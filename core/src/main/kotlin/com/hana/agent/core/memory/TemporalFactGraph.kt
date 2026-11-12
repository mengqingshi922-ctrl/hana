package com.hana.agent.core.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 双时态知识三元组边：`(subject, relation, object, validFrom, invalidAt, supersededBy)`。
 * 发生偏好/事实变更时，旧事实不会被硬删除，而是标记 [invalidAt] 与 [supersededBy]，
 * 从而保留完整的事实演化时间线并支持历史时点回溯。
 */
@Serializable
data class TemporalFact(
    val id: String,
    val subject: String,
    val relation: String,
    @SerialName("object")
    val objectValue: String,
    val validFrom: Long,
    val invalidAt: Long? = null,
    val supersededBy: String? = null,
    val source: String? = null,
) {
    /** 当前时刻是否仍有效（未被替代/失效）。 */
    val isActive: Boolean get() = invalidAt == null

    /** 在指定时点 [atMs] 是否有效（半开区间 `[validFrom, invalidAt)`）。 */
    fun isValidAt(atMs: Long): Boolean = validFrom <= atMs && (invalidAt == null || atMs < invalidAt)
}

/** 双时态图谱持久化抽象。 */
interface TemporalFactPersistence {
    fun load(): List<TemporalFact>
    fun save(facts: List<TemporalFact>)
}

/** 基于轻量级本地 JSON 文件的图谱持久化实现。 */
class FileTemporalFactPersistence(private val file: File) : TemporalFactPersistence {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Synchronized
    override fun load(): List<TemporalFact> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            json.decodeFromString<List<TemporalFact>>(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    override fun save(facts: List<TemporalFact>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(facts))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

/**
 * 轻量双时态关系图谱。
 *
 * 支持：
 * 1. 单值属性/偏好变更自动软失效旧三元组（写 `invalidAt` + `supersededBy`，不硬删）；
 * 2. 多跳实体关联召回（`relatedFacts`），从自然语言查询命中种子实体后沿邻接边扩展；
 * 3. 时点快照查询（`snapshotAt`）与实体演化历史查询（`historyOf`）。
 */
class TemporalFactGraph(
    private val persistence: TemporalFactPersistence? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { "tf_" + UUID.randomUUID().toString().replace("-", "").take(12) },
) {
    constructor(
        file: File,
        clock: () -> Long = System::currentTimeMillis,
        ids: () -> String = { "tf_" + UUID.randomUUID().toString().replace("-", "").take(12) },
    ) : this(FileTemporalFactPersistence(file), clock, ids)

    private val lock = Any()
    private val _facts = MutableStateFlow(loadInitial())
    val facts: StateFlow<List<TemporalFact>> = _facts.asStateFlow()

    private fun loadInitial(): List<TemporalFact> = try {
        persistence?.load().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /** 重新从持久化文件加载。 */
    fun reload(): List<TemporalFact> = synchronized(lock) {
        val loaded = loadInitial()
        _facts.value = loaded
        loaded
    }

    /**
     * 写入或更新一条事实三元组。
     *
     * @param subject 主体（如 "用户"、"公司"）
     * @param relation 关系/谓词（如 "居住地址"、"忌口"、"常用外卖App"）
     * @param objectValue 客体值（如 "张江路100号"、"不要香菜"）
     * @param validFrom 生效时间戳
     * @param supersedeExisting 是否替代同一 `(subject, relation)` 下的冲突旧事实（软置为失效）
     * @param source 来源标识
     * @throws MemoryRejected 若包含敏感凭据（密码/验证码/CVV 等）或字段为空
     */
    @JvmOverloads
    fun assertFact(
        subject: String,
        relation: String,
        objectValue: String,
        validFrom: Long = clock(),
        supersedeExisting: Boolean = true,
        source: String? = null,
    ): TemporalFact = synchronized(lock) {
        val sub = clean(subject)
        val rel = clean(relation)
        val obj = clean(objectValue)
        if (sub.isEmpty() || rel.isEmpty() || obj.isEmpty()) {
            throw MemoryRejected("too_long")
        }
        if (listOf(sub, rel, obj).any { SensitiveFilter.hit(it) != null }) {
            throw MemoryRejected("sensitive")
        }

        val current = _facts.value
        // 幂等：完全相同的有效三元组已存在则直接返回
        current.firstOrNull {
            it.isActive &&
                it.subject.equals(sub, ignoreCase = true) &&
                it.relation.equals(rel, ignoreCase = true) &&
                it.objectValue.equals(obj, ignoreCase = true)
        }?.let { return it }

        val newId = ids()
        val newFact = TemporalFact(
            id = newId,
            subject = sub,
            relation = rel,
            objectValue = obj,
            validFrom = validFrom,
            invalidAt = null,
            supersededBy = null,
            source = source,
        )

        val updated = if (supersedeExisting) {
            current.map { existing ->
                if (existing.isActive &&
                    existing.subject.equals(sub, ignoreCase = true) &&
                    existing.relation.equals(rel, ignoreCase = true)
                ) {
                    existing.copy(invalidAt = validFrom, supersededBy = newId)
                } else {
                    existing
                }
            } + newFact
        } else {
            current + newFact
        }

        commit(updated)
        newFact
    }

    /** 显式将某条事实软标记为失效（不硬删）。 */
    @JvmOverloads
    fun invalidate(
        factId: String,
        invalidAt: Long = clock(),
        supersededBy: String? = null,
    ): Boolean = synchronized(lock) {
        var found = false
        val updated = _facts.value.map { fact ->
            if (fact.id == factId && fact.isActive) {
                found = true
                fact.copy(invalidAt = invalidAt, supersededBy = supersededBy)
            } else fact
        }
        if (found) commit(updated)
        found
    }

    /** 按 `(subject, relation, objectValue?)` 批量软失效匹配的有效事实，返回失效条数。 */
    @JvmOverloads
    fun invalidateWhere(
        subject: String,
        relation: String,
        objectValue: String? = null,
        invalidAt: Long = clock(),
    ): Int = synchronized(lock) {
        val sub = clean(subject)
        val rel = clean(relation)
        val obj = objectValue?.let(::clean)
        var count = 0
        val updated = _facts.value.map { fact ->
            val matchObj = obj == null || fact.objectValue.equals(obj, ignoreCase = true)
            if (fact.isActive &&
                fact.subject.equals(sub, ignoreCase = true) &&
                fact.relation.equals(rel, ignoreCase = true) &&
                matchObj
            ) {
                count++
                fact.copy(invalidAt = invalidAt)
            } else fact
        }
        if (count > 0) commit(updated)
        count
    }

    /**
     * 按实体名（作为 subject 或 object）召回相关事实。
     *
     * @param entity 实体名称或片段
     * @param includeInvalid 是否包含已失效的历史事实
     * @param atMs 若指定时点，则返回该时点有效的事实
     */
    @JvmOverloads
    fun queryByEntity(
        entity: String,
        includeInvalid: Boolean = false,
        atMs: Long? = null,
    ): List<TemporalFact> {
        val needle = clean(entity).lowercase()
        if (needle.isEmpty()) return emptyList()
        return _facts.value.filter { fact ->
            val timeOk = when {
                atMs != null -> fact.isValidAt(atMs)
                includeInvalid -> true
                else -> fact.isActive
            }
            timeOk && (
                fact.subject.lowercase().contains(needle) ||
                    fact.objectValue.lowercase().contains(needle) ||
                    needle.contains(fact.subject.lowercase()) ||
                    needle.contains(fact.objectValue.lowercase())
                )
        }.sortedByDescending { it.validFrom }
    }

    /** 按可选 `(subject, relation, objectValue)` 精确条件查询有效事实。 */
    @JvmOverloads
    fun queryActive(
        subject: String? = null,
        relation: String? = null,
        objectValue: String? = null,
    ): List<TemporalFact> {
        val sub = subject?.let(::clean)
        val rel = relation?.let(::clean)
        val obj = objectValue?.let(::clean)
        return _facts.value.filter { fact ->
            fact.isActive &&
                (sub == null || fact.subject.equals(sub, ignoreCase = true)) &&
                (rel == null || fact.relation.equals(rel, ignoreCase = true)) &&
                (obj == null || fact.objectValue.equals(obj, ignoreCase = true))
        }.sortedByDescending { it.validFrom }
    }

    /**
     * 基于自然语言查询的多跳实体关联召回：
     * 1. 先以查询文本及其分词匹配种子事实（主/谓/宾任意命中）；
     * 2. 再从种子实体出发进行 [hops] 跳邻接图遍历，召回关联实体上的三元组。
     */
    @JvmOverloads
    fun relatedFacts(
        query: String,
        hops: Int = 2,
        limit: Int = 12,
        includeInvalid: Boolean = false,
    ): List<TemporalFact> {
        if (limit <= 0) return emptyList()
        val q = clean(query).lowercase()
        if (q.isEmpty()) return emptyList()
        val pool = if (includeInvalid) _facts.value else _facts.value.filter { it.isActive }
        if (pool.isEmpty()) return emptyList()

        val queryTerms = Tokenize.terms(q, withUnigrams = false).toSet()

        fun matchScore(fact: TemporalFact): Int {
            val s = fact.subject.lowercase()
            val r = fact.relation.lowercase()
            val o = fact.objectValue.lowercase()
            var score = 0
            if (q.contains(s) || s.contains(q)) score += 3
            if (q.contains(r) || r.contains(q)) score += 3
            if (q.contains(o) || o.contains(q)) score += 3
            if (queryTerms.isNotEmpty()) {
                val factTerms = (Tokenize.terms(s) + Tokenize.terms(r) + Tokenize.terms(o)).toSet()
                score += queryTerms.count { it in factTerms } * 2
            }
            return score
        }

        val seedScored = pool.mapNotNull { f ->
            val sc = matchScore(f)
            if (sc > 0) f to sc else null
        }
        if (seedScored.isEmpty()) return emptyList()

        val resultScores = LinkedHashMap<String, Pair<TemporalFact, Double>>()
        val frontierEntities = LinkedHashSet<String>()
        seedScored.forEach { (fact, sc) ->
            resultScores[fact.id] = fact to sc.toDouble()
            frontierEntities += fact.subject.lowercase()
            frontierEntities += fact.objectValue.lowercase()
        }

        val visitedEntities = LinkedHashSet(frontierEntities)
        var currentFrontier = frontierEntities.toSet()
        for (hop in 1 until hops.coerceAtLeast(1)) {
            if (currentFrontier.isEmpty()) break
            val nextFrontier = LinkedHashSet<String>()
            val decay = 1.0 / (hop + 1)
            for (fact in pool) {
                val subLower = fact.subject.lowercase()
                val objLower = fact.objectValue.lowercase()
                if (subLower in currentFrontier || objLower in currentFrontier) {
                    if (fact.id !in resultScores) {
                        resultScores[fact.id] = fact to decay
                    }
                    if (subLower !in visitedEntities) nextFrontier += subLower
                    if (objLower !in visitedEntities) nextFrontier += objLower
                }
            }
            visitedEntities += nextFrontier
            currentFrontier = nextFrontier
        }

        return resultScores.values
            .sortedWith(compareByDescending<Pair<TemporalFact, Double>> { it.second }.thenByDescending { it.first.validFrom })
            .map { it.first }
            .take(limit)
    }

    /** 查询指定 `(subject, relation)` 的完整双时态演化历史（含已失效版本，按时间正序）。 */
    fun historyOf(subject: String, relation: String): List<TemporalFact> {
        val sub = clean(subject)
        val rel = clean(relation)
        return _facts.value.filter {
            it.subject.equals(sub, ignoreCase = true) && it.relation.equals(rel, ignoreCase = true)
        }.sortedBy { it.validFrom }
    }

    /** 查询在指定历史时间戳 [timestampMs] 有效的所有事实快照。 */
    fun snapshotAt(timestampMs: Long): List<TemporalFact> =
        _facts.value.filter { it.isValidAt(timestampMs) }.sortedByDescending { it.validFrom }

    /** 返回图谱中的全部事实（含已失效历史边）。 */
    fun allFacts(): List<TemporalFact> = _facts.value

    /** 清空图谱。 */
    fun clear() = synchronized(lock) {
        commit(emptyList())
    }

    private fun commit(next: List<TemporalFact>) {
        _facts.value = next
        try {
            persistence?.save(next)
        } catch (_: Exception) {
            // 持久化异常不阻断内存态，保持与 TaskMemoryStore 一致
        }
    }

    private fun clean(text: String) = WHITESPACE.replace(text.trim(), " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

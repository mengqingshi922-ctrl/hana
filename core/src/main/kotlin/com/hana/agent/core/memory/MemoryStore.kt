package com.hana.agent.core.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 一次变更;[undoToken] 交给 [MemoryStore.undo] 可恢复到变更之前。 */
sealed interface Change {
    val undoToken: String

    data class Added(val item: MemoryItem, override val undoToken: String) : Change
    data class Updated(val item: MemoryItem, val before: MemoryItem, override val undoToken: String) : Change
    data class Forgot(val before: MemoryItem, override val undoToken: String) : Change
}

/** 整份读写;加密落盘由 app 层实现。 */
interface MemoryPersistence {
    fun load(): List<MemoryItem>
    fun save(items: List<MemoryItem>)
}

/** 冷归档持久化抽象;超出活跃记忆上限时将低分老旧记忆优雅转入冷归档。 */
interface MemoryArchivePersistence {
    fun loadArchive(): List<MemoryItem>
    fun appendArchive(items: List<MemoryItem>)
    fun saveArchive(items: List<MemoryItem>) {}
    fun clearArchive() {}
}

/** 基于本地 JSON 文件的冷归档持久化实现（供 JVM 与非加密场景使用）。 */
class FileMemoryArchivePersistence(private val file: File) : MemoryArchivePersistence {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun loadArchive(): List<MemoryItem> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            json.decodeFromString<List<MemoryItem>>(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    override fun appendArchive(items: List<MemoryItem>) {
        if (items.isEmpty()) return
        val existing = loadArchive()
        val byId = existing.associateByTo(LinkedHashMap()) { it.id }
        items.forEach { byId[it.id] = it }
        saveArchive(byId.values.toList())
    }

    @Synchronized
    override fun saveArchive(items: List<MemoryItem>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(items))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    @Synchronized
    override fun clearArchive() {
        if (file.exists()) file.delete()
    }
}

/** [code]:`sensitive`|`too_long`|`memory_full`|`not_found`|`unavailable`(记忆文件暂时读不出)。 */
class MemoryRejected(val code: String) : Exception(code)

/**
 * 记忆的唯一写入口。所有写方法共用一把锁:并行任务的写入串行化。
 * 写入前过 [SensitiveFilter];每次变更整份落盘;撤销令牌只在内存里保留最近 [UNDO_KEEP] 个。
 *
 * 记忆 2.0：
 * - 引入 Ebbinghaus 遗忘曲线（[lastAccessedAt]、[accessCount]、[strength]），命中或使用时自动强化。
 * - 达到活跃上限 [limit]（> 0）时，按保留分淘汰低分老旧记忆优雅转入冷归档，不再直接拒写 `memory_full`。
 */
class MemoryStore(
    private val persistence: MemoryPersistence,
    val clock: () -> Long,
    private val ids: () -> String,
    val limit: Int = 500,
    private val archivePersistence: MemoryArchivePersistence? = null,
) {
    private val lock = Any()
    // 读不出(密钥库一时不可用)时先当空的用,但不许写:写了就会用空表盖掉真正的记忆文件
    @Volatile private var loaded = false
    private val _items = MutableStateFlow(tryLoad() ?: emptyList())
    val items: StateFlow<List<MemoryItem>> = _items.asStateFlow()

    // 未传入外部归档后端时的内存冷归档缓冲，确保无后端场景下也可查询与恢复
    private val fallbackArchive = LinkedHashMap<String, MemoryItem>()

    /** 记忆文件这次读到了没有;false 时写入一律拒绝(`unavailable`),[reloadIfNeeded] 下次再试。 */
    val available: Boolean get() = loaded

    /** 之前没读出来就再读一次(每个任务开始、每次写入前调用);读到返回 true。 */
    fun reloadIfNeeded(): Boolean = synchronized(lock) {
        if (loaded) return true
        val got = tryLoad() ?: return false
        _items.value = got
        true
    }

    private fun tryLoad(): List<MemoryItem>? = try {
        persistence.load().also { loaded = true }
    } catch (e: Exception) {
        null
    }

    // 插入顺序即新旧顺序,超出时丢最旧的
    private val undos = LinkedHashMap<String, Change>()

    fun add(kind: MemoryKind, content: String, keywords: List<String>, source: MemorySource): Result<Change.Added> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val text = clean(content)
        val kws = cleanKeywords(keywords)
        check(text, kws, source)?.let { return Result.failure(it) }
        if (limit <= 0) return Result.failure(MemoryRejected("memory_full"))

        val now = clock()
        var current = _items.value
        if (current.size >= limit) {
            val needEvict = current.size - limit + 1
            val (kept, evicted) = splitForArchive(current, needEvict, now)
            if (evicted.isEmpty()) return Result.failure(MemoryRejected("memory_full"))
            archiveItems(evicted)
            current = kept
        }

        val item = MemoryItem(
            id = ids(), kind = kind, content = text, keywords = kws, source = source,
            status = if (kind == MemoryKind.procedure) MemoryStatus.pending else MemoryStatus.active,
            createdAt = now, updatedAt = now,
        )
        commit(current + item)
        Result.success(remember { Change.Added(item, it) })
    }

    /**
     * [keywords]/[source] 为 null 时沿用原值。做法被新的非手写来源(模型提取、memory_save)改动后要重新批准;
     * 用户在设置里改(manual)或不带来源的改动保持原状态。
     */
    fun update(id: String, content: String, keywords: List<String>?, source: MemorySource?): Result<Change.Updated> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val before = _items.value.find { it.id == id } ?: return Result.failure(MemoryRejected("not_found"))
        val text = clean(content)
        val kws = keywords?.let(::cleanKeywords) ?: before.keywords
        val src = source ?: before.source
        check(text, kws, src)?.let { return Result.failure(it) }
        val reapprove = before.kind == MemoryKind.procedure && source != null && source.type != "manual"
        val item = before.copy(
            content = text, keywords = kws, source = src, updatedAt = clock(),
            replaces = if (text != before.content) before.content else before.replaces,
            status = if (reapprove) MemoryStatus.pending else before.status,
        )
        commit(_items.value.map { if (it.id == id) item else it })
        Result.success(remember { Change.Updated(item, before, it) })
    }

    fun forget(id: String): Result<Change.Forgot> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val before = _items.value.find { it.id == id } ?: return Result.failure(MemoryRejected("not_found"))
        commit(_items.value.filter { it.id != id })
        Result.success(remember { Change.Forgot(before, it) })
    }

    /**
     * 令牌未知、过期或已用过 → false。更新的撤销只在条目仍是这次改后的样子时才做(之后又被改过/忘掉 → false),
     * 免得把后来的改动一并抹掉。落盘失败时抛出,令牌保留可重试。
     */
    fun undo(token: String): Boolean = synchronized(lock) {
        val change = undos[token] ?: return false
        val cur = _items.value
        val next = when (change) {
            is Change.Added -> cur.filter { it.id != change.item.id }
            is Change.Updated -> {
                // 只看这次改动的字段;之后的使用计数、访问强化、置顶不算「又被改过」,恢复时也保留
                val now = cur.find { it.id == change.item.id }
                if (now == null || now.content != change.item.content || now.keywords != change.item.keywords || now.source != change.item.source) return false
                val restored = change.before.copy(
                    useCount = now.useCount,
                    lastUsedAt = now.lastUsedAt,
                    pinned = now.pinned,
                    accessCount = now.accessCount,
                    lastAccessedAt = now.lastAccessedAt,
                    strength = now.strength,
                )
                cur.map { if (it.id == restored.id) restored else it }
            }
            is Change.Forgot -> cur.filter { it.id != change.before.id } + change.before
        }
        commit(next)
        undos.remove(token)
        true
    }

    fun approve(id: String): Boolean = synchronized(lock) {
        if (!loaded) return false
        val cur = _items.value
        if (cur.none { it.id == id && it.status == MemoryStatus.pending }) return false
        commit(cur.map { if (it.id == id) it.copy(status = MemoryStatus.active) else it })
        true
    }

    fun setPinned(id: String, pinned: Boolean): Unit = synchronized(lock) {
        if (!loaded) return
        val cur = _items.value
        if (cur.none { it.id == id }) return
        commit(cur.map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    fun markUsed(ids: List<String>): Unit = synchronized(lock) {
        if (ids.isEmpty() || !loaded) return
        val set = ids.toSet()
        val now = clock()
        commit(_items.value.map {
            if (it.id in set) reinforceItem(it, now, incrementUse = true) else it
        })
    }

    /** 记录检索命中或访问，自动强化记忆 [strength]、[accessCount] 与 [lastAccessedAt]。 */
    fun markAccessed(ids: List<String>): Unit = synchronized(lock) {
        if (ids.isEmpty() || !loaded) return
        val set = ids.toSet()
        val now = clock()
        var changed = false
        val next = _items.value.map {
            if (it.id in set) {
                changed = true
                reinforceItem(it, now, incrementUse = false)
            } else it
        }
        if (changed) commit(next)
    }

    /**
     * 基于 Ebbinghaus 时间衰减的记忆检索；命中后默认自动强化被召回记忆的 [strength]。
     */
    fun search(
        query: String,
        limit: Int = 8,
        includePending: Boolean = false,
        reinforce: Boolean = true,
    ): List<Pair<MemoryItem, Double>> = synchronized(lock) {
        if (!reloadIfNeeded()) return emptyList()
        val now = clock()
        val hits = MemoryIndex(_items.value, now).search(query, limit, includePending)
        if (!reinforce || hits.isEmpty()) return hits
        val hitIds = hits.map { it.first.id }.toSet()
        val reinforcedById = HashMap<String, MemoryItem>(hitIds.size)
        val next = _items.value.map {
            if (it.id in hitIds) reinforceItem(it, now, incrementUse = false).also { r -> reinforcedById[r.id] = r } else it
        }
        commit(next)
        hits.map { (item, score) -> (reinforcedById[item.id] ?: item) to score }
    }

    /** 查看冷归档中的记忆条目。 */
    fun archivedItems(): List<MemoryItem> = synchronized(lock) {
        val ap = archivePersistence
        if (ap != null) {
            runCatching { ap.loadArchive() }.getOrDefault(emptyList())
        } else {
            fallbackArchive.values.toList()
        }
    }

    /** 在冷归档中按关键词检索老旧记忆。 */
    fun searchArchive(query: String, limit: Int = 8, includePending: Boolean = false): List<Pair<MemoryItem, Double>> = synchronized(lock) {
        val archived = archivedItems()
        if (archived.isEmpty()) return emptyList()
        MemoryIndex(archived, clock()).search(query, limit, includePending)
    }

    /** 将指定冷归档记忆恢复到活跃区并强化其保持强度；若活跃区已满则置换最低分条目入归档。 */
    fun restoreFromArchive(id: String): Result<MemoryItem> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        if (limit <= 0) return Result.failure(MemoryRejected("memory_full"))
        val archived = archivedItems()
        val target = archived.find { it.id == id } ?: return Result.failure(MemoryRejected("not_found"))
        val now = clock()
        val restored = reinforceItem(target, now, incrementUse = false)

        var current = _items.value.filterNot { it.id == id }
        val newlyEvicted = if (current.size >= limit) {
            val (kept, evicted) = splitForArchive(current, current.size - limit + 1, now)
            current = kept
            evicted
        } else {
            emptyList()
        }

        val remainingArchive = archived.filterNot { it.id == id } + newlyEvicted
        replaceArchive(remainingArchive)
        commit(current + restored)
        Result.success(restored)
    }

    /** 合并写入：供巩固器将多条近似记忆的计数与关键字合并回指定条目。 */
    fun mergeStats(id: String, mergedKeywords: List<String>, extraAccessCount: Int, extraUseCount: Int, minStrength: Double, pinned: Boolean): Boolean = synchronized(lock) {
        if (!reloadIfNeeded()) return false
        val target = _items.value.find { it.id == id } ?: return false
        val kws = cleanKeywords(mergedKeywords)
        val updated = target.copy(
            keywords = kws,
            accessCount = target.accessCount + extraAccessCount.coerceAtLeast(0),
            useCount = target.useCount + extraUseCount.coerceAtLeast(0),
            strength = maxOf(target.strength, minStrength).coerceAtMost(EbbinghausRetention.MAX_STRENGTH),
            pinned = target.pinned || pinned,
            updatedAt = clock(),
        )
        commit(_items.value.map { if (it.id == id) updated else it })
        true
    }

    /** 用户要全部清除:读不出的原文件也一并用空表覆盖,这正是用户的意思。 */
    fun clearAll(): Unit = synchronized(lock) {
        undos.clear()
        fallbackArchive.clear()
        runCatching { archivePersistence?.clearArchive() }
        loaded = true
        commit(emptyList())
    }

    private fun reinforceItem(item: MemoryItem, now: Long, incrementUse: Boolean): MemoryItem {
        val refTime = item.lastAccessedAt ?: item.lastUsedAt ?: item.updatedAt
        val nextStrength = EbbinghausRetention.reinforce(item.strength, refTime, now)
        return item.copy(
            useCount = if (incrementUse) item.useCount + 1 else item.useCount,
            lastUsedAt = if (incrementUse) now else item.lastUsedAt,
            accessCount = item.accessCount + 1,
            lastAccessedAt = now,
            strength = nextStrength,
        )
    }

    private fun splitForArchive(items: List<MemoryItem>, count: Int, now: Long): Pair<List<MemoryItem>, List<MemoryItem>> {
        if (count <= 0 || items.isEmpty()) return items to emptyList()
        val ranked = items.sortedWith(
            compareBy<MemoryItem> {
                EbbinghausRetention.archivalScore(
                    lastTimeMs = it.lastAccessedAt ?: it.lastUsedAt ?: it.updatedAt,
                    nowMs = now,
                    strength = it.strength,
                    accessCount = it.accessCount,
                    useCount = it.useCount,
                    pinned = it.pinned,
                )
            }.thenBy { it.updatedAt }.thenBy { it.createdAt }
        )
        val evictSet = ranked.take(count).map { it.id }.toSet()
        val evicted = items.filter { it.id in evictSet }
        val kept = items.filterNot { it.id in evictSet }
        return kept to evicted
    }

    private fun archiveItems(items: List<MemoryItem>) {
        if (items.isEmpty()) return
        val ap = archivePersistence
        if (ap != null) {
            runCatching { ap.appendArchive(items) }
        } else {
            items.forEach { fallbackArchive[it.id] = it }
        }
    }

    private fun replaceArchive(items: List<MemoryItem>) {
        val ap = archivePersistence
        if (ap != null) {
            runCatching { ap.saveArchive(items) }
        } else {
            fallbackArchive.clear()
            items.forEach { fallbackArchive[it.id] = it }
        }
    }

    private fun check(content: String, keywords: List<String>, source: MemorySource): MemoryRejected? {
        if (content.isEmpty() || content.length > MAX_CONTENT) return MemoryRejected("too_long")
        val texts = listOf(content) + keywords + listOfNotNull(source.quote)
        if (texts.any { SensitiveFilter.hit(it) != null }) return MemoryRejected("sensitive")
        return null
    }

    // 换行、连续空白压成一个空格:记忆块按行编号,内容里不能有换行
    private fun clean(content: String) = WHITESPACE.replace(content.trim(), " ")

    private fun cleanKeywords(keywords: List<String>) =
        keywords.map(::clean).filter { it.isNotEmpty() }.distinct().take(MAX_KEYWORDS)

    private fun commit(next: List<MemoryItem>) {
        // 兜底:任何写入都不能在没读到原文件时发生
        check(loaded) { "memory not loaded" }
        persistence.save(next)
        _items.value = next
    }

    private fun <C : Change> remember(make: (String) -> C): C {
        val change = make(ids())
        undos[change.undoToken] = change
        while (undos.size > UNDO_KEEP) undos.remove(undos.keys.first())
        return change
    }

    companion object {
        const val MAX_CONTENT = 200
        const val MAX_KEYWORDS = 8
        const val UNDO_KEEP = 50
        private val WHITESPACE = Regex("\\s+")
    }
}

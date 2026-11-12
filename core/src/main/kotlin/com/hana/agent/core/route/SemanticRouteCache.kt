package com.hana.agent.core.route

import com.hana.agent.core.experience.UiExperienceEntry
import com.hana.agent.core.experience.UiExperienceGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 轻量级纯 Kotlin 多语语义向量化器：
 * 结合字符 N-gram（1/2/3-gram）与词元特征，配合动态 TF-IDF 权重和 L2 归一化，
 * 无需外部模型文件即可实现中/英/日/韩混合意图的毫秒级余弦相似度匹配。
 */
object MultilingualSemanticVectorizer {
    private val PUNCT_REGEX = Regex("[\\p{P}\\p{S}]+")
    private val SPACE_REGEX = Regex("\\s+")
    private val WORD_REGEX = Regex("[a-z0-9_]{2,}")

    private val ZH_NEGATIONS = listOf("放弃", "取消", "不要", "不用", "别", "不", "退", "拒绝", "禁止", "莫")
    private val EN_NEGATION_REGEX = Regex("""\b(?:not|no|never|don't|dont|cannot|can't|cancel|stop|avoid|skip|refuse)\b""")

    fun normalize(text: String): String =
        PUNCT_REGEX.replace(text.lowercase(Locale.ROOT), " ")
            .let { SPACE_REGEX.replace(it, " ") }
            .trim()

    /**
     * 双向否定词语义护栏：输入与候选之间只要有一方包含否定词而另一方没有，即判定存在语义反转冲突。
     */
    fun hasNegationMismatch(query: String, candidate: String): Boolean {
        val qNorm = normalize(query)
        val cNorm = normalize(candidate)
        val qZh = ZH_NEGATIONS.filter { qNorm.contains(it) }.toSet()
        val cZh = ZH_NEGATIONS.filter { cNorm.contains(it) }.toSet()
        if (qZh != cZh) return true

        val qEn = EN_NEGATION_REGEX.findAll(qNorm).map { it.value }.toSet()
        val cEn = EN_NEGATION_REGEX.findAll(cNorm).map { it.value }.toSet()
        return qEn != cEn
    }

    /**
     * 提取多语种混合特征词袋（原始加权频次）：
     * - 字符 1-gram（权重 1.0）
     * - 字符 2-gram（权重 1.0）
     * - 字符 3-gram（权重 0.5）
     * - 英文/数字词元（权重 0.8）
     */
    fun extractFeatures(text: String): Map<String, Double> {
        val norm = normalize(text)
        if (norm.isEmpty()) return emptyMap()
        val features = LinkedHashMap<String, Double>()
        fun add(key: String, weight: Double) {
            features[key] = (features[key] ?: 0.0) + weight
        }

        val compact = norm.replace(" ", "")
        for (i in compact.indices) {
            add("c1:${compact[i]}", 1.0)
            if (i + 1 < compact.length) {
                add("c2:${compact.substring(i, i + 2)}", 1.0)
            }
            if (i + 2 < compact.length) {
                add("c3:${compact.substring(i, i + 3)}", 0.5)
            }
        }
        WORD_REGEX.findAll(norm).forEach { match ->
            add("w:${match.value}", 0.8)
        }
        return features
    }

    fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (smaller, larger) = if (a.size <= b.size) a to b else b to a
        var dot = 0.0
        for ((k, v) in smaller) {
            val other = larger[k] ?: continue
            dot += v * other
        }
        return dot.coerceIn(0.0, 1.0)
    }
}

/**
 * 动态 TF-IDF 稀疏向量索引。
 */
internal class TfIdfVectorIndex {
    private var docCount = 0
    private val docFreq = HashMap<String, Int>()

    fun rebuild(documents: Iterable<String>) {
        docCount = 0
        docFreq.clear()
        for (doc in documents) {
            val features = MultilingualSemanticVectorizer.extractFeatures(doc)
            if (features.isEmpty()) continue
            docCount++
            for (term in features.keys) {
                docFreq[term] = (docFreq[term] ?: 0) + 1
            }
        }
    }

    fun vectorize(text: String): Map<String, Double> {
        val raw = MultilingualSemanticVectorizer.extractFeatures(text)
        if (raw.isEmpty()) return emptyMap()
        val weighted = HashMap<String, Double>(raw.size)
        var normSq = 0.0
        val n = docCount.coerceAtLeast(1)
        for ((term, tf) in raw) {
            val df = docFreq[term] ?: 0
            val idf = if (df == 0) 1.0 else ln((n + 1.0) / (df + 1.0)) + 1.0
            val w = tf * idf
            weighted[term] = w
            normSq += w * w
        }
        if (normSq <= 0.0) return emptyMap()
        val invNorm = 1.0 / sqrt(normSq)
        for (entry in weighted.entries) {
            entry.setValue(entry.value * invNorm)
        }
        return weighted
    }
}

@Serializable
data class CachedRouteGraphEntry(
    val graph: RouteGraph,
    val appId: String? = null,
    val entryNodeId: String = graph.initialNodeId,
    val intentAliases: List<String> = emptyList(),
    val hitCount: Int = 1,
    val lastVerifiedTimestamp: Long = System.currentTimeMillis(),
    val lastAccessedTimestamp: Long = lastVerifiedTimestamp,
)

@Serializable
data class SemanticRouteCacheSnapshot(
    val version: Int = 1,
    val experiences: List<UiExperienceEntry> = emptyList(),
    val routeGraphs: List<CachedRouteGraphEntry> = emptyList(),
)

data class RouteCacheHit(
    val graph: RouteGraph,
    val targetNode: RouteGraphNode?,
    val similarity: Double,
    val matchedIntent: String,
    val exactMatch: Boolean,
)

data class ExperienceCacheHit(
    val entry: UiExperienceEntry,
    val similarity: Double,
    val exactMatch: Boolean,
)

/**
 * 持久化语义路由与 UI 经验图谱缓存：
 * 1. 实现 [UiExperienceGraph] 契约并将经验条目持久化到本地 JSON 文件，支持冷启动自动恢复；
 * 2. 提供基于纯 Kotlin 字符 N-gram + TF-IDF 余弦相似度（默认阈值 >= 0.90）的意图到 [RouteGraph] 节点零 Token 快速命中；
 * 3. 原子落盘（tmp + fsync + ATOMIC_MOVE）与损坏容错，确保进程异常退出不丢失历史经验。
 */
class SemanticRouteCache(
    private val file: File,
    val similarityThreshold: Double = DEFAULT_SIMILARITY_THRESHOLD,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) : UiExperienceGraph {

    init {
        require(similarityThreshold in 0.0..1.0) { "similarityThreshold must be in [0.0, 1.0]" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val lock = Any()
    private val experiences = mutableListOf<UiExperienceEntry>()
    private val routeGraphs = LinkedHashMap<String, CachedRouteGraphEntry>()
    private val tfidfIndex = TfIdfVectorIndex()
    private val experienceVectors = HashMap<Int, Map<String, Double>>()
    private val graphIntentVectors = HashMap<String, List<Pair<String, Map<String, Double>>>>()

    init {
        loadFromDiskLocked()
    }

    override suspend fun lookup(appId: String, pageSignature: String, intent: String): UiExperienceEntry? {
        if (intent.isBlank()) return null
        val trimmed = intent.trim()
        synchronized(lock) {
            val scoped = experiences.filter { it.appId == appId && it.pageSignature == pageSignature }
            if (scoped.isEmpty()) return null

            // 1. 精确匹配优先
            val exact = scoped.filter { it.intentKeyword.equals(trimmed, ignoreCase = true) }
            if (exact.isNotEmpty()) {
                return exact.maxByOrNull { it.successCount }
            }

            // 2. 语义向量余弦相似度命中 (>= similarityThreshold)，并做否定词双向保护
            val queryVec = tfidfIndex.vectorize(trimmed)
            var bestSemantic: UiExperienceEntry? = null
            var bestScore = -1.0
            for (candidate in scoped) {
                val kw = candidate.intentKeyword.trim()
                if (kw.isEmpty() || MultilingualSemanticVectorizer.hasNegationMismatch(trimmed, kw)) continue
                val idx = experiences.indexOf(candidate)
                val vec = experienceVectors[idx] ?: tfidfIndex.vectorize(kw)
                val sim = MultilingualSemanticVectorizer.cosine(queryVec, vec)
                if (sim >= similarityThreshold) {
                    if (sim > bestScore || (sim == bestScore && candidate.successCount > (bestSemantic?.successCount ?: -1))) {
                        bestScore = sim
                        bestSemantic = candidate
                    }
                }
            }
            if (bestSemantic != null) return bestSemantic

            // 3. 兼容原有正向包含匹配（输入包含条目关键字），并保持否定词保护
            return scoped.filter { candidate ->
                val kw = candidate.intentKeyword.trim()
                kw.isNotEmpty() &&
                    trimmed.contains(kw, ignoreCase = true) &&
                    !MultilingualSemanticVectorizer.hasNegationMismatch(trimmed, kw)
            }.maxByOrNull { it.successCount }
        }
    }

    fun lookupSemanticExperience(
        appId: String,
        pageSignature: String,
        intent: String,
        minSimilarity: Double = similarityThreshold,
    ): ExperienceCacheHit? {
        if (intent.isBlank()) return null
        val trimmed = intent.trim()
        synchronized(lock) {
            val scoped = experiences.filter { it.appId == appId && it.pageSignature == pageSignature }
            if (scoped.isEmpty()) return null

            val exact = scoped.filter { it.intentKeyword.equals(trimmed, ignoreCase = true) }
                .maxByOrNull { it.successCount }
            if (exact != null) {
                return ExperienceCacheHit(exact, similarity = 1.0, exactMatch = true)
            }

            val queryVec = tfidfIndex.vectorize(trimmed)
            var best: ExperienceCacheHit? = null
            for (candidate in scoped) {
                val kw = candidate.intentKeyword.trim()
                if (kw.isEmpty() || MultilingualSemanticVectorizer.hasNegationMismatch(trimmed, kw)) continue
                val idx = experiences.indexOf(candidate)
                val vec = experienceVectors[idx] ?: tfidfIndex.vectorize(kw)
                val sim = MultilingualSemanticVectorizer.cosine(queryVec, vec)
                if (sim >= minSimilarity) {
                    if (best == null || sim > best.similarity ||
                        (sim == best.similarity && candidate.successCount > best.entry.successCount)
                    ) {
                        best = ExperienceCacheHit(candidate, similarity = sim, exactMatch = false)
                    }
                }
            }
            return best
        }
    }

    override suspend fun recordSuccess(entry: UiExperienceEntry) {
        synchronized(lock) {
            val idx = experiences.indexOfFirst {
                it.appId == entry.appId &&
                    it.pageSignature == entry.pageSignature &&
                    it.intentKeyword.equals(entry.intentKeyword, ignoreCase = true) &&
                    it.macroActions == entry.macroActions
            }
            if (idx >= 0) {
                val existing = experiences[idx]
                experiences[idx] = existing.copy(
                    successCount = existing.successCount + 1,
                    lastVerifiedTimestamp = clock(),
                )
            } else {
                experiences.add(entry.copy(lastVerifiedTimestamp = if (entry.lastVerifiedTimestamp > 0) entry.lastVerifiedTimestamp else clock()))
            }
            trimCapacityLocked()
            rebuildIndexLocked()
            persistLocked()
        }
    }

    override suspend fun invalidate(appId: String, pageSignature: String) {
        synchronized(lock) {
            val removedExp = experiences.removeAll { it.appId == appId && it.pageSignature == pageSignature }
            val removedGraphIds = routeGraphs.entries
                .filter { (_, v) ->
                    (v.appId == null || v.appId == appId) &&
                        v.graph.nodes.values.any { it.pageSignature == pageSignature }
                }
                .map { it.key }
            removedGraphIds.forEach { routeGraphs.remove(it) }
            if (removedExp || removedGraphIds.isNotEmpty()) {
                rebuildIndexLocked()
                persistLocked()
            }
        }
    }

    suspend fun putRouteGraph(
        graph: RouteGraph,
        appId: String? = null,
        entryNodeId: String = graph.initialNodeId,
        intentAliases: List<String> = emptyList(),
    ) {
        synchronized(lock) {
            val now = clock()
            val existing = routeGraphs[graph.graphId]
            val mergedAliases = (existing?.intentAliases.orEmpty() + intentAliases)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(graph.intentKeyword.trim(), ignoreCase = true) }
                .distinct()
            val updated = CachedRouteGraphEntry(
                graph = graph,
                appId = appId ?: existing?.appId,
                entryNodeId = if (graph.nodes.containsKey(entryNodeId)) entryNodeId else graph.initialNodeId,
                intentAliases = mergedAliases,
                hitCount = (existing?.hitCount ?: 0) + 1,
                lastVerifiedTimestamp = now,
                lastAccessedTimestamp = now,
            )
            routeGraphs[graph.graphId] = updated
            trimCapacityLocked()
            rebuildIndexLocked()
            persistLocked()
        }
    }

    /**
     * 语义匹配用户意图到缓存的 [RouteGraph] 与目标节点：
     * 余弦相似度 >= [minSimilarity]（默认 0.90）且通过否定词保护时立即零 Token 命中。
     */
    suspend fun lookupRouteGraph(
        intent: String,
        appId: String? = null,
        pageSignature: String? = null,
        minSimilarity: Double = similarityThreshold,
    ): RouteCacheHit? {
        if (intent.isBlank()) return null
        val trimmed = intent.trim()
        synchronized(lock) {
            val candidates = routeGraphs.values.filter { entry ->
                appId == null || entry.appId == null || entry.appId == appId
            }
            if (candidates.isEmpty()) return null

            val queryVec = tfidfIndex.vectorize(trimmed)
            var bestHit: RouteCacheHit? = null
            var bestEntry: CachedRouteGraphEntry? = null

            for (entry in candidates) {
                val intents = listOf(entry.graph.intentKeyword) + entry.intentAliases
                for (candidateIntent in intents) {
                    val kw = candidateIntent.trim()
                    if (kw.isEmpty() || MultilingualSemanticVectorizer.hasNegationMismatch(trimmed, kw)) continue

                    val exact = kw.equals(trimmed, ignoreCase = true)
                    val sim = if (exact) {
                        1.0
                    } else {
                        val vec = graphIntentVectors[entry.graph.graphId]
                            ?.firstOrNull { it.first == kw }?.second
                            ?: tfidfIndex.vectorize(kw)
                        MultilingualSemanticVectorizer.cosine(queryVec, vec)
                    }
                    if (sim < minSimilarity) continue

                    val node = resolveTargetNode(entry, pageSignature)
                    val isBetter = bestHit == null ||
                        (exact && !bestHit.exactMatch) ||
                        (exact == bestHit.exactMatch && sim > bestHit.similarity) ||
                        (exact == bestHit.exactMatch && sim == bestHit.similarity && entry.hitCount > (bestEntry?.hitCount ?: 0))

                    if (isBetter) {
                        bestHit = RouteCacheHit(
                            graph = entry.graph,
                            targetNode = node,
                            similarity = sim,
                            matchedIntent = kw,
                            exactMatch = exact,
                        )
                        bestEntry = entry
                    }
                }
            }

            if (bestEntry != null) {
                routeGraphs[bestEntry.graph.graphId] = bestEntry.copy(
                    hitCount = bestEntry.hitCount + 1,
                    lastAccessedTimestamp = clock(),
                )
            }
            return bestHit
        }
    }

    suspend fun invalidateRouteGraph(graphId: String) {
        synchronized(lock) {
            if (routeGraphs.remove(graphId) != null) {
                rebuildIndexLocked()
                persistLocked()
            }
        }
    }

    suspend fun flush() {
        synchronized(lock) {
            persistLocked()
        }
    }

    fun size(): Int = synchronized(lock) { experiences.size }

    fun routeGraphCount(): Int = synchronized(lock) { routeGraphs.size }

    fun allRouteGraphs(): List<CachedRouteGraphEntry> = synchronized(lock) { routeGraphs.values.toList() }

    fun allExperiences(): List<UiExperienceEntry> = synchronized(lock) { experiences.toList() }

    fun clear() {
        synchronized(lock) {
            experiences.clear()
            routeGraphs.clear()
            experienceVectors.clear()
            graphIntentVectors.clear()
            tfidfIndex.rebuild(emptyList())
            if (file.exists()) file.delete()
        }
    }

    private fun resolveTargetNode(entry: CachedRouteGraphEntry, pageSignature: String?): RouteGraphNode? {
        if (pageSignature != null) {
            entry.graph.nodes.values.firstOrNull { it.pageSignature == pageSignature }?.let { return it }
        }
        return entry.graph.nodes[entry.entryNodeId] ?: entry.graph.nodes[entry.graph.initialNodeId]
    }

    private fun trimCapacityLocked() {
        val total = experiences.size + routeGraphs.size
        if (total <= maxEntries) return
        var overflow = total - maxEntries
        while (overflow > 0 && experiences.isNotEmpty()) {
            val victimIdx = experiences.indices.minByOrNull { i ->
                val e = experiences[i]
                e.successCount.toLong() * 1_000_000_000_000L + e.lastVerifiedTimestamp
            } ?: break
            experiences.removeAt(victimIdx)
            overflow--
        }
        while (overflow > 0 && routeGraphs.isNotEmpty()) {
            val victimKey = routeGraphs.entries.minByOrNull { (_, v) ->
                v.hitCount.toLong() * 1_000_000_000_000L + v.lastAccessedTimestamp
            }?.key ?: break
            routeGraphs.remove(victimKey)
            overflow--
        }
    }

    private fun rebuildIndexLocked() {
        val docs = ArrayList<String>(experiences.size + routeGraphs.size * 2)
        experiences.forEach { docs += it.intentKeyword }
        routeGraphs.values.forEach { entry ->
            docs += entry.graph.intentKeyword
            docs.addAll(entry.intentAliases)
        }
        tfidfIndex.rebuild(docs)

        experienceVectors.clear()
        experiences.forEachIndexed { idx, entry ->
            experienceVectors[idx] = tfidfIndex.vectorize(entry.intentKeyword)
        }

        graphIntentVectors.clear()
        routeGraphs.values.forEach { entry ->
            val vecs = (listOf(entry.graph.intentKeyword) + entry.intentAliases)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { kw -> kw to tfidfIndex.vectorize(kw) }
            graphIntentVectors[entry.graph.graphId] = vecs
        }
    }

    private fun loadFromDiskLocked() {
        if (!file.exists() || !file.isFile) {
            rebuildIndexLocked()
            return
        }
        val snapshot = runCatching {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) null else json.decodeFromString(SemanticRouteCacheSnapshot.serializer(), raw)
        }.getOrNull()
        if (snapshot != null) {
            experiences.clear()
            experiences.addAll(snapshot.experiences)
            routeGraphs.clear()
            snapshot.routeGraphs.forEach { entry ->
                routeGraphs[entry.graph.graphId] = entry
            }
        }
        rebuildIndexLocked()
    }

    private fun persistLocked() {
        val snapshot = SemanticRouteCacheSnapshot(
            version = CURRENT_VERSION,
            experiences = experiences.toList(),
            routeGraphs = routeGraphs.values.toList(),
        )
        val encoded = json.encodeToString(SemanticRouteCacheSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(encoded)
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.90
        const val DEFAULT_MAX_ENTRIES = 512

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

package com.hana.agent.core.memory

import kotlin.math.ln
import kotlin.math.max

/**
 * 跨会话任务记忆的内存倒排与关键词索引。
 * 支持根据意图关键词、特定 App、域名以及关键操作词（如「搜索」「下单」「比价」）进行相关度打分与模糊召回。
 */
class TaskMemoryIndex(
    private val memories: List<TaskMemory>,
    private val nowMs: Long = System.currentTimeMillis(),
) {

    private data class IndexedDoc(
        val memory: TaskMemory,
        val termFrequencies: Map<String, Double>,
        val appTokens: Set<String>,
        val domainTokens: Set<String>,
        val length: Double,
    )

    private val docs: List<IndexedDoc> = memories.map { mem ->
        val tf = mutableMapOf<String, Double>()

        fun addTerms(text: String, weight: Double, withUnigrams: Boolean = false) {
            if (text.isBlank()) return
            Tokenize.terms(text.lowercase(), withUnigrams = withUnigrams).forEach { term ->
                tf[term] = (tf[term] ?: 0.0) + weight
            }
        }

        // 1. 任务目标 (高权重)
        addTerms(mem.goal, WEIGHT_GOAL, withUnigrams = true)

        // 2. 任务结论
        addTerms(mem.conclusion, WEIGHT_CONCLUSION, withUnigrams = true)

        // 3. 涉及的 App
        val appTokens = mutableSetOf<String>()
        mem.apps.forEach { app ->
            val clean = app.lowercase()
            appTokens += clean
            // 分解形如 com.taobao.taobao，去除通用前缀 (com, cn 等)
            clean.split('.', '-', '_')
                .filter { it.isNotBlank() && it.length > 2 && it !in COMMON_APP_PREFIXES }
                .forEach {
                    appTokens += it
                    tf[it] = (tf[it] ?: 0.0) + WEIGHT_APP
                }
            addTerms(clean, WEIGHT_APP, withUnigrams = true)
        }

        // 4. 涉及的域名
        val domainTokens = mutableSetOf<String>()
        mem.domains.forEach { domain ->
            val clean = domain.lowercase()
            domainTokens += clean
            clean.split('.', '-', '_')
                .filter { it.isNotBlank() && it.length > 2 && it !in COMMON_APP_PREFIXES }
                .forEach {
                    domainTokens += it
                    tf[it] = (tf[it] ?: 0.0) + WEIGHT_DOMAIN
                }
            addTerms(clean, WEIGHT_DOMAIN, withUnigrams = false)
        }

        // 5. 关键操作步骤序列中的动作词与用意说明 (操作词模糊召回)
        mem.keySteps.forEach { step ->
            addTerms(step.tool, WEIGHT_STEP_TOOL, withUnigrams = false)
            addTerms(step.action, WEIGHT_STEP_ACTION, withUnigrams = true)
            step.note?.let { addTerms(it, WEIGHT_STEP_NOTE, withUnigrams = false) }
        }

        val totalLen = tf.values.sum().coerceAtLeast(1.0)
        IndexedDoc(mem, tf, appTokens, domainTokens, totalLen)
    }

    private val corpusSize = max(docs.size, MIN_CORPUS).toDouble()
    private val avgDocLength = if (docs.isEmpty()) 1.0 else docs.sumOf { it.length } / docs.size

    /**
     * 按意图关键词或应用搜索历史相关任务。
     *
     * @param query 意图关键词、操作词（如 "点外卖", "淘宝比价", "搜运动鞋"）
     * @param app 限定目标应用包名或关键词（可选，如 "com.taobao.taobao" 或 "taobao"）
     * @param limit 返回结果上限
     * @return 按相关度从高到低排序的任务记忆列表
     */
    fun search(query: String, app: String? = null, limit: Int = 10): List<TaskMemory> {
        if (docs.isEmpty() || limit <= 0) return emptyList()

        val cleanApp = app?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val q = query.trim().lowercase()
        val queryTerms = Tokenize.terms(q, withUnigrams = true).distinct().filter { it !in STOP_TERMS }

        // 如果既无查询词也无 app 限定，按时间最新返回
        if (queryTerms.isEmpty() && cleanApp == null) {
            return memories.sortedByDescending { it.timestamp }.take(limit)
        }

        val queryMultiTerms = queryTerms.filterNot(Tokenize::isUnigram)

        // 计算包含 queryTerms 的文档频次 df
        val df = queryTerms.associateWith { t -> docs.count { t in it.termFrequencies } }
        val idf = df.mapValues { (_, n) -> ln(1 + (corpusSize - n + 0.5) / (n + 0.5)) }

        val scored = docs.mapNotNull { doc ->
            // 1. 若显式指定了 app，进行严格的 App 判定
            val matchedApp = if (cleanApp != null) {
                doc.memory.apps.any {
                    it.equals(cleanApp, ignoreCase = true) ||
                        it.contains(cleanApp, ignoreCase = true) ||
                        cleanApp.contains(it, ignoreCase = true)
                } || doc.appTokens.any { token ->
                    token !in COMMON_APP_PREFIXES && (token == cleanApp || cleanApp.contains(token) || token.contains(cleanApp))
                }
            } else false

            // 若指定了 app 但未匹配上，坚决过滤掉
            if (cleanApp != null && !matchedApp) return@mapNotNull null

            val matchedTerms = queryTerms.filter { it in doc.termFrequencies }
            val matchedMulti = matchedTerms.filterNot(Tokenize::isUnigram)

            // 2. 若提供了查询词，必须命中查询词（多字词、单字词或两个以上单字）
            if (queryTerms.isNotEmpty()) {
                val queryHit = matchedMulti.isNotEmpty() ||
                    (queryMultiTerms.isEmpty() && matchedTerms.isNotEmpty()) ||
                    matchedTerms.size >= 2
                if (!queryHit) return@mapNotNull null
            }

            var score = 0.0
            for (term in matchedTerms) {
                val tf = doc.termFrequencies[term] ?: continue
                val termIdf = idf[term] ?: 1.0
                // BM25 核心公式
                val norm = K1 * (1 - B + B * doc.length / avgDocLength)
                score += termIdf * tf * (K1 + 1.0) / (tf + norm)
            }

            // 如果指定了 app 且匹配上了，给显著额外加分
            if (matchedApp) {
                score += APP_EXPLICIT_BONUS
            }

            // 成功任务加分
            if (doc.memory.success) {
                score += SUCCESS_BONUS
            }

            // 引入 Ebbinghaus 时间衰减因子 R = max(0.3, exp(-deltaDays / strength))
            val refTime = doc.memory.lastAccessedAt ?: doc.memory.timestamp
            val retention = EbbinghausRetention.retention(refTime, nowMs, doc.memory.strength)
            doc.memory to (score * retention)
        }

        return scored.sortedByDescending { it.second }.map { it.first }.take(limit)
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75
        private const val MIN_CORPUS = 5

        private const val WEIGHT_GOAL = 3.0
        private const val WEIGHT_CONCLUSION = 1.2
        private const val WEIGHT_APP = 2.5
        private const val WEIGHT_DOMAIN = 2.0
        private const val WEIGHT_STEP_ACTION = 2.0
        private const val WEIGHT_STEP_TOOL = 1.0
        private const val WEIGHT_STEP_NOTE = 1.2

        private const val APP_EXPLICIT_BONUS = 5.0
        private const val SUCCESS_BONUS = 0.5

        private val COMMON_APP_PREFIXES = setOf("com", "cn", "net", "org", "app", "android")
        private val STOP_TERMS = setOf("的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这")
    }
}

package com.hana.agent.core.memory

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.min

/** 睡眠期整合执行报告。 */
data class ConsolidationReport(
    val deduplicatedCount: Int,
    val routinesDetected: List<RoutineSuggestion>,
    val markdownLength: Int,
    val completedAtMs: Long,
)

/**
 * 睡眠期记忆整合与习惯挖掘引擎（纯 Kotlin 算法核心，供 Android 后台 Worker 与 JVM 测试复用）。
 *
 * 职责：
 * 1. **记忆去重合并**：识别相同/高度近似的活跃记忆，将频次、关键词与强度合并到主条目后淘汰冗余条目；
 * 2. **固定 App 序列习惯挖掘**：从历史成功任务足迹（单任务多 App 链路与短时窗跨任务连续转移）中
 *    挖掘高频 App 序列与时段规律，生成可执行的 Routine 建议；
 * 3. **Markdown 投影刷新**：输出最新 `MEMORY.md` 视图文本。
 */
object SleepConsolidator {
    private const val NEAR_DUP_JACCARD_THRESHOLD = 0.85
    private const val MIN_TERMS_FOR_FUZZY_DEDUP = 3
    private const val CROSS_TASK_SESSION_WINDOW_MS = 30 * 60 * 1000L
    private val PUNCT_OR_SPACE = Regex("[\\s\\p{P}\\p{S}]+")
    private val PACKAGE_LIKE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /**
     * 对 [MemoryStore] 中的冗余记忆执行去重与统计合并，返回被合并删除的条数。
     */
    fun deduplicateMemories(store: MemoryStore): Int {
        if (!store.reloadIfNeeded()) return 0
        val items = store.items.value
        if (items.size < 2) return 0

        // 按 (kind, status) 分组去重，不跨种类也不意外把 pending 混入 active
        val groups = items.groupBy { it.kind to it.status }
        var removedCount = 0

        for ((_, group) in groups) {
            if (group.size < 2) continue
            // 优先保留：置顶 > 累计访问/使用次数 > 强度 > 更新时间
            val ordered = group.sortedWith(
                compareByDescending<MemoryItem> { it.pinned }
                    .thenByDescending { it.accessCount + it.useCount }
                    .thenByDescending { it.strength }
                    .thenByDescending { it.updatedAt }
            )
            val termCache = ordered.associate { it.id to Tokenize.terms(it.content.lowercase()).toSet() }
            val normCache = ordered.associate { it.id to normalizeForDedup(it.content) }
            val absorbed = HashSet<String>()

            for (i in ordered.indices) {
                val winner = ordered[i]
                if (winner.id in absorbed) continue
                var mergedKeywords = winner.keywords.toMutableList()
                var extraAccess = 0
                var extraUse = 0
                var maxStrength = winner.strength
                var mergedPin = winner.pinned
                var changed = false

                for (j in (i + 1) until ordered.size) {
                    val candidate = ordered[j]
                    if (candidate.id in absorbed) continue
                    val isDup = isNearDuplicate(
                        normA = normCache.getValue(winner.id),
                        termsA = termCache.getValue(winner.id),
                        normB = normCache.getValue(candidate.id),
                        termsB = termCache.getValue(candidate.id),
                    )
                    if (!isDup) continue

                    absorbed += candidate.id
                    changed = true
                    extraAccess += candidate.accessCount
                    extraUse += candidate.useCount
                    maxStrength = maxOf(maxStrength, candidate.strength)
                    mergedPin = mergedPin || candidate.pinned
                    candidate.keywords.forEach { kw ->
                        if (kw !in mergedKeywords) mergedKeywords += kw
                    }
                    if (store.forget(candidate.id).isSuccess) {
                        removedCount++
                    }
                }

                if (changed) {
                    store.mergeStats(
                        id = winner.id,
                        mergedKeywords = mergedKeywords,
                        extraAccessCount = extraAccess,
                        extraUseCount = extraUse,
                        minStrength = maxStrength,
                        pinned = mergedPin,
                    )
                }
            }
        }
        return removedCount
    }

    /**
     * 从历史任务记忆中挖掘固定 App 序列习惯与时段 Routine 建议。
     *
     * @param tasks 历史任务列表
     * @param minOccurrences 最小出现次数阈值（默认 2 次）
     * @param nowMs 当前时间戳（毫秒）
     * @param zoneId 时区（用于时段聚类分析）
     */
    @JvmOverloads
    fun mineRoutines(
        tasks: List<TaskMemory>,
        minOccurrences: Int = 2,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<RoutineSuggestion> {
        val threshold = minOccurrences.coerceAtLeast(2)
        val successful = tasks.filter { it.success }.sortedBy { it.timestamp }
        if (successful.size < threshold) return emptyList()

        data class Occurrence(val apps: List<String>, val task: TaskMemory)

        val sequenceHits = LinkedHashMap<List<String>, MutableList<TaskMemory>>()

        // 1. 单次任务内多 App 链路提取（如 淘宝 -> 微信 / 携程 -> 支付宝）
        val perTaskSeqs = successful.map { task ->
            task to extractAppSequence(task)
        }
        for ((task, seq) in perTaskSeqs) {
            if (seq.size >= 2) {
                val maxLen = min(4, seq.size)
                val seenInTask = HashSet<List<String>>()
                for (len in 2..maxLen) {
                    for (start in 0..(seq.size - len)) {
                        val sub = seq.subList(start, start + len).toList()
                        if (seenInTask.add(sub)) {
                            sequenceHits.getOrPut(sub) { mutableListOf() } += task
                        }
                    }
                }
            }
        }

        // 2. 短时窗（30 分钟内）跨任务连续 App 转移挖掘（如 先开钉钉 -> 再开美团）
        for (i in 0 until perTaskSeqs.size - 1) {
            val (t1, s1) = perTaskSeqs[i]
            val (t2, s2) = perTaskSeqs[i + 1]
            if (s1.isEmpty() || s2.isEmpty()) continue
            val gap = t2.timestamp - t1.timestamp
            if (gap in 0..CROSS_TASK_SESSION_WINDOW_MS) {
                val a = s1.last()
                val b = s2.first()
                if (!a.equals(b, ignoreCase = true)) {
                    val pairSeq = listOf(a, b)
                    val list = sequenceHits.getOrPut(pairSeq) { mutableListOf() }
                    if (list.none { it.id == t2.id }) {
                        list += t2
                    }
                }
            }
        }

        // 过滤达标序列并去除被更长同频序列包含的子序列
        val qualified = sequenceHits.entries
            .filter { it.value.size >= threshold }
            .sortedWith(compareByDescending<Map.Entry<List<String>, MutableList<TaskMemory>>> { it.key.size }.thenByDescending { it.value.size })

        val keptSequences = mutableListOf<Pair<List<String>, List<TaskMemory>>>()
        for ((seq, matchedTasks) in qualified) {
            val subsumed = keptSequences.any { (longer, longerTasks) ->
                longer.size > seq.size &&
                    containsSublist(longer, seq) &&
                    longerTasks.size >= matchedTasks.size
            }
            if (!subsumed) {
                keptSequences += seq to matchedTasks
            }
        }

        val suggestions = mutableListOf<RoutineSuggestion>()
        for ((seq, matchedTasks) in keptSequences) {
            val timeWin = dominantTimeWindow(matchedTasks.map { it.timestamp }, zoneId)
            val occ = matchedTasks.size
            val conf = min(0.99, 0.50 + 0.15 * (occ - 1) + if (timeWin != null) 0.10 else 0.0)
            val shortSeq = seq.joinToString(" → ") { shortAppName(it) }
            val id = "rt_" + seq.joinToString("_").hashCode().toUInt().toString(16)
            val title = if (timeWin != null) {
                "${timeWin.substringBefore(' ')}习惯：$shortSeq"
            } else {
                "高频联动：$shortSeq"
            }
            val trigger = if (timeWin != null) {
                "检测到您在 $timeWin 常执行「$shortSeq」（$occ 次），可一键创建自动化 Routine"
            } else {
                "检测到跨应用固定序列「$shortSeq」（已出现 $occ 次），建议保存为快捷 Routine"
            }
            suggestions += RoutineSuggestion(
                id = id,
                title = title,
                appSequence = seq,
                occurrences = occ,
                confidence = conf,
                timeWindow = timeWin,
                suggestedTrigger = trigger,
                sampleGoals = matchedTasks.map { it.goal }.distinct().take(3),
                detectedAtMs = nowMs,
            )
        }

        // 3. 若某单 App 在同一时段反复出现（且未被多 App 序列覆盖），补充单 App 时段习惯
        val coveredApps = suggestions.flatMap { it.appSequence }.toSet()
        perTaskSeqs
            .filter { (_, seq) -> seq.size == 1 && seq.first() !in coveredApps }
            .groupBy { (_, seq) -> seq.first() }
            .forEach { (app, entries) ->
                if (entries.size >= threshold) {
                    val matchedTasks = entries.map { it.first }
                    val timeWin = dominantTimeWindow(matchedTasks.map { it.timestamp }, zoneId)
                    if (timeWin != null) {
                        val occ = matchedTasks.size
                        val conf = min(0.95, 0.45 + 0.15 * (occ - 1))
                        val appLabel = shortAppName(app)
                        suggestions += RoutineSuggestion(
                            id = "rt_single_" + app.hashCode().toUInt().toString(16),
                            title = "${timeWin.substringBefore(' ')}常用：$appLabel",
                            appSequence = listOf(app),
                            occurrences = occ,
                            confidence = conf,
                            timeWindow = timeWin,
                            suggestedTrigger = "检测到您在 $timeWin 高频使用 $appLabel（$occ 次），建议设为时段快捷入口",
                            sampleGoals = matchedTasks.map { it.goal }.distinct().take(3),
                            detectedAtMs = nowMs,
                        )
                    }
                }
            }

        return suggestions.sortedWith(
            compareByDescending<RoutineSuggestion> { it.confidence }
                .thenByDescending { it.occurrences }
        )
    }

    /**
     * 执行完整睡眠期整合：记忆去重 + Routine 习惯挖掘 + `MEMORY.md` 视图生成。
     */
    @JvmOverloads
    fun consolidate(
        memoryStore: MemoryStore,
        taskMemoryStore: TaskMemoryStore?,
        factGraph: TemporalFactGraph? = null,
        minRoutineOccurrences: Int = 2,
        nowMs: Long = memoryStore.clock(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Pair<ConsolidationReport, String> {
        val dedupCount = deduplicateMemories(memoryStore)
        val tasks = taskMemoryStore?.getAll().orEmpty()
        val routines = mineRoutines(tasks, minRoutineOccurrences, nowMs, zoneId)
        val markdown = MarkdownMemoryProjection.render(
            items = memoryStore.items.value,
            facts = factGraph?.allFacts().orEmpty(),
            tasks = tasks,
            routines = routines,
            nowMs = nowMs,
            zoneId = zoneId,
        )
        return ConsolidationReport(
            deduplicatedCount = dedupCount,
            routinesDetected = routines,
            markdownLength = markdown.length,
            completedAtMs = nowMs,
        ) to markdown
    }

    private fun extractAppSequence(task: TaskMemory): List<String> {
        val raw = mutableListOf<String>()
        task.apps.filter { it.isNotBlank() }.forEach { raw += it.trim() }
        if (raw.size < 2) {
            task.keySteps.forEach { step ->
                val note = step.note?.trim()
                if (!note.isNullOrEmpty() && PACKAGE_LIKE.matches(note)) {
                    raw += note
                }
            }
        }
        // 合并相邻重复 App，保留有序转移序列
        val collapsed = mutableListOf<String>()
        for (app in raw) {
            if (collapsed.isEmpty() || !collapsed.last().equals(app, ignoreCase = true)) {
                collapsed += app
            }
        }
        return collapsed
    }

    private fun dominantTimeWindow(timestamps: List<Long>, zoneId: ZoneId): String? {
        if (timestamps.isEmpty()) return null
        val buckets = timestamps.map { ts ->
            val hour = Instant.ofEpochMilli(ts).atZone(zoneId).hour
            when (hour) {
                in 6..9 -> "早间 (06:00-10:00)"
                in 10..13 -> "午间 (10:00-14:00)"
                in 14..17 -> "下午 (14:00-18:00)"
                in 18..22 -> "晚间 (18:00-23:00)"
                else -> "深夜 (23:00-06:00)"
            }
        }
        val top = buckets.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return if (top.value.toDouble() / timestamps.size >= 0.6) top.key else null
    }

    private fun shortAppName(app: String): String {
        val parts = app.split('.').filter { it.isNotBlank() && it !in setOf("com", "cn", "org", "net", "app", "android", "ugc") }
        return parts.lastOrNull()?.replaceFirstChar { it.titlecase(Locale.US) } ?: app
    }

    private fun containsSublist(full: List<String>, sub: List<String>): Boolean {
        if (sub.size > full.size) return false
        for (i in 0..(full.size - sub.size)) {
            if (full.subList(i, i + sub.size) == sub) return true
        }
        return false
    }

    private fun normalizeForDedup(text: String): String =
        PUNCT_OR_SPACE.replace(text.lowercase(), "")

    private fun isNearDuplicate(
        normA: String,
        termsA: Set<String>,
        normB: String,
        termsB: Set<String>,
    ): Boolean {
        if (normA.isNotEmpty() && normA == normB) return true
        if (termsA.size >= MIN_TERMS_FOR_FUZZY_DEDUP && termsB.size >= MIN_TERMS_FOR_FUZZY_DEDUP) {
            val inter = termsA.count { it in termsB }
            val union = termsA.size + termsB.size - inter
            if (union > 0 && inter.toDouble() / union >= NEAR_DUP_JACCARD_THRESHOLD) return true
        }
        return false
    }
}

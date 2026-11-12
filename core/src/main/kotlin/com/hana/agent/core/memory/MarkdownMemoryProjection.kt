package com.hana.agent.core.memory

import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 睡眠期挖掘出的固定 App 序列习惯与自动化 Routine 建议。
 */
@Serializable
data class RoutineSuggestion(
    val id: String,
    val title: String,
    val appSequence: List<String>,
    val occurrences: Int,
    val confidence: Double,
    val timeWindow: String? = null,
    val suggestedTrigger: String,
    val sampleGoals: List<String> = emptyList(),
    val detectedAtMs: Long,
)

/** 从 `MEMORY.md` 解析出的一条可同步记忆行。 */
data class ParsedMemoryLine(
    val id: String?,
    val kind: MemoryKind,
    val content: String,
    val keywords: List<String>,
    val pinned: Boolean = false,
)

/** `MEMORY.md` 双向同步到 [MemoryStore] 的结果统计。 */
data class MarkdownSyncResult(
    val added: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val rejected: Int = 0,
)

/**
 * 记忆与日常足迹的 Markdown 投影视图 (`MEMORY.md`)。
 *
 * 将结构化记忆 ([MemoryItem])、双时态事实图谱 ([TemporalFact])、近期任务足迹 ([TaskMemory])
 * 及习惯建议 ([RoutineSuggestion]) 渲染为人类可读、可编辑的 Markdown 文档，并支持将用户在
 * `MEMORY.md` 中的增删改写回 [MemoryStore]。
 */
object MarkdownMemoryProjection {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    private val META_REGEX = Regex("""<!--\s*hana:([^>]+?)\s*-->\s*$""")
    private val KEYWORDS_REGEX = Regex("""_\(标签:\s*([^)]+)\)_""")
    private val BULLET_REGEX = Regex("""^\s*[-*]\s+(.+)$""")

    fun render(
        items: List<MemoryItem>,
        facts: List<TemporalFact> = emptyList(),
        tasks: List<TaskMemory> = emptyList(),
        routines: List<RoutineSuggestion> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        maxTasks: Int = 20,
    ): String = buildString {
        val stamp = DATETIME_FMT.format(Instant.ofEpochMilli(nowMs).atZone(zoneId))
        appendLine("# Hana Memory (MEMORY.md)")
        appendLine()
        appendLine("> 更新时间: $stamp | 可直接编辑「事实 / 偏好 / 流程规范」段落中的列表项，同步后会写回记忆库。")
        appendLine()

        val sortedItems = items.sortedWith(
            compareByDescending<MemoryItem> { it.pinned }
                .thenByDescending { it.lastAccessedAt ?: it.updatedAt }
        )

        renderMemorySection(this, "## 事实 (Facts)", sortedItems.filter { it.kind == MemoryKind.fact })
        renderMemorySection(this, "## 偏好 (Preferences)", sortedItems.filter { it.kind == MemoryKind.preference })
        renderMemorySection(this, "## 流程规范 (Procedures)", sortedItems.filter { it.kind == MemoryKind.procedure })

        appendLine("## 时态知识图谱 (Temporal Fact Graph)")
        val activeFacts = facts.filter { it.isActive }.sortedByDescending { it.validFrom }
        val historicalFacts = facts.filterNot { it.isActive }.sortedByDescending { it.invalidAt ?: it.validFrom }
        if (activeFacts.isEmpty() && historicalFacts.isEmpty()) {
            appendLine("- _(暂无关系三元组)_")
        } else {
            activeFacts.forEach { f ->
                val since = DATE_FMT.format(Instant.ofEpochMilli(f.validFrom).atZone(zoneId))
                appendLine("- **${f.subject}** —[${f.relation}]→ **${f.objectValue}** _(有效自 $since)_")
            }
            historicalFacts.forEach { f ->
                val invalidDate = f.invalidAt?.let { DATE_FMT.format(Instant.ofEpochMilli(it).atZone(zoneId)) } ?: "-"
                val sup = f.supersededBy?.let { ", 由 `$it` 替代" } ?: ""
                appendLine("- ~~${f.subject} —[${f.relation}]→ ${f.objectValue}~~ _(失效于 $invalidDate$sup)_")
            }
        }
        appendLine()

        appendLine("## 日常足迹 (Recent Task Footprints)")
        val recentTasks = tasks.sortedByDescending { it.timestamp }.take(maxTasks.coerceAtLeast(0))
        if (recentTasks.isEmpty()) {
            appendLine("- _(暂无任务执行足迹)_")
        } else {
            recentTasks.forEach { t ->
                val dt = DATETIME_FMT.format(Instant.ofEpochMilli(t.timestamp).atZone(zoneId))
                val mark = if (t.success) "✅" else "⚠️"
                val appsStr = t.apps.joinToString(" → ").ifEmpty { "系统" }
                appendLine("- [$dt] $mark **${t.goal}** → ${t.conclusion} _($appsStr, ${t.keySteps.size} 步)_")
            }
        }
        appendLine()

        appendLine("## 习惯与 Routine 建议 (Routine Suggestions)")
        if (routines.isEmpty()) {
            appendLine("- _(暂无自动化习惯建议)_")
        } else {
            routines.sortedByDescending { it.confidence }.forEach { r ->
                val seq = r.appSequence.joinToString(" → ")
                val pct = String.format(Locale.US, "%.0f%%", r.confidence * 100.0)
                val win = r.timeWindow?.let { " [$it]" } ?: ""
                appendLine("- **${r.title}** (`$seq`, ${r.occurrences} 次, 置信度 $pct)$win — ${r.suggestedTrigger}")
            }
        }
    }.trimEnd() + "\n"

    private fun renderMemorySection(sb: StringBuilder, heading: String, list: List<MemoryItem>) {
        sb.appendLine(heading)
        if (list.isEmpty()) {
            sb.appendLine("- _(暂无)_")
        } else {
            list.forEach { item ->
                val pinPrefix = if (item.pinned) "[置顶] " else ""
                val kwSuffix = if (item.keywords.isNotEmpty()) " _(标签: ${item.keywords.joinToString(", ")})_" else ""
                val pendingSuffix = if (item.status == MemoryStatus.pending) " [待批准]" else ""
                val strengthStr = String.format(Locale.US, "%.2f", item.strength)
                val meta = "<!-- hana:id=${item.id};kind=${item.kind.name};pinned=${item.pinned};status=${item.status.name};strength=$strengthStr -->"
                sb.appendLine("- $pinPrefix${item.content}$kwSuffix$pendingSuffix $meta")
            }
        }
        sb.appendLine()
    }

    /** 解析人类编辑后的 `MEMORY.md`，提取其中的事实/偏好/流程条目（包含新手工新增行）。 */
    fun parse(markdown: String): List<ParsedMemoryLine> {
        val out = mutableListOf<ParsedMemoryLine>()
        var currentKind: MemoryKind? = null

        for (rawLine in markdown.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("## ")) {
                currentKind = when {
                    line.contains("事实") || line.contains("Facts", ignoreCase = true) -> MemoryKind.fact
                    line.contains("偏好") || line.contains("Preferences", ignoreCase = true) -> MemoryKind.preference
                    line.contains("流程") || line.contains("Procedures", ignoreCase = true) -> MemoryKind.procedure
                    else -> null
                }
                continue
            }
            val sectionKind = currentKind ?: continue
            val bulletMatch = BULLET_REGEX.matchEntire(line) ?: continue
            var body = bulletMatch.groupValues[1].trim()
            if (body == "_(暂无)_" || body.startsWith("_(") && body.endsWith(")_")) continue

            var id: String? = null
            var kind = sectionKind
            var pinned = false

            META_REGEX.find(body)?.let { metaMatch ->
                body = body.removeRange(metaMatch.range).trim()
                val attrs = parseMeta(metaMatch.groupValues[1])
                id = attrs["id"]?.takeIf { it.isNotBlank() }
                attrs["kind"]?.let { k -> MemoryKind.entries.find { it.name == k }?.let { kind = it } }
                pinned = attrs["pinned"]?.toBooleanStrictOrNull() ?: false
            }

            if (body.startsWith("[置顶]")) {
                pinned = true
                body = body.removePrefix("[置顶]").trim()
            }
            if (body.endsWith("[待批准]")) {
                body = body.removeSuffix("[待批准]").trim()
            }

            var keywords = emptyList<String>()
            KEYWORDS_REGEX.find(body)?.let { kwMatch ->
                keywords = kwMatch.groupValues[1]
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                body = body.removeRange(kwMatch.range).trim()
            }

            if (body.isNotBlank()) {
                out += ParsedMemoryLine(
                    id = id,
                    kind = kind,
                    content = body,
                    keywords = keywords,
                    pinned = pinned,
                )
            }
        }
        return out
    }

    /**
     * 将用户在 `MEMORY.md` 中的手动编辑同步回 [MemoryStore]。
     *
     * @param deleteMissing 为 true 时，在原存储中存在但已从 `MEMORY.md` 删除的条目将被软遗忘（forget）。
     */
    @JvmOverloads
    fun syncEditsToStore(
        markdown: String,
        store: MemoryStore,
        deleteMissing: Boolean = false,
    ): MarkdownSyncResult {
        if (!store.reloadIfNeeded()) return MarkdownSyncResult()
        val parsed = parse(markdown)
        val existingById = store.items.value.associateBy { it.id }
        val now = store.clock()
        val manualSource = MemorySource(type = "manual", atMs = now, channel = "memory_md")

        var added = 0
        var updated = 0
        var deleted = 0
        var rejected = 0
        val seenIds = HashSet<String>()

        for (entry in parsed) {
            val existing = entry.id?.let { existingById[it] }
            if (existing != null) {
                seenIds += existing.id
                val contentChanged = entry.content != existing.content
                val keywordsChanged = entry.keywords != existing.keywords
                if (contentChanged || keywordsChanged) {
                    val res = store.update(existing.id, entry.content, entry.keywords, manualSource)
                    if (res.isSuccess) updated++ else rejected++
                }
                if (entry.pinned != existing.pinned) {
                    store.setPinned(existing.id, entry.pinned)
                }
            } else {
                // 避免与已有内容完全相同的手工条目重复新增
                val dup = store.items.value.firstOrNull {
                    it.kind == entry.kind && it.content.trim() == entry.content.trim()
                }
                if (dup != null) {
                    seenIds += dup.id
                } else {
                    val res = store.add(entry.kind, entry.content, entry.keywords, manualSource)
                    if (res.isSuccess) {
                        added++
                        val newId = res.getOrThrow().item.id
                        seenIds += newId
                        if (entry.pinned) store.setPinned(newId, true)
                    } else {
                        rejected++
                    }
                }
            }
        }

        if (deleteMissing) {
            for (item in existingById.values) {
                if (item.id !in seenIds) {
                    if (store.forget(item.id).isSuccess) deleted++
                }
            }
        }

        return MarkdownSyncResult(added = added, updated = updated, deleted = deleted, rejected = rejected)
    }

    private fun parseMeta(raw: String): Map<String, String> =
        raw.split(';')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null
                else pair.substring(0, idx).trim() to pair.substring(idx + 1).trim()
            }
            .toMap()
}

/** `MEMORY.md` 文件原子读写包装器。 */
class MarkdownMemoryFile(val file: File) {
    @Synchronized
    fun write(markdown: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(markdown, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(markdown, Charsets.UTF_8)
            tmp.delete()
        }
    }

    @Synchronized
    fun readOrNull(): String? =
        if (file.exists() && file.length() > 0L) file.readText(Charsets.UTF_8) else null

    @Synchronized
    fun delete() {
        if (file.exists()) file.delete()
    }
}

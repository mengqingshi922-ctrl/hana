package com.hana.agent.core.memory

import com.hana.agent.core.observation.PlaceKind
import com.hana.agent.core.record.RunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.UUID

/**
 * 任务记忆录制器。
 * 在单次任务完成时（Done / Succeeded），自动从任务执行事件流及最终答复中提取：
 * 涉及的 App、网站域名、关键操作步骤序列及业务结论，并自动沉淀写入 [TaskMemoryStore]。
 */
class TaskMemoryRecorder(private val store: TaskMemoryStore) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 在单次任务完成时（Done）提取经验并写入持久化存储。
     *
     * @param goal 任务目标/用户原话
     * @param conclusion 最终执行结论或给用户的答复
     * @param events 任务运行期间记录的完整事件流
     * @param conversationId 对话 ID（可选）
     * @param timestamp 记录时间戳（毫秒）
     * @param success 是否完成成功，默认为 true
     * @return 提取并保存的 [TaskMemory]，若发生不可恢复异常则返回 null
     */
    fun recordTaskDone(
        goal: String,
        conclusion: String,
        events: List<RunEvent>,
        conversationId: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        success: Boolean = true,
    ): TaskMemory? {
        if (goal.isBlank() && conclusion.isBlank()) return null

        return try {
            val apps = extractApps(events)
            val domains = extractDomains(events)
            val keySteps = extractKeySteps(events)

            val memory = TaskMemory(
                id = "tm_" + UUID.randomUUID().toString().replace("-", "").take(16),
                goal = goal.trim(),
                conclusion = conclusion.trim(),
                apps = apps,
                domains = domains,
                keySteps = keySteps,
                timestamp = timestamp,
                conversationId = conversationId,
                success = success,
            )

            store.add(memory)
            memory
        } catch (e: Exception) {
            null
        }
    }

    /** 从事件流中提取涉及的 App 名称或包名。 */
    internal fun extractApps(events: List<RunEvent>): List<String> {
        val apps = linkedSetOf<String>()

        events.forEach { e ->
            when (e) {
                is RunEvent.ActionTarget -> {
                    e.before.packageName?.let { if (isAppIdentifier(it)) apps += it }
                    e.after.packageName?.let { if (isAppIdentifier(it)) apps += it }
                    extractAppFromArgs(e.argsJson)?.let { apps += it }
                }
                is RunEvent.Media -> {
                    e.place?.let { if (isAppIdentifier(it)) apps += it }
                }
                is RunEvent.Observed -> {
                    val p = e.place
                    if (p != null) {
                        if (p.kind == PlaceKind.App && isAppIdentifier(p.name)) {
                            apps += p.name
                        }
                    }
                }
                is RunEvent.ToolResult -> {
                    if (e.tool.contains("launch_app", ignoreCase = true) || e.tool.contains("app", ignoreCase = true)) {
                        extractAppFromArgs(e.resultJson)?.let { apps += it }
                    }
                }
                else -> {}
            }
        }
        return apps.toList()
    }

    /** 从事件流中提取涉及的网站域名。 */
    internal fun extractDomains(events: List<RunEvent>): List<String> {
        val domains = linkedSetOf<String>()

        events.forEach { e ->
            when (e) {
                is RunEvent.ActionTarget -> {
                    extractHostFromArgs(e.argsJson)?.let { domains += it }
                }
                is RunEvent.Media -> {
                    e.place?.let { extractHost(it)?.let { h -> domains += h } }
                }
                is RunEvent.Observed -> {
                    val p = e.place
                    if (p != null) {
                        if (p.kind == PlaceKind.Site) {
                            domains += p.name
                        } else {
                            extractHost(p.name)?.let { domains += it }
                        }
                    }
                }
                is RunEvent.ToolResult -> {
                    if (e.tool.contains("web", ignoreCase = true) || e.tool.contains("link", ignoreCase = true)) {
                        extractHostFromArgs(e.resultJson)?.let { domains += it }
                    }
                }
                else -> {}
            }
        }
        return domains.toList()
    }

    /** 从事件流中提炼出关键操作步骤序列（过滤只读探测与无变动重试）。 */
    internal fun extractKeySteps(events: List<RunEvent>): List<TaskMemoryStep> {
        val steps = mutableListOf<TaskMemoryStep>()
        var stepIndex = 1

        val actionTargets = events.filterIsInstance<RunEvent.ActionTarget>()
        if (actionTargets.isNotEmpty()) {
            actionTargets.filter { it.effective }.forEach { at ->
                val actionDesc = describeAction(at.tool, at.argsJson, at.target?.name)
                steps += TaskMemoryStep(
                    step = stepIndex++,
                    tool = at.tool,
                    action = actionDesc,
                    note = at.before.packageName ?: at.after.packageName,
                )
            }
        } else {
            // 没有 ActionTarget 时回退从 ToolResult 提取关键写动作
            events.filterIsInstance<RunEvent.ToolResult>()
                .filter { isMeaningfulActionTool(it.tool) && it.changed != false }
                .take(MAX_STEPS)
                .forEach { tr ->
                    val actionDesc = describeToolResult(tr.tool, tr.resultJson)
                    steps += TaskMemoryStep(
                        step = stepIndex++,
                        tool = tr.tool,
                        action = actionDesc,
                    )
                }
        }

        return steps.take(MAX_STEPS)
    }

    private fun isAppIdentifier(name: String): Boolean {
        if (name.startsWith("网站 ")) return false
        // Android 包名通常包含点，如 com.taobao.taobao，或纯字母应用名
        return name.contains('.') || (!name.contains('/') && !name.contains(':'))
    }

    private fun extractAppFromArgs(argsJson: String?): String? {
        if (argsJson.isNullOrBlank()) return null
        return try {
            val obj = json.parseToJsonElement(argsJson) as? JsonObject ?: return null
            (obj["package"] ?: obj["packageName"] ?: obj["app"])?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun extractHost(raw: String): String? {
        val clean = if (raw.startsWith("网站 ")) raw.removePrefix("网站 ").trim() else raw.trim()
        return try {
            if (clean.startsWith("http://") || clean.startsWith("https://")) {
                URI(clean).host ?: clean
            } else if (clean.contains(".") && !clean.contains(" ") && !clean.startsWith("com.")) {
                clean
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractHostFromArgs(jsonStr: String?): String? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            val obj = json.parseToJsonElement(jsonStr) as? JsonObject ?: return null
            val url = (obj["url"] ?: obj["link"])?.jsonPrimitive?.contentOrNull
            url?.let { extractHost(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun describeAction(tool: String, argsJson: String, targetName: String?): String {
        val parsed = try {
            json.parseToJsonElement(argsJson) as? JsonObject
        } catch (_: Exception) {
            null
        }

        val text = parsed?.get("text")?.jsonPrimitive?.contentOrNull
        return when {
            tool.contains("tap") -> if (!targetName.isNullOrBlank()) "点击 [$targetName]" else "点击屏幕元素"
            tool.contains("type") -> if (text != null && !targetName.isNullOrBlank()) "在 [$targetName] 输入 \"$text\"" else if (text != null) "输入 \"$text\"" else "输入文本"
            tool.contains("launch") -> "启动应用 ${parsed?.get("package")?.jsonPrimitive?.contentOrNull ?: ""}".trim()
            tool.contains("navigate") -> "访问网址 ${parsed?.get("url")?.jsonPrimitive?.contentOrNull ?: ""}".trim()
            tool.contains("scroll") -> "滑动屏幕"
            tool.contains("key") -> "按键 ${parsed?.get("key")?.jsonPrimitive?.contentOrNull ?: ""}".trim()
            else -> if (!targetName.isNullOrBlank()) "$tool -> $targetName" else tool
        }
    }

    private fun describeToolResult(tool: String, resultJson: String): String {
        return "执行 $tool"
    }

    private fun isMeaningfulActionTool(tool: String): Boolean {
        val lower = tool.lowercase()
        return lower.contains("tap") || lower.contains("type") || lower.contains("launch") ||
            lower.contains("navigate") || lower.contains("scroll") || lower.contains("key") ||
            lower.contains("click")
    }

    companion object {
        private const val MAX_STEPS = 30
    }
}

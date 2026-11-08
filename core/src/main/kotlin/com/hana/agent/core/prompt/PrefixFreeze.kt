package com.hana.agent.core.prompt

import com.hana.agent.core.model.Author
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import kotlinx.serialization.Serializable

/**
 * 静态前缀块：严格字节级冻结，严禁包含时间戳、电量、网络、临时会话 ID 等易变状态。
 */
@Serializable
data class StaticPrefixBlock(
    val systemPrompt: String,
    val initialGoal: String? = null,
    val toolSchemaJson: String? = null,
    val fewShotExamples: List<String> = emptyList(),
) {
    init {
        validateNoDynamicLeaks(systemPrompt)
        initialGoal?.let { validateNoDynamicLeaks(it) }
        toolSchemaJson?.let { validateNoDynamicLeaks(it) }
        fewShotExamples.forEach { validateNoDynamicLeaks(it) }
    }

    companion object {
        private val DYNAMIC_LEAK_PATTERNS = listOf(
            Regex("""\b\d{4}[-/]\d{2}[-/]\d{2}"""), // 日期如 2026-11-08（含 ISO-8601 前缀）
            Regex("""(?i)\d{4}-\d{2}-\d{2}[T\s]\d{2}:\d{2}(:\d{2})?"""), // ISO-8601 完整时间戳
            Regex("""\b\d{2}:\d{2}(:\d{2})?\b"""), // 时间如 14:15:00
            Regex("""(?i)["']?battery["']?[:\s=]+\d+%?"""), // 电量（支持 JSON 键）
            Regex("""(?i)["']?timestamp["']?[:\s=]+["']?\d+"""), // 动态时间戳（支持 JSON 键与值）
            Regex("""(?i)["']?session[_-]?id["']?[:\s=]+["']?[a-f0-9-]+"""), // 临时会话 ID（支持 JSON 键与值）
        )

        fun validateNoDynamicLeaks(content: String) {
            for (pattern in DYNAMIC_LEAK_PATTERNS) {
                require(!pattern.containsMatchIn(content)) {
                    "StaticPrefixBlock validation failed: dynamic variable pattern '${pattern.pattern}' detected in static prefix!"
                }
            }
        }
    }
}

/**
 * 动态后置后缀块：将易变运行时状态封装并严格置于末尾 User 消息。
 */
@Serializable
data class DynamicSuffixBlock(
    val nowMs: Long,
    val localTimeStr: String,
    val currentPlace: String? = null,
    val batteryLevel: Int? = null,
    val networkType: String? = null,
    val screenDiff: String? = null,
    val extraRuntimeState: Map<String, String> = emptyMap(),
) {
    fun toUserMessagePartText(): Part.Text {
        val lines = mutableListOf<String>()
        lines += "[Runtime Dynamic Suffix]"
        lines += "Local time: $localTimeStr"
        lines += "Timestamp: $nowMs"
        currentPlace?.let { lines += "Current place: $it" }
        batteryLevel?.let { lines += "Battery: $it%" }
        networkType?.let { lines += "Network: $it" }
        screenDiff?.let { lines += "Screen diff: $it" }
        extraRuntimeState.forEach { (k, v) -> lines += "$k: $v" }
        return Part.Text(lines.joinToString("\n"))
    }

    fun attachToLastUserMessage(history: List<Message>): List<Message> {
        val part = toUserMessagePartText()
        if (history.isEmpty()) {
            return listOf(Message(Role.User, listOf(part), Author.Mika))
        }
        val mutable = history.toMutableList()
        val lastIdx = mutable.lastIndex
        val last = mutable[lastIdx]
        if (last.role == Role.User) {
            mutable[lastIdx] = last.copy(parts = last.parts + part)
        } else {
            mutable.add(Message(Role.User, listOf(part), Author.Mika))
        }
        return mutable
    }
}

/**
 * 前缀冻结验证工具
 */
object PrefixFreezeVerifier {
    /**
     * 校验从第 1 轮到第 N 轮，静态前缀是否达到 100% 字节级一致
     */
    fun verifyPrefixStability(turnPrefixes: List<String>): Boolean {
        if (turnPrefixes.size <= 1) return true
        val baseline = turnPrefixes.first()
        return turnPrefixes.all { it == baseline }
    }
}

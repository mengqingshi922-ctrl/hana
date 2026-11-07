package com.hana.agent.core.agent

/**
 * Google ARTEMIS / Flash Profile 风格的应用极速直达快轨 (App Fast-Path):
 * 对于用户发出的明确且高频的打开 App 指令，无需等待大模型阅读数十个工具说明并思考 CoT，
 * 直接在本地以毫秒级识别并立即触发启动。
 */
internal object AppFastPath {
    private val PREFIXES = listOf(
        "帮我打开", "请帮我打开", "打开", "帮我启动", "请帮我启动", "启动", "运行", "进一下", "进入", "去", "帮我进入", "launch ", "open "
    )

    data class FastMatch(val packageName: String, val appLabel: String, val isPureOpen: Boolean)

    /**
     * 尝试将用户任务匹配到本地已安装的 App:
     * [installed] 为 (包名 to 显示名称) 列表。
     */
    fun match(task: String, installed: List<Pair<String, String>>): FastMatch? {
        val clean = task.trim()
        if (clean.isEmpty() || installed.isEmpty()) return null

        val matchedPrefix = PREFIXES.firstOrNull { clean.startsWith(it, ignoreCase = true) } ?: return null
        val rest = clean.substring(matchedPrefix.length).trim()
        if (rest.isEmpty()) return null

        // 按显示名称长度从长到短排序，优先匹配长词（例如“网易云音乐”优于“音乐”）
        for ((pkg, label) in installed.sortedByDescending { it.second.length }) {
            if (label.length < 2) continue
            val labelLower = label.lowercase()
            val restLower = rest.lowercase()

            if (restLower.startsWith(labelLower)) {
                val after = restLower.substring(labelLower.length).trimStart(' ', ',', '，', '。', '、', '的', '！', '!', '吧')
                val isPure = after.isEmpty() || after == "app" || after == "应用" || after == "软件"
                return FastMatch(pkg, label, isPure)
            }
        }
        return null
    }
}

package com.hana.agent.core.knowledge

import java.util.concurrent.ConcurrentHashMap

/**
 * 单条结构化应用避坑经验条目。
 */
data class AppTipEntry(
    val packageName: String,
    val tip: String,
    val failedAction: String? = null,
    val recoveryAction: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Mobile-Agent-E 风格应用踩坑经验自进化库 (App Pitfall & Tips Evolution Store)
 *
 * 借鉴自阿里巴巴 Mobile-Agent-E (Self-Evolving Mobile Assistant) 的 Tips Evolution 机制：
 * 当 Agent 在某款应用中遇到阻碍 (如 StuckTracker 告警、看门狗停滞/震荡)，并在随后自主摸索脱困成功后，
 * 系统自动将“受阻动作/目标 + 有效脱困动作”提炼为结构化避坑经验 (App Tip) 沉淀入库，并支持结构化去重与最新 N 条淘汰保留。
 * 下次再次访问该 App 时，自动将避坑指南随运行时状态注入给主模型，防止“在同一个坑里跌倒两次”。
 */
object AppTipsStore {
    const val MAX_TIPS_PER_APP = 5
    private const val MAX_TIP_LENGTH = 160
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val tipsByPackage = ConcurrentHashMap<String, MutableList<StoredTip>>()

    private data class StoredTip(
        val dedupKey: String,
        val entry: AppTipEntry,
    )

    init {
        // 内置高频复杂应用的初始专家避坑指南 (Cold-Start Experience)
        recordTip("com.xingin.xhs", "搜索结果页筛选前需先留意并关闭底部的登录/引导横幅")
        recordTip("com.tencent.mm", "公众号主页与聊天窗内操作注意右上角更多菜单的隐藏入口")
        recordTip("com.taobao.taobao", "大促活动期间首屏弹窗较多，若点击未响应可优先检查透明阻挡浮层")
    }

    /**
     * 沉淀一条特定 App 的避坑经验（按归一化文本去重并刷新至最新位置，最多保留最新 [MAX_TIPS_PER_APP] 条）。
     */
    fun recordTip(packageName: String?, tip: String) {
        if (packageName.isNullOrBlank() || tip.isBlank()) return
        val normalizedTip = normalizeDisplayTip(tip)
        if (normalizedTip.isEmpty()) return
        val key = normalizeKey(normalizedTip)
        upsert(
            packageName = packageName.trim(),
            dedupKey = key,
            entry = AppTipEntry(
                packageName = packageName.trim(),
                tip = normalizedTip,
            ),
        )
    }

    /**
     * 沉淀结构化避坑经验：记录受阻动作/目标与成功脱困动作，按 `(failedAction -> recoveryAction)` 结构化去重。
     */
    fun recordStructuredTip(
        packageName: String?,
        failedAction: String?,
        recoveryAction: String?,
        customTip: String? = null,
    ): String? {
        if (packageName.isNullOrBlank()) return null
        val failedClean = failedAction?.trim()?.take(48).orEmpty()
        val recoveryClean = recoveryAction?.trim()?.take(48).orEmpty()
        if (failedClean.isEmpty() && recoveryClean.isEmpty() && customTip.isNullOrBlank()) return null

        val rendered = customTip?.takeIf { it.isNotBlank() } ?: buildString {
            append("在当前页面执行[")
            append(failedClean.ifEmpty { "先前操作" })
            append("]受阻后，改用[")
            append(recoveryClean.ifEmpty { "替代操作" })
            append("]可恢复推进")
        }
        val normalizedTip = normalizeDisplayTip(rendered)
        if (normalizedTip.isEmpty()) return null

        val dedupKey = if (failedClean.isNotEmpty() && recoveryClean.isNotEmpty()) {
            "act:${normalizeKey(failedClean)}->${normalizeKey(recoveryClean)}"
        } else {
            normalizeKey(normalizedTip)
        }
        upsert(
            packageName = packageName.trim(),
            dedupKey = dedupKey,
            entry = AppTipEntry(
                packageName = packageName.trim(),
                tip = normalizedTip,
                failedAction = failedClean.ifEmpty { null },
                recoveryAction = recoveryClean.ifEmpty { null },
            ),
        )
        return normalizedTip
    }

    private fun upsert(packageName: String, dedupKey: String, entry: AppTipEntry) {
        val list = tipsByPackage.computeIfAbsent(packageName) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it.dedupKey == dedupKey || normalizeKey(it.entry.tip) == normalizeKey(entry.tip) }
            list.add(0, StoredTip(dedupKey, entry))
            while (list.size > MAX_TIPS_PER_APP) {
                list.removeAt(list.lastIndex)
            }
        }
    }

    /**
     * 获取指定 App 的避坑指南列表（按最新优先顺序返回，最多 [MAX_TIPS_PER_APP] 条）。
     */
    fun getTips(packageName: String?): List<String> {
        if (packageName.isNullOrBlank()) return emptyList()
        val list = tipsByPackage[packageName] ?: return emptyList()
        return synchronized(list) { list.map { it.entry.tip } }
    }

    /**
     * 获取指定 App 的结构化避坑指南条目（按最新优先顺序返回）。
     */
    fun getStructuredTips(packageName: String?): List<AppTipEntry> {
        if (packageName.isNullOrBlank()) return emptyList()
        val list = tipsByPackage[packageName] ?: return emptyList()
        return synchronized(list) { list.map { it.entry } }
    }

    /**
     * 清理（供测试使用）
     */
    fun clear() {
        tipsByPackage.clear()
    }

    private fun normalizeDisplayTip(tip: String): String =
        WHITESPACE_REGEX.replace(tip.trim(), " ").take(MAX_TIP_LENGTH)

    private fun normalizeKey(text: String): String =
        WHITESPACE_REGEX.replace(text.trim().lowercase(), " ")
}

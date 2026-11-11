package com.hana.agent.core.knowledge

import java.util.concurrent.ConcurrentHashMap

/**
 * Mobile-Agent-E 风格应用踩坑经验自进化库 (App Pitfall & Tips Evolution Store)
 *
 * 借鉴自阿里巴巴 Mobile-Agent-E (Self-Evolving Mobile Assistant) 的 Tips Evolution 机制：
 * 当 Agent 在某款应用中遇到阻碍 (如 StuckTracker 告警、连续同位置无效果点击)，并在随后自主摸索脱困成功后，
 * 系统自动将该踩坑反思提炼为单行避坑经验 (App Tip) 沉淀入库。
 * 下次再次访问该 App 时，自动将避坑指南随运行时状态注入给主模型，防止“在同一个坑里跌倒两次”。
 */
object AppTipsStore {
    private const val MAX_TIPS_PER_APP = 5
    private val tipsByPackage = ConcurrentHashMap<String, MutableList<String>>()

    init {
        // 内置高频复杂应用的初始专家避坑指南 (Cold-Start Experience)
        recordTip("com.xingin.xhs", "搜索结果页筛选前需先留意并关闭底部的登录/引导横幅")
        recordTip("com.tencent.mm", "公众号主页与聊天窗内操作注意右上角更多菜单的隐藏入口")
        recordTip("com.taobao.taobao", "大促活动期间首屏弹窗较多，若点击未响应可优先检查透明阻挡浮层")
    }

    /**
     * 沉淀一条特定 App 的避坑经验
     */
    fun recordTip(packageName: String?, tip: String) {
        if (packageName.isNullOrBlank() || tip.isBlank()) return
        val list = tipsByPackage.computeIfAbsent(packageName) { mutableListOf() }
        synchronized(list) {
            if (!list.contains(tip)) {
                if (list.size >= MAX_TIPS_PER_APP) {
                    list.removeAt(0)
                }
                list.add(tip.take(120))
            }
        }
    }

    /**
     * 获取指定 App 的避坑指南列表
     */
    fun getTips(packageName: String?): List<String> {
        if (packageName.isNullOrBlank()) return emptyList()
        val list = tipsByPackage[packageName] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    /**
     * 清理（供测试使用）
     */
    fun clear() {
        tipsByPackage.clear()
    }
}

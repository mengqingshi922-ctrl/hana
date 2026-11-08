package com.hana.agent.core.edge

import com.hana.agent.core.agent.IntentGate

/**
 * 端侧毫秒级非自回归决策与槽位提取端口（由 :app 层 MNN-Pocket JNI 实现；未安装模型时默认 NoOp 回退）。
 */
interface EdgeDecisionPort {
    /** 端侧引擎当前是否就绪可用（已下载且内存水位安全）。 */
    val isReady: Boolean

    /**
     * 1. 单步非自回归意图分类（MNN-Pocket-0.3B-Dec，耗时 ~12ms）。
     * @return 若置信度 >= threshold (默认 0.85) 返回判定结果，否则返回 null 交由原有链路处理。
     */
    suspend fun classifyIntent(
        task: String,
        recentContextSummary: String = "",
        confidenceThreshold: Float = 0.85f,
    ): IntentGate.Intent?

    /**
     * 2. 浮层阻断弹窗按钮指针消解（MNN-Pocket-0.3B-Dec Pointer Head，耗时 ~15ms）。
     * 仅在候选元素 <= 18 的疑似弹窗中调用，从 candidates 中选出代表“关闭/跳过/暂不/拒绝”的按钮 ref。
     * 若所有候选均为正常业务按钮，必须返回 null。
     */
    suspend fun pickObstacleDismissRef(
        userGoal: String,
        dialogClues: String,
        candidates: List<EdgeCandidate>,
        confidenceThreshold: Float = 0.85f,
    ): String?

    /**
     * 3. 经验回放（Replayer）跨版本 UI 控件语义对齐（耗时 ~15ms）。
     */
    suspend fun matchReplayCandidate(
        stepGoal: String,
        recordedLabel: String,
        candidates: List<EdgeCandidate>,
        confidenceThreshold: Float = 0.88f,
    ): String?

    /**
     * 4. 口语化应用启动与搜索槽位一步提取（耗时 ~25ms）。
     * 例如：“去红薯搜一下秋冬穿搭” -> ResolvedAppAction(packageName="com.xingin.xhs", appLabel="小红书", searchQuery="秋冬穿搭")
     */
    suspend fun resolveAppFastAction(
        task: String,
        installedApps: List<Pair<String, String>>,
    ): ResolvedAppAction?

    data class EdgeCandidate(
        val ref: String,
        val role: String,
        val label: String,
        val bounds: IntArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EdgeCandidate) return false
            if (ref != other.ref || role != other.role || label != other.label) return false
            if (bounds != null) {
                if (other.bounds == null || !bounds.contentEquals(other.bounds)) return false
            } else if (other.bounds != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = ref.hashCode()
            result = 31 * result + role.hashCode()
            result = 31 * result + label.hashCode()
            result = 31 * result + (bounds?.contentHashCode() ?: 0)
            return result
        }
    }

    data class ResolvedAppAction(
        val packageName: String,
        val appLabel: String,
        val searchQuery: String? = null,
        val isPureOpen: Boolean = searchQuery.isNullOrBlank(),
    )

    companion object {
        val NONE = object : EdgeDecisionPort {
            override val isReady: Boolean = false
            override suspend fun classifyIntent(task: String, recentContextSummary: String, confidenceThreshold: Float) = null
            override suspend fun pickObstacleDismissRef(userGoal: String, dialogClues: String, candidates: List<EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun matchReplayCandidate(stepGoal: String, recordedLabel: String, candidates: List<EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun resolveAppFastAction(task: String, installedApps: List<Pair<String, String>>) = null
        }
    }
}

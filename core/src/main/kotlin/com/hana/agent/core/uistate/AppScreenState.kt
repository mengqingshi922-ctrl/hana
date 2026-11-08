package com.hana.agent.core.uistate

/**
 * 单个应用前台界面的会话快照与拓扑状态
 */
data class AppScreenState(
    val packageName: String,
    val lastSnapshot: ScreenSnapshot?,
    val refStabilizerState: RefStabilizerState?,
    val pageSignature: Long,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * 稳定元素编号器状态快照（跨应用切换时冻结与恢复）
 */
data class RefStabilizerState(
    val count: Int,
    val born: Map<String, Int>,
    val latest: ModelSnapshot?,
    val centers: Map<Int, Map<String, Pair<Int, Int>>>,
    val nextRef: Int = 1,
)

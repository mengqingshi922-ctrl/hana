package com.hana.agent.core.route

import com.hana.agent.core.action.MacroStep
import com.hana.agent.core.replay.Anchors
import kotlinx.serialization.Serializable

@Serializable
data class RouteGraphNode(
    val nodeId: String,
    val pageSignature: String,
    val anchors: Anchors? = null,
    val isTerminal: Boolean = false,
)

@Serializable
data class RouteGraphEdge(
    val edgeId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val action: MacroStep,
    val isRecoveryBranch: Boolean = false, // 是否为异常/广告弹窗自愈边
    val weight: Float = 1.0f,
)

/**
 * 有向经验拓扑图谱（RouteGraph）：
 * 将原有的单向线性经验路线升维为包含主路径、广告弹窗自愈容错边与异常分支的有向图结构。
 */
@Serializable
data class RouteGraph(
    val graphId: String,
    val intentKeyword: String,
    val initialNodeId: String,
    val nodes: Map<String, RouteGraphNode>,
    val edges: List<RouteGraphEdge>,
    val successCount: Int = 1,
) {
    fun findNextStep(currentNodeId: String, preferRecovery: Boolean = false): RouteGraphEdge? {
        val candidates = edges.filter { it.fromNodeId == currentNodeId }
        return if (preferRecovery) {
            candidates.firstOrNull { it.isRecoveryBranch } ?: candidates.firstOrNull()
        } else {
            candidates.firstOrNull { !it.isRecoveryBranch } ?: candidates.firstOrNull()
        }
    }

    fun hasRecoveryBranch(currentNodeId: String): Boolean =
        edges.any { it.fromNodeId == currentNodeId && it.isRecoveryBranch }

    fun findNormalEdge(fromNodeId: String): RouteGraphEdge? =
        edges.firstOrNull { it.fromNodeId == fromNodeId && !it.isRecoveryBranch }

    fun findRecoveryEdge(fromNodeId: String): RouteGraphEdge? =
        edges.firstOrNull { it.fromNodeId == fromNodeId && it.isRecoveryBranch }

    /**
     * 判断动作是否为不可逆高危物理外部副作用（支付、删除、转账）
     */
    fun isIrreversibleAction(action: MacroStep): Boolean {
        val tool = action.tool.lowercase()
        val argsStr = action.args.toString().lowercase()
        return tool.contains("pay") || tool.contains("delete") || tool.contains("transfer") ||
            argsStr.contains("pay") || argsStr.contains("delete") || argsStr.contains("confirm_order")
    }
}

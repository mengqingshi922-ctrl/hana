package com.hana.agent.core.route

import com.hana.agent.core.action.ChainPredicateKind
import com.hana.agent.core.action.MacroStep
import com.hana.agent.core.bus.RegisterBus
import com.hana.agent.core.subgoal.SubgoalCheckpoint
import com.hana.agent.core.subgoal.SubgoalCheckpointManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RouteGraphCheckpointTest {

    @Before
    fun setUp() {
        RegisterBus.clear()
    }

    @After
    fun tearDown() {
        RegisterBus.clear()
    }

    @Test
    fun testDirectedGraphBranchingForObstacles() {
        val nodeMain = RouteGraphNode(nodeId = "n_splash", pageSignature = "sig_splash_ad")
        val nodeAdDismiss = RouteGraphNode(nodeId = "n_ad_dismiss", pageSignature = "sig_ad_close")
        val nodeHome = RouteGraphNode(nodeId = "n_home", pageSignature = "sig_home", isTerminal = true)

        val nodes = mapOf(
            "n_splash" to nodeMain,
            "n_ad_dismiss" to nodeAdDismiss,
            "n_home" to nodeHome
        )

        val normalEdge = RouteGraphEdge(
            edgeId = "e_normal",
            fromNodeId = "n_splash",
            toNodeId = "n_home",
            action = MacroStep("phone_tap", buildJsonObject { put("ref", "@e_skip") }, ChainPredicateKind.NONE),
            isRecoveryBranch = false
        )

        val recoveryEdge = RouteGraphEdge(
            edgeId = "e_recovery",
            fromNodeId = "n_splash",
            toNodeId = "n_ad_dismiss",
            action = MacroStep("phone_tap", buildJsonObject { put("ref", "@e_close_ad") }, ChainPredicateKind.NONE),
            isRecoveryBranch = true
        )

        val graph = RouteGraph(
            graphId = "g_ad_bypass",
            intentKeyword = "打开首页",
            initialNodeId = "n_splash",
            nodes = nodes,
            edges = listOf(normalEdge, recoveryEdge)
        )

        assertTrue(graph.hasRecoveryBranch("n_splash"))
        assertFalse(graph.hasRecoveryBranch("n_ad_dismiss"))

        assertEquals(recoveryEdge, graph.findRecoveryEdge("n_splash"))
        assertEquals(normalEdge, graph.findNormalEdge("n_splash"))

        // 当需要优先自愈时走分支边，平时走常规边
        assertEquals(recoveryEdge, graph.findNextStep("n_splash", preferRecovery = true))
        assertEquals(normalEdge, graph.findNextStep("n_splash", preferRecovery = false))
    }

    @Test
    fun testNavigationLevelCheckpointSaveAndRestore() {
        val manager = SubgoalCheckpointManager()

        RegisterBus.put("token", "AUTH_TOKEN_XYZ")
        RegisterBus.put("city", "Beijing")

        val cp1 = SubgoalCheckpoint(
            checkpointId = "cp_step1",
            subgoalName = "完成登录并进入城市选择",
            packageName = "com.travel.app",
            uiSnapshotHash = 10101L,
            registers = mapOf("token" to "AUTH_TOKEN_XYZ", "city" to "Beijing")
        )
        manager.saveCheckpoint(cp1)

        // 模拟步骤 2 修改了寄存器
        RegisterBus.put("city", "Shanghai")
        val cp2 = SubgoalCheckpoint(
            checkpointId = "cp_step2",
            subgoalName = "已切换为上海",
            packageName = "com.travel.app",
            uiSnapshotHash = 10102L,
            registers = mapOf("token" to "AUTH_TOKEN_XYZ", "city" to "Shanghai")
        )
        manager.saveCheckpoint(cp2)

        assertEquals(2, manager.getCheckpoints().size)
        assertEquals("cp_step2", manager.getLatestCheckpoint()?.checkpointId)

        // 模拟后续崩溃，回滚至断点 1 并恢复寄存器状态
        val restored = manager.rollbackTo("cp_step1")
        assertNotNull(restored)
        assertEquals("cp_step1", restored?.checkpointId)
        assertEquals("Beijing", restored?.registers?.get("city"))
        assertEquals(1, manager.getCheckpoints().size)

        // 验证读取断点快照恢复 RegisterBus 状态
        restored?.registers?.forEach { (k, v) -> RegisterBus.put(k, v) }
        assertEquals("Beijing", RegisterBus.get("city"))
    }

    @Test
    fun testIrreversibleActionPreConditionRule() {
        val graph = RouteGraph(
            graphId = "g_test",
            intentKeyword = "高危动作核验",
            initialNodeId = "n0",
            nodes = emptyMap(),
            edges = emptyList()
        )

        // 支付动作不可逆
        val payAction = MacroStep("phone_pay", buildJsonObject { put("amount", 100) }, ChainPredicateKind.NONE)
        assertTrue(graph.isIrreversibleAction(payAction))

        // 删除动作不可逆
        val deleteAction = MacroStep("phone_tap", buildJsonObject { put("action", "delete_account") }, ChainPredicateKind.NONE)
        assertTrue(graph.isIrreversibleAction(deleteAction))

        // 转账动作不可逆
        val transferAction = MacroStep("phone_transfer", buildJsonObject { put("to", "user1") }, ChainPredicateKind.NONE)
        assertTrue(graph.isIrreversibleAction(transferAction))

        // 下单确认不可逆
        val confirmOrderAction = MacroStep("phone_tap", buildJsonObject { put("confirm_order", true) }, ChainPredicateKind.NONE)
        assertTrue(graph.isIrreversibleAction(confirmOrderAction))

        // 普通浏览/点击动作可逆
        val normalTap = MacroStep("phone_tap", buildJsonObject { put("ref", "@e1") }, ChainPredicateKind.NONE)
        assertFalse(graph.isIrreversibleAction(normalTap))

        val swipeAction = MacroStep("phone_swipe", buildJsonObject { put("direction", "down") }, ChainPredicateKind.NONE)
        assertFalse(graph.isIrreversibleAction(swipeAction))
    }
}

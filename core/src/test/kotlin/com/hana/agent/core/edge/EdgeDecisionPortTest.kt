package com.hana.agent.core.edge

import com.hana.agent.core.agent.IntentGate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeDecisionPortTest {

    @Test
    fun edgeDecisionPortNoneIsAlwaysUnreadyAndReturnsNull() = runTest {
        val none = EdgeDecisionPort.NONE
        assertFalse(none.isReady)
        assertNull(none.classifyIntent("打开微信"))
        assertNull(none.pickObstacleDismissRef("买东西", "弹窗", emptyList()))
        assertNull(none.matchReplayCandidate("Tap", "结算", emptyList()))
        assertNull(none.resolveAppFastAction("去红薯搜穿搭", emptyList()))
    }

    @Test
    fun edgeCandidateEqualityHandlesBoundsArray() {
        val c1 = EdgeDecisionPort.EdgeCandidate("@e1", "Button", "关闭", intArrayOf(100, 200, 300, 400))
        val c2 = EdgeDecisionPort.EdgeCandidate("@e1", "Button", "关闭", intArrayOf(100, 200, 300, 400))
        val c3 = EdgeDecisionPort.EdgeCandidate("@e2", "Button", "关闭", intArrayOf(100, 200, 300, 400))
        val c4 = EdgeDecisionPort.EdgeCandidate("@e1", "Button", "取消", intArrayOf(100, 200, 300, 400))

        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
        assertNotEquals(c1, c3)
        assertNotEquals(c1, c4)
    }

    @Test
    fun resolvedAppActionCalculatesPureOpenCorrectly() {
        val pure = EdgeDecisionPort.ResolvedAppAction("com.xingin.xhs", "小红书")
        assertTrue(pure.isPureOpen)
        assertNull(pure.searchQuery)

        val composite = EdgeDecisionPort.ResolvedAppAction("com.xingin.xhs", "小红书", searchQuery = "冬装")
        assertFalse(composite.isPureOpen)
        assertEquals("冬装", composite.searchQuery)
    }

    @Test
    fun intentGateBypassesCloudModelWhenEdgePortIsReady() = runTest {
        val dummyModel = com.hana.agent.core.testing.fakeModel("dummy") {
            answer("ACT")
        }

        val stubPort = object : EdgeDecisionPort {
            override val isReady: Boolean = true
            override suspend fun classifyIntent(
                task: String,
                recentContextSummary: String,
                confidenceThreshold: Float,
            ): IntentGate.Intent? {
                return if (task.contains("去微信")) IntentGate.Intent.Act else null
            }
            override suspend fun pickObstacleDismissRef(userGoal: String, dialogClues: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun matchReplayCandidate(stepGoal: String, recordedLabel: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun resolveAppFastAction(task: String, installedApps: List<Pair<String, String>>) = null
        }

        val gate = IntentGate(dummyModel, edgePort = stubPort)
        val intent = gate.assess("去微信发消息", emptyList())
        assertEquals(IntentGate.Intent.Act, intent)
        assertTrue("端侧 MNN-Pocket 意图决策命中时，云端模型绝不应该被唤醒", dummyModel.requests.isEmpty())
    }
}

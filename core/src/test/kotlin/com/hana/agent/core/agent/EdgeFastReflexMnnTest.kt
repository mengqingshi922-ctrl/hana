package com.hana.agent.core.agent

import com.hana.agent.core.edge.EdgeDecisionPort
import com.hana.agent.core.model.Author
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeFastReflexMnnTest {

    @Test
    fun mnnPointerDismissesDialogObstacleWhenRulesDoNotHit() = runTest {
        val screenContent = """
            {
                "screen": {
                    "elements": [
                        {"ref": "@e1", "name": "新版本已上线", "clickable": false},
                        {"ref": "@e2", "name": "立即更新", "clickable": true, "bounds": [500, 1000, 900, 1100]},
                        {"ref": "@e3", "name": "忍痛离开", "clickable": true, "bounds": [100, 1000, 400, 1100]}
                    ]
                }
            }
        """.trimIndent()

        val history = listOf(
            Message(Role.User, listOf(Part.Text("帮我在应用里浏览一下")), Author.User),
            Message(Role.Tool, listOf(Part.ToolResult("call_1", screenContent, isError = false)), Author.Mika)
        )

        val stubPort = object : EdgeDecisionPort {
            override val isReady: Boolean = true
            override suspend fun classifyIntent(task: String, recentContextSummary: String, confidenceThreshold: Float) = null
            override suspend fun pickObstacleDismissRef(
                userGoal: String,
                dialogClues: String,
                candidates: List<EdgeDecisionPort.EdgeCandidate>,
                confidenceThreshold: Float,
            ): String? {
                return candidates.firstOrNull { it.label == "忍痛离开" }?.ref
            }
            override suspend fun matchReplayCandidate(stepGoal: String, recordedLabel: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun resolveAppFastAction(task: String, installedApps: List<Pair<String, String>>) = null
        }

        val result = EdgeFastReflex.inspect(history, "帮我在应用里浏览一下", 1, stubPort)
        assertNotNull(result)
        assertEquals("mnn_obstacle_popup: 忍痛离开", result?.reason)
        val toolCall = result?.reply?.parts?.filterIsInstance<Part.ToolCall>()?.firstOrNull()
        assertNotNull(toolCall)
        assertEquals("phone_tap", toolCall?.name)
        assertTrue(toolCall?.argsJson?.contains("@e3") == true)
        assertTrue(toolCall?.argsJson?.contains("250") == true) // (100+400)/2
    }

    @Test
    fun returnsNullWhenMnnRejectsAllCandidatesAsLegitBusiness() = runTest {
        val screenContent = """
            {
                "screen": {
                    "elements": [
                        {"ref": "@e1", "name": "商品详情", "clickable": false},
                        {"ref": "@e2", "name": "立即购买", "clickable": true, "bounds": [500, 1000, 900, 1100]},
                        {"ref": "@e3", "name": "加入购物车", "clickable": true, "bounds": [100, 1000, 400, 1100]}
                    ]
                }
            }
        """.trimIndent()

        val history = listOf(
            Message(Role.User, listOf(Part.Text("帮我买衣服")), Author.User),
            Message(Role.Tool, listOf(Part.ToolResult("call_1", screenContent, isError = false)), Author.Mika)
        )

        val stubPort = object : EdgeDecisionPort {
            override val isReady: Boolean = true
            override suspend fun classifyIntent(task: String, recentContextSummary: String, confidenceThreshold: Float) = null
            override suspend fun pickObstacleDismissRef(userGoal: String, dialogClues: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun matchReplayCandidate(stepGoal: String, recordedLabel: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun resolveAppFastAction(task: String, installedApps: List<Pair<String, String>>) = null
        }

        val result = EdgeFastReflex.inspect(history, "帮我买衣服", 1, stubPort)
        assertNull("业务按钮不应被误判为弹窗关闭，必须返回 null 交由云端模型", result)
    }

    @Test
    fun doesNotTriggerMnnWhenElementsExceed18() = runTest {
        // 构造大于 18 个元素的复杂业务页面
        val elementsJson = (1..20).joinToString(",") { idx ->
            """{"ref": "@e$idx", "name": "业务选项$idx", "clickable": true, "bounds": [0, $idx * 50, 500, $idx * 50 + 40]}"""
        }
        val screenContent = """{"screen": {"elements": [$elementsJson]}}"""

        val history = listOf(
            Message(Role.User, listOf(Part.Text("帮我选择")), Author.User),
            Message(Role.Tool, listOf(Part.ToolResult("call_1", screenContent, isError = false)), Author.Mika)
        )

        var portCalled = false
        val stubPort = object : EdgeDecisionPort {
            override val isReady: Boolean = true
            override suspend fun classifyIntent(task: String, recentContextSummary: String, confidenceThreshold: Float) = null
            override suspend fun pickObstacleDismissRef(userGoal: String, dialogClues: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float): String? {
                portCalled = true
                return "@e1"
            }
            override suspend fun matchReplayCandidate(stepGoal: String, recordedLabel: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun resolveAppFastAction(task: String, installedApps: List<Pair<String, String>>) = null
        }

        val result = EdgeFastReflex.inspect(history, "帮我选择", 1, stubPort)
        assertNull("全量业务界面(>18元素)绝不应触发端侧弹窗消解，防止越界误伤", result)
        assertFalse("非轻量浮层严禁调用端侧 pickObstacleDismissRef", portCalled)
    }
}

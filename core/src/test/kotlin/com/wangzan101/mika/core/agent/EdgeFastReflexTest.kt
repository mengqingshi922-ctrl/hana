package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeFastReflexTest {

    @Test
    fun hitsPermissionPopupAndGeneratesDirectTap() {
        val screenContent = """
            {
                "screen": {
                    "snapshotId": "snap_1",
                    "elements": [
                        {"ref": "@e1", "name": "允许", "clickable": true, "bounds": [500, 800, 700, 900]},
                        {"ref": "@e2", "name": "拒绝", "clickable": true, "bounds": [100, 800, 300, 900]}
                    ]
                }
            }
        """.trimIndent()

        val history = listOf(
            Message(Role.User, listOf(Part.Text("打开微信查看消息")), Author.User),
            Message(Role.Tool, listOf(Part.ToolResult("call_1", screenContent, isError = false)), Author.Mika)
        )

        val result = EdgeFastReflex.inspect(history, "打开微信查看消息", 1)
        assertNotNull(result)
        val toolCall = result?.reply?.parts?.filterIsInstance<Part.ToolCall>()?.firstOrNull()
        assertNotNull(toolCall)
        assertEquals("phone_tap", toolCall?.name)
        assertTrue(toolCall?.argsJson?.contains("@e1") == true)
        assertTrue(toolCall?.argsJson?.contains("600") == true) // cx = (500+700)/2
        assertTrue(toolCall?.argsJson?.contains("850") == true) // cy = (800+900)/2
    }

    @Test
    fun hitsAdSkipPopupAndGeneratesDirectTap() {
        val screenContent = """
            {
                "screen": {
                    "snapshotId": "snap_2",
                    "elements": [
                        {"ref": "@e10", "name": "跳过广告", "clickable": true, "bounds": [800, 50, 950, 100]},
                        {"ref": "@e11", "name": "广告详情", "clickable": true, "bounds": [100, 200, 500, 300]}
                    ]
                }
            }
        """.trimIndent()

        val history = listOf(
            Message(Role.User, listOf(Part.Text("帮我听歌")), Author.User),
            Message(Role.Tool, listOf(Part.ToolResult("call_2", screenContent, isError = false)), Author.Mika)
        )

        val result = EdgeFastReflex.inspect(history, "帮我听歌", 2)
        assertNotNull(result)
        val toolCall = result?.reply?.parts?.filterIsInstance<Part.ToolCall>()?.firstOrNull()
        assertNotNull(toolCall)
        assertEquals("phone_tap", toolCall?.name)
        assertTrue(toolCall?.argsJson?.contains("@e10") == true)
    }

    @Test
    fun avoidsFalsePositiveWhenUserGoalExplicitlyTargetsKeyword() {
        val screenContent = """
            {
                "screen": {
                    "snapshotId": "snap_3",
                    "elements": [
                        {"ref": "@e5", "name": "允许", "clickable": true, "bounds": [500, 800, 700, 900]}
                    ]
                }
            }
        """.trimIndent()

        val history = listOf(
            Message(Role.User, listOf(Part.Text("在设置里不要点允许")), Author.User),
            Message(Role.Tool, listOf(Part.ToolResult("call_3", screenContent, isError = false)), Author.Mika)
        )

        // 用户目标包含“允许”，反射引擎防误伤，应返回 null 移交给大模型决策
        val result = EdgeFastReflex.inspect(history, "在设置里不要点允许", 1)
        assertNull(result)
    }

    @Test
    fun triggersAntiLoopBreakerOnConsecutiveIdenticalTapsWithoutChange() {
        val tapCall1 = Part.ToolCall("call_tap_1", "phone_tap", """{"ref":"@target_btn"}""")
        val tapCall2 = Part.ToolCall("call_tap_2", "phone_tap", """{"ref":"@target_btn"}""")
        val noChangeResult = """{"changed": false, "screen": {"elements": []}}"""

        val history = listOf(
            Message(Role.User, listOf(Part.Text("帮我点确定")), Author.User),
            Message(Role.Assistant, listOf(tapCall1), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("call_tap_1", noChangeResult, isError = false)), Author.Mika),
            Message(Role.Assistant, listOf(tapCall2), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("call_tap_2", noChangeResult, isError = false)), Author.Mika)
        )

        val result = EdgeFastReflex.inspect(history, "帮我点确定", 3)
        assertNotNull(result)
        assertEquals("anti_loop_breaker", result?.reason)
        val toolCall = result?.reply?.parts?.filterIsInstance<Part.ToolCall>()?.firstOrNull()
        assertNotNull(toolCall)
        assertEquals("phone_back", toolCall?.name)
    }
}

package com.hana.agent.core.tool

import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.model.Part
import com.hana.agent.core.uistate.ActionResult
import com.hana.agent.core.uistate.ScreenPort
import com.hana.agent.core.uistate.UiElement
import com.hana.agent.core.uistate.Verification
import com.hana.agent.core.vision.JvmTestImageOps
import com.hana.agent.core.vision.LocScreen
import com.hana.agent.core.vision.VisionService
import com.hana.agent.core.vision.says
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapAndTextFallbackTest {
    private val element = UiElement(
        ref = "@e1",
        role = "button",
        name = "提交",
        centerX = 540,
        centerY = 130,
        bounds = listOf(100, 100, 900, 160),
        resourceId = "com.app:id/submit",
        clickable = true,
        editable = true,
        scrollable = false,
        focused = true,
    )

    private class FallbackTestScreen(
        element: UiElement,
        var tapRefResult: ActionResult = ActionResult.Ok(changed = false),
        var tapXYResult: ActionResult = ActionResult.Ok(changed = true),
        var typeTextResult: ActionResult = ActionResult.Ok(changed = true, verification = Verification.MISMATCH),
        var typeAtResult: ActionResult = ActionResult.Ok(changed = true, verification = Verification.MATCH),
    ) : ScreenPort by LocScreen(listOf(element)) {
        val calls = mutableListOf<String>()

        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            calls += "tapRef $ref"
            return tapRefResult
        }

        override suspend fun tapXY(x: Int, y: Int): ActionResult {
            calls += "tapXY $x $y"
            return tapXYResult
        }

        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
            calls += "typeText $ref $text"
            return typeTextResult
        }

        override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult {
            calls += "typeAt $x,$y $text"
            return typeAtResult
        }

        override suspend fun pressKey(keyCode: Int): ActionResult {
            calls += "pressKey $keyCode"
            return ActionResult.Ok()
        }
    }

    private var seq = 0
    private fun call(name: String, args: String) = Part.ToolCall("t${++seq}", name, args)
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
    private fun d(s: FallbackTestScreen) = toolDispatcher(s, vision = VisionService(says(), JvmTestImageOps))

    @Test
    fun tapRefFallbackToPhysicalTouchWhenUnchanged() = runTest {
        val s = FallbackTestScreen(element, tapRefResult = ActionResult.Ok(changed = false), tapXYResult = ActionResult.Ok(changed = true))
        val d = d(s)
        val snap = d.execute(call("phone_observe", "{}")).content.s("snapshotId")
        val r = d.execute(call("phone_tap", """{"ref":"@e1","snapshotId":"$snap"}""")).content
        assertEquals("true", r.s("ok"))
        assertEquals("true", r.s("changed"))
        // 验证先调用无障碍 tapRef，由于未产生画面变化，降级调用底层 tapXY 物理触摸
        assertEquals(listOf("tapRef @e1", "tapXY 540 130"), s.calls)
    }

    @Test
    fun tapRefDoesNotFallbackWhenChanged() = runTest {
        val s = FallbackTestScreen(element, tapRefResult = ActionResult.Ok(changed = true))
        val d = d(s)
        val snap = d.execute(call("phone_observe", "{}")).content.s("snapshotId")
        val r = d.execute(call("phone_tap", """{"ref":"@e1","snapshotId":"$snap"}""")).content
        assertEquals("true", r.s("ok"))
        assertEquals("true", r.s("changed"))
        assertEquals(listOf("tapRef @e1"), s.calls)
    }

    @Test
    fun typeTextFallbackToNativePasteWhenMismatch() = runTest {
        val s = FallbackTestScreen(
            element,
            typeTextResult = ActionResult.Ok(changed = true, verification = Verification.MISMATCH),
            typeAtResult = ActionResult.Ok(changed = true, verification = Verification.MATCH),
        )
        val d = d(s)
        val snap = d.execute(call("phone_observe", "{}")).content.s("snapshotId")
        val r = d.execute(call("phone_type_text", """{"ref":"@e1","snapshotId":"$snap","text":"测试输入"}""")).content
        assertEquals("true", r.s("ok"))
        assertEquals("match", r.s("verification"))
        // 验证标准输入发生 MISMATCH 时，自动回退走原生粘贴坐标 typeAt
        assertEquals(listOf("typeText @e1 测试输入", "typeAt 540,130 测试输入"), s.calls)
    }

    @Test
    fun typeTextDoesNotFallbackWhenMatched() = runTest {
        val s = FallbackTestScreen(
            element,
            typeTextResult = ActionResult.Ok(changed = true, verification = Verification.MATCH),
        )
        val d = d(s)
        val snap = d.execute(call("phone_observe", "{}")).content.s("snapshotId")
        val r = d.execute(call("phone_type_text", """{"ref":"@e1","snapshotId":"$snap","text":"正常输入"}""")).content
        assertEquals("true", r.s("ok"))
        assertEquals("match", r.s("verification"))
        assertEquals(listOf("typeText @e1 正常输入"), s.calls)
    }
}

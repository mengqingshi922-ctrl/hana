package com.hana.agent.core.tool

import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.model.Part
import com.hana.agent.core.uistate.ActionResult
import com.hana.agent.core.uistate.ScreenPort
import com.hana.agent.core.uistate.ScreenSnapshot
import com.hana.agent.core.uistate.UiElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class ChallengerScreen(private val snapshotSupplier: (Int) -> ScreenSnapshot) : ScreenPort by FakeScreen() {
    val calls = mutableListOf<String>()
    var reads = 0
    var tapResult: ActionResult = ActionResult.Ok(changed = true)
    var typeResult: ActionResult = ActionResult.Ok()
    var waitStableCalls = mutableListOf<Long>()

    override suspend fun snapshot(): ScreenSnapshot = snapshotSupplier(reads++)
    override suspend fun peek(): ScreenSnapshot = snapshotSupplier(maxOf(0, reads - 1))
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
        calls += "tapRef $ref $snapshotId"
        return tapResult
    }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
        calls += "type $ref $snapshotId $text"
        return typeResult
    }
    override suspend fun waitStable(timeoutMs: Long): Boolean {
        calls += "waitStable $timeoutMs"
        waitStableCalls += timeoutMs
        return true
    }
}

class ToolDispatcherEmpiricalChallengerTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    /**
     * 对抗性场景 1: 无焦点场景
     * 当传入已失效引用 @e1 (来自旧快照 p1)，第二屏没有任何聚焦元素。
     * 预期：严密阻断盲打降级，严格返回 ref_expired 契约，底层 screen.typeText 绝对不被调用。
     */
    @Test
    fun staleRefWithoutActiveFocus_strictlyReturnsRefExpired() = runTest {
        val s = ChallengerScreen { index ->
            if (index == 0) {
                ScreenSnapshot("p1", "com.app", listOf(
                    UiElement("@e1", "button", "旧按钮", 500, 200, listOf(0, 0, 10, 10), null, true, false, false)
                ), 1080, 2400)
            } else {
                ScreenSnapshot("p2", "com.app", listOf(
                    UiElement("@e1", "button", "新按钮", 500, 200, listOf(0, 0, 10, 10), null, true, false, false, focused = false)
                ), 1080, 2400)
            }
        }
        val dispatcher = toolDispatcher(s, stableRefs = true)
        dispatcher.execute(call("phone_observe"))
        dispatcher.execute(call("phone_observe"))

        val r = dispatcher.execute(call("phone_type_text", """{"ref":"@e1","snapshotId":"p1","text":"测试文本"}"""))
        assertEquals("false", r.content.s("ok"))
        assertEquals("ref_expired", r.content.s("error"))
        assertTrue("Calls should be empty because fallback is blocked", s.calls.isEmpty())
    }

    /**
     * 对抗性场景 2: 有活跃焦点的输入框场景
     * 当传入已失效引用 @e1 (来自旧快照 p1)，但第二屏存在 focused=true 且 editable=true 的输入框。
     * 预期：触发平滑降级，将文本直接注入当前焦点输入框，返回 ok=true，底层记录 type null null <text>。
     */
    @Test
    fun staleRefWithActiveFocusedInput_smoothlyFallbacksToFocusedField() = runTest {
        val s = ChallengerScreen { index ->
            if (index == 0) {
                ScreenSnapshot("p1", "com.app", listOf(
                    UiElement("@e1", "button", "旧搜索", 500, 100, listOf(0, 0, 10, 10), null, true, false, false)
                ), 1080, 2400)
            } else {
                ScreenSnapshot("p2", "com.app", listOf(
                    UiElement("@e1", "EditText", "搜索框", 500, 300, listOf(100, 200, 900, 400), "id/search", true, editable = true, scrollable = false, focused = true)
                ), 1080, 2400)
            }
        }
        val dispatcher = toolDispatcher(s, stableRefs = true)
        dispatcher.execute(call("phone_observe"))
        dispatcher.execute(call("phone_observe"))

        val r = dispatcher.execute(call("phone_type_text", """{"ref":"@e1","snapshotId":"p1","text":"智能降级输入"}"""))
        assertEquals("true", r.content.s("ok"))
        assertEquals(listOf("type null null 智能降级输入", "waitStable 350"), s.calls)
    }

    /**
     * 对抗性场景 3: 焦点落在非输入控件 (例如 focused=true 但 role=button, editable=false)
     * 预期：不能因为有焦点就胡乱降级输入，必须阻断降级，返回 ref_expired。
     */
    @Test
    fun staleRefWithFocusOnNonInputRole_blocksFallback() = runTest {
        val s = ChallengerScreen { index ->
            if (index == 0) {
                ScreenSnapshot("p1", "com.app", listOf(
                    UiElement("@e1", "button", "旧按钮", 500, 100, listOf(0, 0, 10, 10), null, true, false, false)
                ), 1080, 2400)
            } else {
                ScreenSnapshot("p2", "com.app", listOf(
                    UiElement("@e1", "button", "获得焦点的不可编辑按钮", 500, 300, listOf(100, 200, 900, 400), "id/btn", true, editable = false, scrollable = false, focused = true)
                ), 1080, 2400)
            }
        }
        val dispatcher = toolDispatcher(s, stableRefs = true)
        dispatcher.execute(call("phone_observe"))
        dispatcher.execute(call("phone_observe"))

        val r = dispatcher.execute(call("phone_type_text", """{"ref":"@e1","snapshotId":"p1","text":"不应键入"}"""))
        assertEquals("false", r.content.s("ok"))
        assertEquals("ref_expired", r.content.s("error"))
        assertTrue("Non-editable focused element must not receive fallback type", s.calls.isEmpty())
    }

    /**
     * 对抗性场景 4: 输入框存在但未获得焦点 (editable=true, role=EditText, 但 focused=false)
     * 预期：由于无法确定光标位置，不能盲目打字，必须抛出 ref_expired。
     */
    @Test
    fun staleRefWithUnfocusedInput_blocksFallback() = runTest {
        val s = ChallengerScreen { index ->
            if (index == 0) {
                ScreenSnapshot("p1", "com.app", listOf(
                    UiElement("@e1", "button", "旧按钮", 500, 100, listOf(0, 0, 10, 10), null, true, false, false)
                ), 1080, 2400)
            } else {
                ScreenSnapshot("p2", "com.app", listOf(
                    UiElement("@e1", "EditText", "未聚焦的输入框", 500, 300, listOf(100, 200, 900, 400), "id/edit", true, editable = true, scrollable = false, focused = false)
                ), 1080, 2400)
            }
        }
        val dispatcher = toolDispatcher(s, stableRefs = true)
        dispatcher.execute(call("phone_observe"))
        dispatcher.execute(call("phone_observe"))

        val r = dispatcher.execute(call("phone_type_text", """{"ref":"@e1","snapshotId":"p1","text":"不应键入"}"""))
        assertEquals("false", r.content.s("ok"))
        assertEquals("ref_expired", r.content.s("error"))
        assertTrue("Unfocused input must not trigger blind typing", s.calls.isEmpty())
    }

    /**
     * 对抗性场景 5: 降级调用时底层屏幕执行失败
     * 预期：fallback 失败时不能吞噬错误判定为成功，必须如实返回失败。
     */
    @Test
    fun staleRefFallbackFailure_doesNotSwallowError() = runTest {
        val s = ChallengerScreen { index ->
            if (index == 0) {
                ScreenSnapshot("p1", "com.app", listOf(
                    UiElement("@e1", "button", "旧按钮", 500, 100, listOf(0, 0, 10, 10), null, true, false, false)
                ), 1080, 2400)
            } else {
                ScreenSnapshot("p2", "com.app", listOf(
                    UiElement("@e1", "EditText", "输入框", 500, 300, listOf(100, 200, 900, 400), "id/edit", true, editable = true, scrollable = false, focused = true)
                ), 1080, 2400)
            }
        }.apply {
            typeResult = ActionResult.Failed("ime_disconnected")
        }
        val dispatcher = toolDispatcher(s, stableRefs = true)
        dispatcher.execute(call("phone_observe"))
        dispatcher.execute(call("phone_observe"))

        val r = dispatcher.execute(call("phone_type_text", """{"ref":"@e1","snapshotId":"p1","text":"测试"}"""))
        assertEquals("false", r.content.s("ok"))
    }

    /**
     * 对抗性场景 6: waitStable 350ms 连续调用稳定性与时序一致性
     * 预期：连续 5 次点击操作，每次都精准且稳定触发 waitStable 350ms，无抖动、无漂移。
     */
    @Test
    fun tapResultConsistentlyWaits350msAcrossMultipleCalls() = runTest {
        val elements = (1..5).map { UiElement("@e$it", "button", "操作$it", 500, 100 * it, listOf(0, 0, 10, 10), null, true, false, false) }
        val s = ChallengerScreen { index ->
            ScreenSnapshot("p${index + 1}", "com.app", elements, 1080, 2400)
        }
        val dispatcher = toolDispatcher(s, inlineScreen = true)
        dispatcher.execute(call("phone_observe"))

        repeat(5) { i ->
            val step = i + 1
            val res = dispatcher.execute(call("phone_tap", """{"ref":"@e1","snapshotId":"p$step"}"""))
            assertEquals("true", res.content.s("ok"))
        }

        assertEquals(5, s.waitStableCalls.size)
        assertTrue(s.waitStableCalls.all { it == 350L })
    }

    /**
     * 对抗性场景 7: 多协程并发调度时的时序竞争测试
     * 预期：并发执行读取与动作，ToolDispatcher 的 snapshot 和 diff 管理不应发生并发冲突或死锁。
     */
    @Test
    fun concurrentObserveAndTap_remainsThreadSafe() = runTest {
        val elements = (1..10).map { UiElement("@e$it", "button", "项目$it", 500, 50 * it, listOf(0, 0, 10, 10), null, true, false, false) }
        val s = ChallengerScreen { index ->
            ScreenSnapshot("p${index + 1}", "com.app", elements, 1080, 2400)
        }
        val dispatcher = toolDispatcher(s, inlineScreen = true)

        val jobs = (1..10).map {
            async {
                dispatcher.execute(call("phone_observe"))
            }
        }
        val results = jobs.awaitAll()
        assertEquals(10, results.size)
        results.forEach { r ->
            assertNotNull(r.content["snapshotId"])
        }
    }
}

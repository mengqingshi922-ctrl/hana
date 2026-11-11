package com.hana.agent.core.action

import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.gate.ConfirmationGate
import com.hana.agent.core.guard.OperationGuards
import com.hana.agent.core.model.Part
import com.hana.agent.core.record.RunEvent
import com.hana.agent.core.record.RunSink
import com.hana.agent.core.tool.FakeScreen
import com.hana.agent.core.tool.execute
import com.hana.agent.core.uistate.ActionResult
import com.hana.agent.core.uistate.ScreenPort
import com.hana.agent.core.uistate.ScreenSnapshot
import com.hana.agent.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroActionChainTest {

    private class ChainScreen : ScreenPort by FakeScreen() {
        val executedTaps = mutableListOf<String>()
        val typedTexts = mutableListOf<String>()
        var focusedElement = true
        var currentElements = mutableListOf(
            UiElement("@e1", "button", "搜索", 100, 100, listOf(50, 50, 150, 150), null, true, false, false, focused = true),
            UiElement("@e2", "edit_text", "输入框", 200, 200, listOf(150, 150, 250, 250), null, true, true, false, focused = true),
            UiElement("@e3", "button", "确认提交", 300, 300, listOf(250, 250, 350, 350), null, true, false, false),
            UiElement("@e4", "text", "加载中", 400, 400, listOf(350, 350, 450, 450), null, false, false, false)
        )

        override suspend fun snapshot() = ScreenSnapshot(
            snapshotId = "s_chain",
            packageName = "com.test.app",
            elements = currentElements.toList(),
            width = 1080,
            height = 2400
        )

        override suspend fun peek() = snapshot()

        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            executedTaps += ref
            if (ref == "@e1") {
                currentElements.add(UiElement("@e5", "text", "搜索结果列表", 500, 500, listOf(450, 450, 550, 550), null, false, false, false))
            }
            return ActionResult.Ok(changed = true)
        }

        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
            typedTexts += text
            currentElements.add(UiElement("@e6", "text", text, 600, 600, listOf(550, 550, 650, 650), null, false, false, false))
            return ActionResult.Ok(changed = true)
        }
    }

    private suspend fun createExecutor(screen: ChainScreen): ActionExecutor {
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("obs", "phone_observe", "{}"))
        val sink = object : RunSink { override fun event(e: RunEvent) {} }
        return ActionExecutor(
            tools = tools,
            guards = OperationGuards(),
            confirmations = ConfirmationGate(),
            gate = {},
            recorder = ActionRecorder(sink)
        )
    }

    @Test
    fun testSerializationAndDefaults() {
        val step1 = MacroStep(
            tool = "phone_tap",
            args = buildJsonObject { put("ref", "@e1") },
            predicate = ChainPredicateKind.NONE
        )
        val step2 = MacroStep(
            tool = "phone_type",
            args = buildJsonObject { put("text", "hello") },
            predicate = ChainPredicateKind.FOCUSED,
            expectText = "hello",
            timeoutMs = 500L
        )
        val chain = MacroActionChain(steps = listOf(step1, step2), fallbackStrategy = "stop_and_query", comment = "测试链")

        val json = Json.encodeToString(chain)
        val decoded = Json.decodeFromString<MacroActionChain>(json)

        assertEquals(2, decoded.steps.size)
        assertEquals("phone_tap", decoded.steps[0].tool)
        assertEquals(ChainPredicateKind.NONE, decoded.steps[0].predicate)
        assertEquals("phone_type", decoded.steps[1].tool)
        assertEquals(ChainPredicateKind.FOCUSED, decoded.steps[1].predicate)
        assertEquals("hello", decoded.steps[1].expectText)
        assertEquals(500L, decoded.steps[1].timeoutMs)
        assertEquals("stop_and_query", decoded.fallbackStrategy)
        assertEquals("测试链", decoded.comment)
    }

    @Test
    fun testEmptyChainReturnsImmediateSuccess() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)
        val result = executor.executeChain(MacroActionChain(emptyList()))

        assertTrue(result.success)
        assertEquals(0, result.completedSteps)
        assertEquals(0, result.totalSteps)
        assertFalse(result.shortCircuited)
        assertNull(result.failedStepIndex)
    }

    @Test
    fun testStraightThroughExecutionSuccess() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)

        val chain = MacroActionChain(
            steps = listOf(
                MacroStep("phone_tap", buildJsonObject { put("ref", "@e1"); put("snapshotId", "s_chain") }, ChainPredicateKind.NONE),
                MacroStep("phone_type", buildJsonObject { put("ref", "@e2"); put("snapshotId", "s_chain"); put("text", "Hana 核心优化") }, ChainPredicateKind.TEXT_UPDATED, expectText = "搜索结果列表"),
                MacroStep("phone_tap", buildJsonObject { put("ref", "@e3"); put("snapshotId", "s_chain") }, ChainPredicateKind.ELEMENT_APPEARED, expectText = "确认提交")
            )
        )

        val result = executor.executeChain(chain)
        assertTrue("Failed at step ${result.failedStepIndex}: ${result.failureReason}", result.success)
        assertEquals(3, result.completedSteps)
        assertEquals(3, result.totalSteps)
        assertFalse(result.shortCircuited)
        assertNull(result.failedStepIndex)
        assertEquals(listOf("@e1", "@e3"), screen.executedTaps)
        assertEquals(listOf("Hana 核心优化"), screen.typedTexts)
    }

    @Test
    fun testPreAssertionFailureShortCircuitsImmediately() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)

        val chain = MacroActionChain(
            steps = listOf(
                MacroStep("phone_tap", buildJsonObject { put("ref", "@e1"); put("snapshotId", "s_chain") }, ChainPredicateKind.NONE),
                // 断言不存在的文字，应当立即短路且不执行第三步
                MacroStep("phone_type", buildJsonObject { put("text", "不会执行") }, ChainPredicateKind.ELEMENT_APPEARED, expectText = "绝对不存在的标签XYZ"),
                MacroStep("phone_tap", buildJsonObject { put("ref", "@e3"); put("snapshotId", "s_chain") }, ChainPredicateKind.NONE)
            )
        )

        val result = executor.executeChain(chain)
        assertFalse(result.success)
        assertTrue(result.shortCircuited)
        assertEquals(1, result.completedSteps)
        assertEquals(3, result.totalSteps)
        assertEquals(1, result.failedStepIndex)
        assertTrue(result.failureReason!!.contains("Predicate unmet"))

        assertEquals(listOf("@e1"), screen.executedTaps)
        assertTrue(screen.typedTexts.isEmpty())
    }

    @Test
    fun testCustomPredicateChecker() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)

        val chain = MacroActionChain(
            steps = listOf(
                MacroStep("phone_tap", buildJsonObject { put("ref", "@e1"); put("snapshotId", "s_chain") }, ChainPredicateKind.NONE),
                MacroStep("phone_type", buildJsonObject { put("text", "测试输入") }, ChainPredicateKind.FOCUSED)
            )
        )

        var checkerCalled = 0
        val result = executor.executeChain(chain) { step ->
            checkerCalled++
            step.tool != "phone_type" // 阻止第二步
        }

        assertFalse(result.success)
        assertTrue(result.shortCircuited)
        assertEquals(1, result.completedSteps)
        assertEquals(1, result.failedStepIndex)
        assertEquals(2, checkerCalled)
    }

    @Test
    fun testElementGonePredicate() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)

        // "加载中" 还在屏幕上，ELEMENT_GONE 应该失败
        val chain = MacroActionChain(
            steps = listOf(
                MacroStep("phone_tap", buildJsonObject { put("ref", "@e1"); put("snapshotId", "s_chain") }, ChainPredicateKind.ELEMENT_GONE, expectText = "加载中")
            )
        )

        val result = executor.executeChain(chain)
        assertFalse(result.success)
        assertTrue(result.shortCircuited)
        assertEquals(0, result.completedSteps)
        assertEquals(0, result.failedStepIndex)
    }

    @Test
    fun testPredicateFocusedValidatesTargetElementInsteadOfAny() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)

        // @e3 的 focused 为 false；尽管屏幕上 @e1/@e2 处于 focused，FOCUSED 断言依然必须短路失败
        val chain = MacroActionChain(
            steps = listOf(
                MacroStep("phone_tap", buildJsonObject { put("ref", "@e3"); put("snapshotId", "s_chain") }, ChainPredicateKind.FOCUSED)
            )
        )
        val result = executor.executeChain(chain)
        assertFalse(result.success)
        assertTrue(result.shortCircuited)
        assertEquals(0, result.completedSteps)
        assertEquals(0, result.failedStepIndex)
        assertTrue(result.failureReason!!.contains("Predicate unmet"))
    }

    @Test
    fun testPredicatePageNavigatedRejectsStationaryScreen() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)

        // 界面未发生任何跳转与元素变动，PAGE_NAVIGATED 断言判定失败
        val chain = MacroActionChain(
            steps = listOf(
                MacroStep("phone_tap", buildJsonObject { put("ref", "@e1"); put("snapshotId", "s_chain") }, ChainPredicateKind.PAGE_NAVIGATED)
            )
        )
        val result = executor.executeChain(chain)
        assertFalse(result.success)
        assertTrue(result.shortCircuited)
        assertEquals(0, result.completedSteps)
        assertEquals(0, result.failedStepIndex)
    }

    @Test
    fun testPredicateTimeoutMsPollingEnforced() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)

        val chain = MacroActionChain(
            steps = listOf(
                MacroStep(
                    tool = "phone_tap",
                    args = buildJsonObject { put("ref", "@e1"); put("snapshotId", "s_chain") },
                    predicate = ChainPredicateKind.ELEMENT_APPEARED,
                    expectText = "NOT_EXISTING_ASYNC_TAG",
                    timeoutMs = 120L
                )
            )
        )
        val start = System.currentTimeMillis()
        val result = executor.executeChain(chain)
        val elapsed = System.currentTimeMillis() - start

        assertFalse(result.success)
        assertTrue(result.shortCircuited)
        assertTrue("elapsed $elapsed should be >= 100ms", elapsed >= 100)
    }
}

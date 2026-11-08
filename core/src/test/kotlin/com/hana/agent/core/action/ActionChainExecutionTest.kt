package com.hana.agent.core.action

import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.cap.phone.ActionChainTool
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
import com.hana.agent.core.uistate.UiWindowTree
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionChainExecutionTest {

    private class ChainScreen : ScreenPort by FakeScreen() {
        val calls = mutableListOf<String>()
        var modalActive = false

        private val normalElements = listOf(
            UiElement("@e1", "button", "点击按钮", 100, 100, listOf(50, 50, 150, 150), null, true, false, false, focused = true),
            UiElement("@e2", "edit", "输入框", 200, 200, listOf(150, 150, 250, 250), null, true, true, false, focused = true)
        )

        override suspend fun snapshot(): ScreenSnapshot {
            val windows = if (modalActive) {
                listOf(
                    UiWindowTree(0, "application", layer = 0, title = "MainApp", packageName = "com.test.app", active = false, focused = false, nodes = emptyList()),
                    UiWindowTree(1, "dialog", layer = 2, title = "系统更新提示", packageName = "com.test.app", active = true, focused = true, nodes = emptyList())
                )
            } else {
                listOf(
                    UiWindowTree(0, "application", layer = 0, title = "MainApp", packageName = "com.test.app", active = true, focused = true, nodes = emptyList())
                )
            }
            return ScreenSnapshot(
                snapshotId = "s_chain",
                packageName = "com.test.app",
                elements = normalElements,
                width = 1080,
                height = 2400,
                windows = windows
            )
        }

        override suspend fun peek() = snapshot()

        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            calls += "tapRef:$ref"
            return ActionResult.Ok(changed = true)
        }

        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
            calls += "typeText:$text"
            return ActionResult.Ok(changed = true)
        }

        override suspend fun back(): ActionResult {
            calls += "back"
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
    fun test35msRenderSettleWindowConstant() {
        // 验证 35ms 渲染平水期与最多 3 步投机限制规范常量
        assertEquals(35L, ActionExecutor.RENDER_SETTLE_WINDOW_MS)
        assertEquals(3, ActionExecutor.MAX_CHAIN_STEPS)
    }

    @Test
    fun testMaxStepLimitEnforcement() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)

        // 构造包含 4 步的投机链（超过上限 3 步）
        val fourSteps = (1..4).map { i ->
            MacroStep(
                tool = "phone_tap",
                args = buildJsonObject { put("ref", "@e1") },
                predicate = ChainPredicateKind.NONE
            )
        }
        val chain = MacroActionChain(steps = fourSteps)

        val result = executor.executeChain(chain)
        assertFalse(result.success)
        assertTrue(result.shortCircuited)
        assertEquals(0, result.completedSteps)
        assertEquals(4, result.totalSteps)
        assertEquals("chain_exceeds_max_steps_3", result.failureReason)
    }

    @Test
    fun testModalOverlayZOrderInterception() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)

        // 模拟前台出现系统更新/模态弹窗遮挡
        screen.modalActive = true

        val steps = listOf(
            MacroStep(
                tool = "phone_tap",
                args = buildJsonObject { put("ref", "@e1") },
                predicate = ChainPredicateKind.NONE
            )
        )
        val chain = MacroActionChain(steps = steps)

        val result = executor.executeChain(chain)
        assertFalse(result.success)
        assertTrue(result.shortCircuited)
        assertEquals(0, result.completedSteps)
        assertEquals("modal_overlay_intercepted", result.failureReason)
        assertEquals(0, screen.calls.size)
    }

    @Test
    fun testRollbackPhysicalBackCompensationOnAssertionFailure() = runTest {
        val screen = ChainScreen()
        val executor = createExecutor(screen)

        val steps = listOf(
            MacroStep(
                tool = "phone_tap",
                args = buildJsonObject { put("ref", "@e1"); put("snapshotId", "s_chain") },
                predicate = ChainPredicateKind.NONE
            ),
            MacroStep(
                tool = "phone_type_text",
                args = buildJsonObject { put("ref", "@e2"); put("snapshotId", "s_chain"); put("text", "测试文本") },
                predicate = ChainPredicateKind.TEXT_UPDATED,
                expectText = "预期不存在的文字"
            )
        )
        val chain = MacroActionChain(steps = steps, fallbackStrategy = "backtrack")

        // 步骤 1 判定通过，步骤 2 判定失败
        var callCount = 0
        val result = executor.executeChain(chain) { step ->
            callCount++
            callCount <= 1
        }

        assertFalse(result.success)
        assertTrue(result.shortCircuited)
        assertEquals(1, result.completedSteps)
        assertEquals(1, result.failedStepIndex)

        // 验证第一步执行了点击，并在第二步熔断后触发了 Back 键反向补偿
        assertTrue(screen.calls.any { it.startsWith("tapRef") })
        assertTrue(screen.calls.any { it == "back" })
    }

    @Test
    fun testActionChainToolContract() = runTest {
        val def = ActionChainTool.def
        assertEquals("action_chain", def.name)
        assertTrue(def.description.contains("35ms"))
        assertTrue(def.props.containsKey("steps"))
        assertTrue(def.props.containsKey("fallbackStrategy"))

        val execArgs = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "phone_tap")
                    put("args", buildJsonObject { put("ref", "@e1") })
                })
            })
            put("fallbackStrategy", "backtrack")
        }

        val outcome = ActionChainTool.execute(execArgs)
        assertEquals("true", outcome.content["ok"]?.jsonPrimitive?.content)
        assertEquals("1", outcome.content["stepsCount"]?.jsonPrimitive?.content)
        assertEquals("backtrack", outcome.content["strategy"]?.jsonPrimitive?.content)
    }
}

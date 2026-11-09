package com.hana.agent.core.assembly

import com.hana.agent.core.action.ActionExecutor
import com.hana.agent.core.action.ActionOutcome
import com.hana.agent.core.action.ActionRecorder
import com.hana.agent.core.action.ChainPredicateKind
import com.hana.agent.core.bus.RegisterBus
import com.hana.agent.core.bus.RegisterResolveTool
import com.hana.agent.core.bus.RegisterStoreTool
import com.hana.agent.core.cap.phone.ActionChainTool
import com.hana.agent.core.cap.phone.PhoneCapability
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 验证核心工具装配、命名空间防冲突与端侧真实执行调度
 */
class ToolAssemblyRegistrationTest {

    @Before
    fun setUp() {
        RegisterBus.clear()
    }

    private class AssemblyScreen : ScreenPort by FakeScreen() {
        val actions = mutableListOf<String>()
        var modalActive = false

        private val elements = listOf(
            UiElement("@e1", "button", "立即搜索", 100, 100, listOf(50, 50, 150, 150), null, true, false, false, focused = true),
            UiElement("@e2", "edit", "输入框", 200, 200, listOf(150, 150, 250, 250), null, true, true, false, focused = true)
        )

        override suspend fun snapshot(): ScreenSnapshot {
            val windows = if (modalActive) {
                listOf(
                    UiWindowTree(0, "app", 0, "MainApp", "com.test.app", false, false, emptyList()),
                    UiWindowTree(1, "dialog", 2, "警告弹窗", "com.test.app", true, true, emptyList())
                )
            } else {
                listOf(UiWindowTree(0, "app", 0, "MainApp", "com.test.app", true, true, emptyList()))
            }
            return ScreenSnapshot("p1", "com.test.app", elements, 1080, 2400, windows = windows)
        }

        override suspend fun peek() = snapshot()

        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            actions += "tap:$ref"
            return ActionResult.Ok(changed = true)
        }

        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
            actions += "type:$text"
            return ActionResult.Ok(changed = true)
        }

        override suspend fun back(): ActionResult {
            actions += "back"
            return ActionResult.Ok(changed = true)
        }
    }

    private suspend fun createTestExecutor(screen: AssemblyScreen): Pair<ActionExecutor, com.hana.agent.core.tool.ToolDispatcher> {
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("init_obs", "phone_observe", "{}"))
        val sink = object : RunSink { override fun event(e: RunEvent) {} }
        val executor = ActionExecutor(
            tools = tools,
            guards = OperationGuards(),
            confirmations = ConfirmationGate(),
            gate = {},
            recorder = ActionRecorder(sink)
        )
        return executor to tools
    }

    @Test
    fun phoneCapabilityExposesAllPhoneToolsSuite() {
        val allTools = PhoneCapability.allPhoneTools()
        val toolNames = allTools.map { it.name }.toSet()

        // 验证完整工具套件已注册
        assertTrue("必须包含 action_chain 投机宏动作链工具", toolNames.contains("action_chain"))
        assertTrue("必须包含 register_store 语义寄存器存储工具", toolNames.contains("register_store"))
        assertTrue("必须包含 register_resolve 语义寄存器解析工具", toolNames.contains("register_resolve"))
        assertTrue("必须包含基础 phone_tap 核心操作工具", toolNames.contains("phone_tap"))
        assertTrue("必须包含基础 phone_observe 核心操作工具", toolNames.contains("phone_observe"))

        // 验证实例属性一致暴露
        val instance = PhoneCapability(FakeScreen(), deepLinks = true)
        val instanceNames = instance.allPhoneTools.map { it.name }.toSet()
        assertEquals(toolNames, instanceNames)
    }

    @Test
    fun noDuplicatePhoneGroupClassInToolPackage() {
        // 彻底验证 core.tool 包下不存在冲突的 PhoneGroup 类
        try {
            Class.forName("com.hana.agent.core.tool.PhoneGroup")
            org.junit.Assert.fail("com.hana.agent.core.tool.PhoneGroup 必须被物理彻底移除以消解命名冲突")
        } catch (_: ClassNotFoundException) {
            // 符合预期
        }
    }

    @Test
    fun actionChainToolDispatchesRealActionsThroughActionExecutor() = runTest {
        val screen = AssemblyScreen()
        val (executor, tools) = createTestExecutor(screen)

        val chainArgs = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "phone_tap")
                    put("args", buildJsonObject { put("ref", "@e1"); put("snapshotId", "p1") })
                    put("predicate", ChainPredicateKind.NONE.name)
                })
                add(buildJsonObject {
                    put("tool", "phone_type")
                    put("args", buildJsonObject { put("ref", "@e2"); put("snapshotId", "p1"); put("text", "真实执行内容") })
                    put("predicate", ChainPredicateKind.NONE.name)
                })
            })
            put("fallbackStrategy", "backtrack")
        }

        // 通过 ActionChainTool 调度 ActionExecutor.executeChain 投机执行
        val execution = ActionChainTool.execute(chainArgs, tools.ctx)
        val content = execution.content
        assertEquals("true", content["ok"]?.jsonPrimitive?.content)
        assertEquals("2", content["stepsCount"]?.jsonPrimitive?.content)
        assertEquals("2", content["totalSteps"]?.jsonPrimitive?.content)

        // 真实物理校验：底层 Screen 确实收到了点击和输入
        assertEquals(listOf("tap:@e1", "type:真实执行内容"), screen.actions)
    }

    @Test
    fun actionChainToolInterceptsModalOverlayAndRollsBack() = runTest {
        val screen = AssemblyScreen()
        screen.modalActive = true // 模拟出现弹窗遮挡
        val (executor, tools) = createTestExecutor(screen)

        val chainArgs = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "phone_tap")
                    put("args", buildJsonObject { put("ref", "@e1"); put("snapshotId", "p1") })
                })
            })
            put("fallbackStrategy", "backtrack")
        }

        val execution = ActionChainTool.execute(chainArgs, tools.ctx)
        val content = execution.content
        assertEquals("false", content["ok"]?.jsonPrimitive?.content)
        assertEquals("modal_overlay_intercepted", content["error"]?.jsonPrimitive?.content)
        assertEquals(0, screen.actions.size)
    }

    @Test
    fun registerStoreAndResolveToolIntegration() = runTest {
        val storeArgs = buildJsonObject {
            put("key", "test_order_token")
            put("value", "WX_PAY_ORDER_20261109")
        }
        val storeResult = RegisterStoreTool.execute(storeArgs)
        assertEquals("true", storeResult.content["ok"]?.jsonPrimitive?.content)
        assertEquals("\$reg:test_order_token", storeResult.content["key"]?.jsonPrimitive?.content)

        val resolveArgs = buildJsonObject {
            put("key", "test_order_token")
        }
        val resolveResult = RegisterResolveTool.execute(resolveArgs)
        assertEquals("true", resolveResult.content["ok"]?.jsonPrimitive?.content)
        assertEquals("WX_PAY_ORDER_20261109", resolveResult.content["value"]?.jsonPrimitive?.content)

        // 验证 RegisterBus 字符串内嵌解析
        val resolvedText = RegisterBus.resolve("请查收您的订单号: \$reg:test_order_token")
        assertEquals("请查收您的订单号: WX_PAY_ORDER_20261109", resolvedText)
    }

    @Test
    fun toolDispatcherAssemblesAndDispatchesActionChainEndToEnd() = runTest {
        val screen = AssemblyScreen()
        val (executor, tools) = createTestExecutor(screen)

        // 验证装配器已完整注册投机宏动作与寄存器总线工具
        assertNotNull("toolDispatcher 必须索引 action_chain", tools.index["action_chain"])
        assertNotNull("toolDispatcher 必须索引 register_store", tools.index["register_store"])
        assertNotNull("toolDispatcher 必须索引 register_resolve", tools.index["register_resolve"])

        // 验证全局声明 knownTools 完整收录
        val known = knownTools().names
        assertTrue("knownTools 必须包含 action_chain", known.contains("action_chain"))
        assertTrue("knownTools 必须包含 register_store", known.contains("register_store"))
        assertTrue("knownTools 必须包含 register_resolve", known.contains("register_resolve"))

        // 通过 toolDispatcher.execute 端到端调度 action_chain
        val chainArgs = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "phone_tap")
                    put("args", buildJsonObject { put("ref", "@e1"); put("snapshotId", "p1") })
                })
            })
            put("fallbackStrategy", "backtrack")
        }
        val execution = tools.execute(Part.ToolCall("call_ac", "action_chain", chainArgs.toString()))
        val content = execution.content
        assertEquals("true", content["ok"]?.jsonPrimitive?.content)
        assertEquals(listOf("tap:@e1"), screen.actions)
    }

    @Test
    fun actionChainBacktracksOnExecutionFailure() = runTest {
        val screen = object : ScreenPort by FakeScreen() {
            val actions = mutableListOf<String>()
            override suspend fun peek() = ScreenSnapshot("p1", "com.test.app", emptyList(), 1080, 2400)
            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                actions += "tap:$ref"
                return if (ref == "@e1") ActionResult.Ok(changed = true)
                else ActionResult.Error("tap_failed", "element missing")
            }
            override suspend fun back(): ActionResult {
                actions += "back"
                return ActionResult.Ok(changed = true)
            }
        }
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("init", "phone_observe", "{}"))
        val executor = ActionExecutor(
            tools = tools,
            guards = OperationGuards(),
            confirmations = ConfirmationGate(),
            gate = {},
            recorder = ActionRecorder(object : RunSink { override fun event(e: RunEvent) {} })
        )

        val chainArgs = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "phone_tap")
                    put("args", buildJsonObject { put("ref", "@e1"); put("snapshotId", "p1") })
                })
                add(buildJsonObject {
                    put("tool", "phone_tap")
                    put("args", buildJsonObject { put("ref", "@e2_missing"); put("snapshotId", "p1") })
                })
            })
            put("fallbackStrategy", "backtrack")
        }

        val res = ActionChainTool.execute(chainArgs, tools.ctx)
        assertFalse("步2执行失败时整个 chain 必须失败", res.content["ok"]?.jsonPrimitive?.content?.toBoolean() == true)
        // 验证执行了步1 (@e1)，接着步2失败触发了反向 back 补偿
        assertEquals(listOf("tap:@e1", "tap:@e2_missing", "back"), screen.actions)
    }

    @Test
    fun actionChainToolRejectsWhenNoExecutorAvailable() = runTest {
        val chainArgs = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "phone_tap")
                    put("args", buildJsonObject { put("ref", "@e1") })
                })
            })
        }
        val res = ActionChainTool.execute(chainArgs, null)
        assertEquals("false", res.content["ok"]?.jsonPrimitive?.content)
        assertEquals("chain_executor_not_available", res.content["error"]?.jsonPrimitive?.content)
    }
}

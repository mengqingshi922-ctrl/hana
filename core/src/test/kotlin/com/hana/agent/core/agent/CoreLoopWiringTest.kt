package com.hana.agent.core.agent

import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.cap.phone.ActionChainTool
import com.hana.agent.core.eval.EvalTask
import com.hana.agent.core.model.Author
import com.hana.agent.core.model.Capabilities
import com.hana.agent.core.model.ChatModel
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.ModelReply
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import com.hana.agent.core.model.StopReason
import com.hana.agent.core.model.ToolSpec
import com.hana.agent.core.model.Usage
import com.hana.agent.core.record.RunEvent
import com.hana.agent.core.record.RunSink
import com.hana.agent.core.tool.FakeScreen
import com.hana.agent.core.uistate.ActionResult
import com.hana.agent.core.uistate.ScreenPort
import com.hana.agent.core.uistate.ScreenSnapshot
import com.hana.agent.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

private class MockWiringChatModel(
    override val name: String,
    toolCalls: Boolean = true,
    vision: Boolean = false,
) : ChatModel {
    override val capabilities = Capabilities(
        toolCalls = toolCalls,
        vision = vision,
        contextTokens = 64_000,
        reasoning = false,
    )

    var replyGenerator: ((List<Message>) -> ModelReply)? = null

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val generator = replyGenerator
        if (generator != null) {
            return generator(messages)
        }
        return ModelReply(
            parts = listOf(Part.Text("Mock complete response")),
            usage = Usage(10, 10),
            stopReason = StopReason.EndTurn,
        )
    }
}

/**
 * 验证 ActionChainTool 紧凑 DSL 开关门控、ModelCaller 置信度路由主模型回退，
 * 以及 AgentLoop 步后反思与失败轨迹导出。
 */
class CoreLoopWiringTest {

    @Test
    fun testActionChainToolCompactDslGating() = runTest {
        val fakeScreen = FakeScreen()
        val toolsDisabled = toolDispatcher(fakeScreen, enableCompactDsl = false)
        val compactOnlyArgs = buildJsonObject {
            put("compact", "t(@e1)")
        }

        // 1. enableCompactDsl = false 时拒绝纯 compact 格式并返回 compact_dsl_disabled
        val resultDisabled = ActionChainTool.execute(compactOnlyArgs, toolsDisabled.ctx)
        val okDisabled = resultDisabled.content["ok"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true
        assertFalse("enableCompactDsl=false 应拒绝纯 compact 参数", okDisabled)
        assertEquals("compact_dsl_disabled", resultDisabled.content["error"]?.jsonPrimitive?.contentOrNull)

        // 2. enableCompactDsl = true 时正常解析与执行
        val toolsEnabled = toolDispatcher(fakeScreen, enableCompactDsl = true)
        toolsEnabled.ctx.chainExecutor = { chain ->
            com.hana.agent.core.action.MacroChainResult(
                success = true,
                completedSteps = chain.steps.size,
                totalSteps = chain.steps.size,
            )
        }
        val resultEnabled = ActionChainTool.execute(compactOnlyArgs, toolsEnabled.ctx)
        val okEnabled = resultEnabled.content["ok"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        assertTrue("enableCompactDsl=true 应允许执行 compact 参数", okEnabled)
        assertEquals(1, resultEnabled.content["stepsCount"]?.jsonPrimitive?.contentOrNull?.toInt())

        // 3. enableCompactDsl = false 时，如果提供了 steps 列表，降级解析 steps
        val fallbackArgs = buildJsonObject {
            put("compact", "t(@e1)")
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "phone_tap")
                    put("args", buildJsonObject { put("ref", "@e1") })
                })
            })
        }
        toolsDisabled.ctx.chainExecutor = { chain ->
            com.hana.agent.core.action.MacroChainResult(
                success = true,
                completedSteps = chain.steps.size,
                totalSteps = chain.steps.size,
            )
        }
        val resultFallback = ActionChainTool.execute(fallbackArgs, toolsDisabled.ctx)
        val okFallback = resultFallback.content["ok"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        assertTrue("存在 steps 时降级解析 steps", okFallback)
    }

    @Test
    fun testModelCallerConfidenceRoutingMainFallback() = runTest {
        val cloudMain = MockWiringChatModel("cloud-main", toolCalls = true, vision = true)
        val lightweight = MockWiringChatModel("lightweight", toolCalls = true, vision = false)
        val router = ModelRouter(
            slots = ModelSlots(
                chat = cloudMain,
                visionAction = cloudMain,
                edgeReflex = lightweight,
            ),
        )
        val events = mutableListOf<RunEvent>()
        val flags = SpeedFlags(lightSteps = true)
        val caller = ModelCaller(
            model = cloudMain,
            tools = toolDispatcher(FakeScreen()),
            router = router,
            flags = flags,
            retry = RetryPolicy.NONE,
            emit = { events += it },
            now = { 1000L },
        )

        // 场景 A: 包含空的无障碍节点树 ("nodes":[]) -> 触发 tree_quality_poor 回退主模型
        val poorTreeHistory = listOf(
            Message(Role.User, listOf(Part.Text("打开音乐")), Author.User),
            Message(Role.Assistant, listOf(Part.ToolCall("call_obs", "phone_observe", "{}")), Author.Hana),
            Message(Role.Tool, listOf(Part.ToolResult("call_obs", """{"ok":true,"windows":[{"nodes":[]}]}""", false)), Author.Hana),
        )
        val trackA = StepTrack()
        trackA.after(com.hana.agent.core.tool.Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.music")
        val outcomeA = caller.routed(poorTreeHistory, emptyList(), trackA, step = 2)
        assertEquals("cloud-main", outcomeA.used.name)
        val treePoorEvent = events.filterIsInstance<RunEvent.State>().firstOrNull { it.name == "confidence_route_main" }
        assertNotNull("应发出 confidence_route_main 事件", treePoorEvent)
        assertEquals("tree_quality_poor", treePoorEvent?.detail)

        // 场景 B: 正常连续步且界面有节点树 -> 允许走轻量模型
        events.clear()
        val healthyLight = MockWiringChatModel("lightweight", toolCalls = true, vision = false).apply {
            replyGenerator = {
                ModelReply(
                    parts = listOf(Part.ToolCall("c_light", "phone_wait", """{"seconds":1}""")),
                    usage = Usage(5, 5),
                    stopReason = StopReason.ToolUse,
                )
            }
        }
        val healthyRouter = ModelRouter(
            slots = ModelSlots(
                chat = cloudMain,
                visionAction = cloudMain,
                edgeReflex = healthyLight,
            ),
        )
        val healthyCaller = ModelCaller(
            model = cloudMain,
            tools = toolDispatcher(FakeScreen()),
            router = healthyRouter,
            flags = flags,
            retry = RetryPolicy.NONE,
            emit = { events += it },
            now = { 1000L },
        )
        val healthyHistory = listOf(
            Message(Role.User, listOf(Part.Text("打开音乐")), Author.User),
            Message(Role.Assistant, listOf(Part.ToolCall("call_obs", "phone_observe", "{}")), Author.Hana),
            Message(Role.Tool, listOf(Part.ToolResult("call_obs", """{"ok":true,"windows":[{"nodes":[{"id":1,"text":"首页"}]}]}""", false)), Author.Hana),
        )
        val trackB = StepTrack()
        trackB.after(com.hana.agent.core.tool.Verb.Observe, ok = true, guard = false, changed = true, recovery = false, app = "com.music")
        trackB.after(com.hana.agent.core.tool.Verb.Observe, ok = true, guard = false, changed = true, recovery = false, app = "com.music")
        val outcomeB = healthyCaller.routed(healthyHistory, emptyList(), trackB, step = 3)
        assertEquals("lightweight", outcomeB.used.name)
        val noFallbackEvents = events.filterIsInstance<RunEvent.State>().filter { it.name == "confidence_route_main" }
        assertTrue("正常步不应产生 confidence_route_main", noFallbackEvents.isEmpty())

        // 场景 C: 上一步失败 (lastOk = false) -> 触发主模型回退
        events.clear()
        val trackC = StepTrack()
        trackC.after(com.hana.agent.core.tool.Verb.Tap, ok = false, guard = false, changed = false, recovery = false, app = "com.music")
        val outcomeC = caller.routed(healthyHistory, emptyList(), trackC, step = 3)
        assertEquals("cloud-main", outcomeC.used.name)
        val failFallbackEvent = events.filterIsInstance<RunEvent.State>().firstOrNull { it.name == "confidence_route_main" }
        assertNotNull("上步失败应产生 confidence_route_main", failFallbackEvent)
    }

    @Test
    fun testAgentLoopPostActionMismatchAndFailureTrajectory() = runTest {
        // 自定义屏幕：点击与窥屏均不引起界面变化 (changed = false)
        class StagnantScreen : ScreenPort by FakeScreen() {
            private val staticSnap = ScreenSnapshot(
                snapshotId = "s1",
                packageName = "com.demo.app",
                elements = listOf(
                    UiElement(
                        ref = "@e1",
                        role = "button",
                        name = "未改变按钮",
                        centerX = 100,
                        centerY = 100,
                        bounds = listOf(0, 0, 200, 200),
                        resourceId = null,
                        clickable = true,
                        editable = false,
                        scrollable = false,
                    )
                ),
                width = 1080,
                height = 2400,
            )
            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Ok(changed = false)
            override suspend fun tapXY(x: Int, y: Int): ActionResult = ActionResult.Ok(changed = false)
            override suspend fun snapshot(): ScreenSnapshot = staticSnap
            override suspend fun peek(): ScreenSnapshot = staticSnap
        }

        var callCount = 0
        val model = MockWiringChatModel("main-model")
        model.replyGenerator = { _ ->
            callCount++
            when (callCount) {
                1 -> ModelReply(
                    parts = listOf(Part.ToolCall("c1", "phone_observe", "{}")),
                    usage = Usage(10, 10),
                    stopReason = StopReason.ToolUse,
                )
                2 -> ModelReply(
                    parts = listOf(Part.ToolCall("c2", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}""")),
                    usage = Usage(10, 10),
                    stopReason = StopReason.ToolUse,
                )
                3 -> ModelReply(
                    parts = listOf(Part.ToolCall("c3", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}""")),
                    usage = Usage(10, 10),
                    stopReason = StopReason.ToolUse,
                )
                else -> ModelReply(
                    parts = listOf(Part.ToolCall("c4", "end_not_done", """{"message":"无法点击按钮"}""")),
                    usage = Usage(10, 10),
                    stopReason = StopReason.ToolUse,
                )
            }
        }

        val collectedEvents = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                collectedEvents += e
            }
        }

        var exportedTask: EvalTask? = null
        var exportedJson: String? = null
        val loop = AgentLoop(
            model = model,
            tools = toolDispatcher(StagnantScreen()),
            basePrompt = "BASE",
            sink = sink,
            onFailureTrajectory = { task, json ->
                exportedTask = task
                exportedJson = json
            },
        )

        val targetGoal = "为用户 13800138000 查询话费余额"
        val state = loop.run(targetGoal)
        assertTrue("任务应以失败结束", state is TaskState.Failed)

        // 1. 验证 post_action_mismatch 预期未达成事件发射
        val mismatchEvent = collectedEvents.filterIsInstance<RunEvent.State>().firstOrNull { it.name == "post_action_mismatch" }
        assertNotNull("因屏幕未变化应发射 post_action_mismatch", mismatchEvent)
        assertTrue(mismatchEvent?.detail?.contains("phone_tap") == true)

        // 2. 验证 onFailureTrajectory 回调正确触发且已脱敏
        assertNotNull("任务失败应回调 onFailureTrajectory", exportedTask)
        assertNotNull("导出的 json 应当非空", exportedJson)

        val taskResult = exportedTask!!
        assertFalse("手机号隐私信息必须脱敏", taskResult.goal.contains("13800138000"))
        assertTrue("手机号应被替换为 REDACTED", taskResult.goal.contains("{REDACTED}"))
        assertTrue("导出备注应当包含执行动作 phone_tap", taskResult.note.orEmpty().contains("phone_tap"))
        assertFalse("导出的 json 字符串中严禁泄露手机号", exportedJson!!.contains("13800138000"))
    }
}

package com.hana.agent.core.agent

import com.hana.agent.core.access.AppAccessPolicy
import com.hana.agent.core.access.AppAccessState
import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.knowledge.AppTipsStore
import com.hana.agent.core.memory.TaskNotePad
import com.hana.agent.core.model.Capabilities
import com.hana.agent.core.model.ChatModel
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.ModelReply
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.StopReason
import com.hana.agent.core.model.ToolSpec
import com.hana.agent.core.record.RunEvent
import com.hana.agent.core.record.RunSink
import com.hana.agent.core.route.SemanticRouteCache
import com.hana.agent.core.subgoal.SubgoalCheckpoint
import com.hana.agent.core.uistate.ActionResult
import com.hana.agent.core.uistate.ScreenPort
import com.hana.agent.core.uistate.ScreenSnapshot
import com.hana.agent.core.uistate.UiElement
import com.hana.agent.core.uistate.VisibleTextLine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 验证四项核心闭环优化：
 * 1. cognitive_rollback 触发时主动补发 phone_back 修复“脑屏撕裂”并注入页面差异反思；
 * 2. 成功轨迹非阻塞持久化进 SemanticRouteCache 路由图谱；
 * 3. AppTipsStore 动态记录「受阻动作 -> 脱困动作」并去重保留最新 5 条；
 * 4. TaskNotePad 自动投影至每步 [Working Memory Pad] -> [Task Notes]。
 */
class AgentLoopEvolutionAndRollbackTest {

    @Before
    fun setUp() {
        AppTipsStore.clear()
        TaskNotePad.clearAll()
    }

    @After
    fun tearDown() {
        AppTipsStore.clear()
        TaskNotePad.clearAll()
    }

    private class RecordingSink : RunSink {
        val events = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) {
            events += e
        }
        override fun media(mediaId: Int, png: ByteArray) {}
    }

    private class ScriptedChatModel(
        private val onComplete: (stepIdx: Int, messages: List<Message>) -> ModelReply,
    ) : ChatModel {
        override val capabilities = Capabilities(
            toolCalls = true,
            vision = false,
            contextTokens = 64_000,
            reasoning = false,
        )
        val receivedMessages = mutableListOf<List<Message>>()
        private var step = 0

        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            receivedMessages += messages
            return onComplete(step++, messages)
        }
    }

    private class EvolvableScreen(
        private var pkg: String = "com.launcher",
        private var screenLabel: String = "桌面",
        var tapTransitionsTo: Pair<String, String>? = null,
        var scrollTransitionsTo: Pair<String, String>? = null,
        var backScript: ArrayDeque<Pair<String, String>> = ArrayDeque(),
    ) : ScreenPort {
        private var snapSeq = 0
        var latestSnapshotId: String = "snap-0"
            private set
        var backCalls = 0
            private set

        override val appAccess: AppAccessPolicy = AppAccessPolicy { AppAccessState(revision = 1L) }

        override suspend fun snapshot(): ScreenSnapshot {
            snapSeq++
            latestSnapshotId = "snap-$snapSeq"
            val elements = listOf(
                UiElement(
                    ref = "@e1",
                    role = "button",
                    name = "按钮A",
                    centerX = 300,
                    centerY = 500,
                    bounds = listOf(100, 440, 500, 560),
                    resourceId = "id/main_btn",
                    clickable = true,
                    editable = false,
                    scrollable = false,
                ),
                UiElement(
                    ref = "@e2",
                    role = "button",
                    name = "按钮B",
                    centerX = 300,
                    centerY = 200,
                    bounds = listOf(100, 160, 500, 240),
                    resourceId = "id/sub_btn",
                    clickable = true,
                    editable = false,
                    scrollable = false,
                ),
                UiElement(
                    ref = "@e3",
                    role = "text",
                    name = "$screenLabel($pkg)",
                    centerX = 300,
                    centerY = 100,
                    bounds = listOf(100, 70, 500, 130),
                    resourceId = "id/title",
                    clickable = false,
                    editable = false,
                    scrollable = false,
                ),
            )
            return ScreenSnapshot(latestSnapshotId, pkg, elements, 1080, 2400)
        }

        override suspend fun peek(): ScreenSnapshot = snapshot()
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun readVisibleText(): List<VisibleTextLine> = emptyList()
        override suspend fun isProtected(): Boolean = false

        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            tapTransitionsTo?.let { (nextPkg, nextLabel) ->
                pkg = nextPkg
                screenLabel = nextLabel
                tapTransitionsTo = null
            }
            return ActionResult.Ok()
        }

        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()

        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
            scrollTransitionsTo?.let { (nextPkg, nextLabel) ->
                pkg = nextPkg
                screenLabel = nextLabel
                scrollTransitionsTo = null
            }
            return ActionResult.Ok()
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()

        override suspend fun back(): ActionResult {
            backCalls++
            if (backScript.isNotEmpty()) {
                val (nextPkg, nextLabel) = backScript.removeFirst()
                pkg = nextPkg
                screenLabel = nextLabel
            }
            return ActionResult.Ok()
        }

        override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()

        override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
            pkg = packageName
            screenLabel = "商品列表"
            return ActionResult.Ok()
        }

        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = listOf("com.shop.app" to "商城")
    }

    @Test
    fun `cognitive rollback realigns physical page with phone_back and records discrepancy in reflection`() = runTest {
        val screen = EvolvableScreen(
            // 模拟多层广告弹窗：TIER_1/TIER_2 各退一层仍在 com.ads.popup 内，触发认知回滚后补发 phone_back 真正退回 com.shop.app
            backScript = ArrayDeque(
                listOf(
                    "com.ads.popup.interstitial" to "广告弹窗层2",
                    "com.ads.popup.interstitial" to "广告弹窗层1",
                    "com.shop.app" to "商品列表",
                ),
            ),
        )
        val tools = toolDispatcher(screen, inlineScreen = true)
        val sink = RecordingSink()
        lateinit var loop: AgentLoop
        var checkpointSaved = false
        var latestSnapId = "p1"
        var knownRefs = listOf("@e1", "@e2")
        val snapRegex = Regex(""""snapshotId"\s*:\s*"([^"]+)"""")
        val buttonRefRegex = Regex(""""ref"\s*:\s*"([^"]+)"[^}]*"role"\s*:\s*"button"""")

        val model = ScriptedChatModel { stepIdx, messages ->
            val lastToolText = messages.asReversed()
                .flatMap { it.parts }
                .filterIsInstance<Part.ToolResult>()
                .firstOrNull()
                ?.content
                .orEmpty()
            snapRegex.findAll(lastToolText).lastOrNull()?.groupValues?.get(1)?.let { latestSnapId = it }
            val parsedRefs = buttonRefRegex.findAll(lastToolText).map { it.groupValues[1] }.toList().distinct()
            if (parsedRefs.size >= 2) {
                knownRefs = parsedRefs.take(2)
            } else if (parsedRefs.isNotEmpty()) {
                knownRefs = listOf(parsedRefs.last(), knownRefs.last())
            }
            val allTextInPrompt = messages.flatMap { it.parts }
                .filterIsInstance<Part.Text>()
                .joinToString("\n") { it.text }
            if (stepIdx >= 2 && allTextInPrompt.contains("已自动补发")) {
                return@ScriptedChatModel ModelReply(listOf(Part.Text("已通过认知回滚重对齐回到商城页面")), StopReason.EndTurn, null)
            }
            when (stepIdx) {
                0 -> toolReply("c1", "phone_launch_app", """{"package":"com.shop.app"}""")
                1 -> {
                    if (!checkpointSaved) {
                        checkpointSaved = true
                        // 此时 history 已有开局消息 + step1 模型回复/工具结果，记录语义断点在 com.shop.app
                        loop.checkpoints.saveCheckpoint(
                            SubgoalCheckpoint(
                                checkpointId = "cp_shop_home",
                                subgoalName = "进入商城商品列表",
                                packageName = "com.shop.app",
                                uiSnapshotHash = 100L,
                                historyMessageCount = messages.size,
                            ),
                        )
                    }
                    // 点击误跳入广告弹窗应用
                    screen.tapTransitionsTo = "com.ads.popup.interstitial" to "广告弹窗层1"
                    toolReply("c2", "phone_tap", """{"ref":"${knownRefs[0]}","snapshotId":"$latestSnapId"}""")
                }
                in 2..15 -> {
                    // 交替点击两控件（无变化且不触发 navigationDrift），累计 stagnationWindow=4 后连挫 2 次触发 cognitive_rollback
                    val targetRef = knownRefs[stepIdx % knownRefs.size]
                    toolReply("c_${stepIdx + 1}", "phone_tap", """{"ref":"$targetRef","snapshotId":"$latestSnapId"}""")
                }
                else -> ModelReply(listOf(Part.Text("已通过认知回滚重对齐回到商城页面")), StopReason.EndTurn, null)
            }
        }

        loop = AgentLoop(
            model = model,
            tools = tools,
            basePrompt = "你是手机助手。",
            maxSteps = 18,
            sink = sink,
            reviewFinalAnswer = false,
            installedApps = { listOf("com.shop.app" to "商城") },
        )

        val end = loop.run("进入商城查看商品")
        assertTrue(
            "任务应顺利结束: $end; stateEvents=${sink.events.filterIsInstance<RunEvent.State>().map { "${it.name}:${it.detail}" }}",
            end is TaskState.Succeeded,
        )

        val rollbackEvent = sink.events
            .filterIsInstance<RunEvent.State>()
            .firstOrNull { it.name == "cognitive_rollback" }
        assertNotNull(
            "必须触发 cognitive_rollback 事件; stateEvents=${sink.events.filterIsInstance<RunEvent.State>().map { "${it.name}:${it.detail}" }}",
            rollbackEvent,
        )
        assertTrue("cognitive_rollback 必须记录补发 phone_back 次数: ${rollbackEvent!!.detail}", rollbackEvent.detail.contains("back=1"))
        assertTrue("总共应包含 TIER_1 + TIER_2 + 认知回滚补发的 3 次 back 调用", screen.backCalls >= 3)

        // 检查注入给下一轮模型的 watchdog_reflection 消息是否包含页面对比说明
        val lastMessagesText = model.receivedMessages.last()
            .flatMap { it.parts.filterIsInstance<Part.Text>() }
            .joinToString("\n") { it.text }
        assertTrue("反思提示应明确包含当前物理页面与预期 Checkpoint 信息: $lastMessagesText",
            lastMessagesText.contains("Checkpoint[进入商城商品列表]") && lastMessagesText.contains("com.shop.app"))
    }

    @Test
    fun `successful mobile trajectory persists RouteGraph into SemanticRouteCache`() = runTest {
        val cacheFile = File.createTempFile("route-graph-e2e", ".json").apply { deleteOnExit() }
        val routeCache = SemanticRouteCache(file = cacheFile)
        val screen = EvolvableScreen(
            tapTransitionsTo = "com.shop.app" to "我的订单",
        )
        val tools = toolDispatcher(screen)
        val model = ScriptedChatModel { stepIdx, _ ->
            when (stepIdx) {
                0 -> toolReply("r1", "phone_launch_app", """{"package":"com.shop.app"}""")
                1 -> toolReply("r2", "phone_tap", """{"ref":"@e1","snapshotId":"snap-2"}""")
                else -> ModelReply(listOf(Part.Text("已打开我的订单页")), StopReason.EndTurn, null)
            }
        }

        val loop = AgentLoop(
            model = model,
            tools = tools,
            basePrompt = "你是手机助手。",
            maxSteps = 8,
            reviewFinalAnswer = false,
            semanticRouteCache = routeCache,
            installedApps = { listOf("com.shop.app" to "商城") },
        )

        val end = loop.run("打开商城查看我的订单")
        assertTrue(end is TaskState.Succeeded)
        assertEquals("成功完成手机轨迹后应持久化 1 张路由图谱", 1, routeCache.routeGraphCount())

        val hit = routeCache.lookupRouteGraph("打开商城查看我的订单", appId = "com.shop.app")
        assertNotNull("后续相同意图查询 SemanticRouteCache 必须命中已存路由图谱", hit)
        val toolsInGraph = hit!!.graph.edges.map { it.action.tool }
        assertTrue(toolsInGraph.contains("phone_launch_app"))
        assertTrue(toolsInGraph.contains("phone_tap"))
        assertTrue(hit.graph.nodes.values.any { it.isTerminal })
    }

    @Test
    fun `AppTipsStore records dynamic blocked-to-recovery tips and deduplicates latest 5`() = runTest {
        // 1. 直接验证 AppTipsStore 去重与最新 5 条容量限制
        val pkg = "com.example.dedupe"
        AppTipsStore.recordStructuredTip(pkg, "phone_tap(立即购买)", "phone_scroll(down)")
        AppTipsStore.recordStructuredTip(pkg, "phone_tap(立即购买)", "phone_scroll(down)")
        assertEquals(1, AppTipsStore.getTips(pkg).size)

        for (i in 1..6) {
            AppTipsStore.recordStructuredTip(pkg, "action_fail_$i", "action_ok_$i")
        }
        val stored = AppTipsStore.getTips(pkg)
        assertEquals("每个 App 最多保留最新 5 条高质量 Tip", 5, stored.size)
        assertTrue("最新一条应排在最前面供 take(2) 优先读取", stored.first().contains("action_fail_6") && stored.first().contains("action_ok_6"))
        assertFalse("最早的旧经验应被淘汰", stored.any { it.contains("phone_tap(立即购买)") })

        // 2. 端到端验证 AgentLoop 遇阻后改用新动作脱困时写入动态经验，而非静态废话
        val screen = EvolvableScreen(
            scrollTransitionsTo = "com.shop.app" to "结算确认页",
        )
        val tools = toolDispatcher(screen)
        val model = ScriptedChatModel { stepIdx, _ ->
            when (stepIdx) {
                0 -> toolReply("t1", "phone_launch_app", """{"package":"com.shop.app"}""")
                1 -> toolReply("t2", "phone_tap", """{"ref":"@e1","snapshotId":"snap-2"}""") // unchanged -> 受阻
                2 -> toolReply("t3", "phone_scroll", """{"direction":"down"}""") // changed -> 脱困成功
                else -> ModelReply(listOf(Part.Text("已向下滚动进入结算确认页")), StopReason.EndTurn, null)
            }
        }

        val loop = AgentLoop(
            model = model,
            tools = tools,
            basePrompt = "你是手机助手。",
            maxSteps = 8,
            reviewFinalAnswer = false,
            installedApps = { listOf("com.shop.app" to "商城") },
        )
        val end = loop.run("打开商城 -> 进入结算页面")
        assertTrue(end is TaskState.Succeeded)

        val shopTips = AppTipsStore.getTips("com.shop.app")
        assertTrue("应记录动态脱困 Tip", shopTips.isNotEmpty())
        val tip = shopTips.first()
        assertFalse("禁止继续写入旧静态废话", tip.contains("曾在此界面遇阻，换用其他动作或等待刷新可脱困"))
        assertTrue("动态 Tip 应同时包含受阻动作与脱困动作: $tip", tip.contains("phone_tap") && tip.contains("phone_scroll"))

        // 验证下一轮请求中的 [App Tips] 已自动注入该经验
        val lastStepText = model.receivedMessages.last()
            .flatMap { it.parts.filterIsInstance<Part.Text>() }
            .joinToString("\n") { it.text }
        assertTrue(lastStepText.contains("[App Tips]") && lastStepText.contains("phone_scroll"))
    }

    @Test
    fun `TaskNotePad entries automatically project into Working Memory Pad Task Notes`() = runTest {
        val convId = "conv-notepad-projection-test"
        val notePad = TaskNotePad.getForRun(convId)
        notePad.append("比价记录", "旗舰店", "¥4999 包邮")

        val screen = EvolvableScreen()
        val tools = toolDispatcher(screen, conversationId = convId)
        val model = ScriptedChatModel { _, _ ->
            ModelReply(listOf(Part.Text("已完成比价汇总：旗舰店 ¥4999")), StopReason.EndTurn, null)
        }

        val loop = AgentLoop(
            model = model,
            tools = tools,
            basePrompt = "你是手机助手。",
            maxSteps = 4,
            reviewFinalAnswer = false,
        )

        val end = loop.run("帮我总结刚才记录的价格")
        assertTrue(end is TaskState.Succeeded)

        val promptTexts = model.receivedMessages.first()
            .flatMap { it.parts.filterIsInstance<Part.Text>() }
            .joinToString("\n") { it.text }
        assertTrue("即使 workingMemory/subgoals 为空，TaskNotePad 非空也必须激活 [Working Memory Pad]: $promptTexts",
            promptTexts.contains("[Working Memory Pad]"))
        assertTrue("运行时状态必须自动投影 [Task Notes] 段: $promptTexts",
            promptTexts.contains("[Task Notes]") && promptTexts.contains("比价记录") && promptTexts.contains("¥4999 包邮"))
    }

    private fun toolReply(id: String, name: String, argsJson: String): ModelReply =
        ModelReply(listOf(Part.ToolCall(id, name, argsJson)), StopReason.ToolUse, null)
}

package com.hana.agent.core.replay

import com.hana.agent.core.action.ActionExecutor
import com.hana.agent.core.action.ActionRecorder
import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.edge.EdgeDecisionPort
import com.hana.agent.core.gate.ConfirmationGate
import com.hana.agent.core.guard.OperationGuards
import com.hana.agent.core.record.RunEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayerHealingTest {

    private fun memoryStore(initial: Route? = null): RouteStore = object : RouteStore {
        private val map = linkedMapOf<String, Route>().apply { initial?.let { put(it.id, it) } }
        override fun all(): List<Route> = map.values.toList()
        override fun put(r: Route) { map[r.id] = r }
        override fun remove(id: String) { map.remove(id) }
        override fun clear() { map.clear() }
        override fun count(): Int = map.size
    }

    @Test
    fun healsShiftedAndRenamedSelectorInPlaceAndContinuesZeroTokenReplay() = runTest {
        val page1A = Anchors("com.shop", listOf("商品详情", "规格参数"))
        val page2A = Anchors("com.shop", listOf("确认订单", "合计"))
        val page3A = Anchors("com.shop", listOf("下单成功"))

        // 历史录制：button "立即购买" @ [100, 300, 400, 380], rid="btn_buy"
        val steps = listOf(
            RouteStep(
                tool = "phone_tap",
                argsJson = "{}",
                target = Selector("button", "立即购买", "com.shop:id/btn_buy", listOf(100, 300, 400, 380), 0, uniqueAtRecord = true),
                before = page1A,
                after = page2A,
            ),
            RouteStep(
                tool = "phone_tap",
                argsJson = "{}",
                target = Selector("button", "提交订单", "com.shop:id/btn_submit", listOf(600, 900, 950, 980), 0, uniqueAtRecord = true),
                before = page2A,
                after = page3A,
            ),
        )
        val route = Route("r_heal_1", "购买商品", 0, listOf("com.shop"), emptyMap(), steps, lastUsedMs = 100L)
        val store = memoryStore(route)

        // 当前版本：第一步控件位置下移超过 NEAR(150) 到达 y=540（中心距 240），role 变成 text，rid 变成 btn_buy_new，文案变成 "立即购买(包邮)"
        val page1 = listOf(
            ui("@e1", "text", "商品详情", listOf(0, 0, 999, 60), clickable = false),
            ui("@e2", "text", "规格参数", listOf(0, 60, 999, 120), clickable = false),
            ui("@e3", "text", "立即购买(包邮)", listOf(110, 540, 410, 620), rid = "com.shop:id/btn_buy_new", clickable = true),
        )
        val page2 = listOf(
            ui("@e4", "text", "确认订单", listOf(0, 0, 999, 60), clickable = false),
            ui("@e5", "text", "合计", listOf(0, 60, 999, 120), clickable = false),
            ui("@e6", "button", "提交订单", listOf(600, 900, 950, 980), rid = "com.shop:id/btn_submit", clickable = true),
        )
        val page3 = listOf(
            ui("@e7", "text", "下单成功", listOf(0, 0, 999, 100), clickable = false),
        )

        val screen = PagedScreen(mapOf("p1" to page1, "p2" to page2, "p3" to page3), start = "p1").apply {
            pkg = "com.shop"
            links["p1" to "立即购买(包邮)"] = "p2"
            links["p2" to "提交订单"] = "p3"
        }

        val dispatcher = toolDispatcher(screen).also { it.captureTargets = true }
        val sink = Events()
        // 不提供任何确认门控拦截以便验证 Zero-Token 连续执行完两步
        val executor = ActionExecutor(dispatcher, OperationGuards(), null, {}, ActionRecorder(sink))
        val replayer = Replayer(
            tools = dispatcher,
            executor = executor,
            gate = {},
            sink = sink,
            clock = { 999L },
            edgePort = EdgeDecisionPort.NONE,
            routeStore = store,
        )

        val outcome = replayer.run(RouteChoice(route, emptyList()))
        assertEquals(ReplayOutcome.Completed(2), outcome)
        assertEquals(1, replayer.healedStepCount)
        assertEquals(listOf("立即购买(包邮)", "提交订单"), screen.taps)

        // 验证第一步的目标选择器与边锚点已热更新写入 RouteStore
        val persisted = store.all().single()
        assertNotNull(replayer.lastHealedRoute)
        assertEquals("com.shop:id/btn_buy_new", persisted.steps[0].target?.resourceId)
        assertEquals("text", persisted.steps[0].target?.role)
        assertTrue(sink.all.filterIsInstance<RunEvent.Replay>().first().detail.contains("[healed:"))
    }

    @Test
    fun hotUpdatesEdgeAnchorWeightsAndSurvivesStaleAnchorDrop() = runTest {
        // 初始有 3 个锚点："旧标题"、"商品列表"、"筛选"
        val beforeA = Anchors("com.mall", listOf("旧标题", "商品列表", "筛选"))
        val afterA = Anchors("com.mall", listOf("详情页"))
        val step = RouteStep(
            tool = "phone_tap",
            argsJson = "{}",
            target = Selector("button", "进入详情", "com.mall:id/enter", listOf(100, 200, 400, 300), 0, uniqueAtRecord = true),
            before = beforeA,
            after = afterA,
        )
        val initialRoute = Route("r_anchor", "进入商品详情", 0, listOf("com.mall"), emptyMap(), listOf(step), lastUsedMs = 1L)
        val store = memoryStore(initialRoute)

        // 第一次回放："旧标题"已从界面消失，但"商品列表"和"筛选"在（2/3 命中），触发热更新：
        // "商品列表"与"筛选"权重升至 1.25，"旧标题"权重衰减至 0.70
        val p1Run1 = listOf(
            ui("@e1", "text", "商品列表", listOf(0, 0, 500, 60), clickable = false),
            ui("@e2", "text", "筛选", listOf(500, 0, 999, 60), clickable = false),
            ui("@e3", "button", "进入详情", listOf(100, 200, 400, 300), rid = "com.mall:id/enter"),
        )
        val p2 = listOf(ui("@e4", "text", "详情页", listOf(0, 0, 999, 60), clickable = false))
        val screen1 = PagedScreen(mapOf("p1" to p1Run1, "p2" to p2), start = "p1").apply {
            pkg = "com.mall"
            links["p1" to "进入详情"] = "p2"
        }
        val d1 = toolDispatcher(screen1).also { it.captureTargets = true }
        val sink1 = Events()
        val r1 = Replayer(d1, ActionExecutor(d1, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink1)), {}, sink1, routeStore = store)
        assertEquals(ReplayOutcome.Completed(1), r1.run(RouteChoice(initialRoute, emptyList())))

        val updatedRoute = store.all().single()
        val updatedWeights = updatedRoute.steps[0].before.weights
        assertTrue((updatedWeights["商品列表"] ?: 0f) > 1.0f)
        assertTrue((updatedWeights["筛选"] ?: 0f) > 1.0f)
        assertTrue((updatedWeights["旧标题"] ?: 1f) < 1.0f)

        // 再热更新几轮使"商品列表"达到较高权重、"旧标题"和"筛选"权重衰减
        val weightedAnchors = Anchors(
            packageName = "com.mall",
            texts = listOf("旧标题", "商品列表", "筛选"),
            weights = mapOf("旧标题" to 0.2f, "筛选" to 0.3f, "商品列表" to 1.8f),
        )
        val weightedRoute = updatedRoute.copy(steps = listOf(step.copy(before = weightedAnchors)))
        // 第二次回放页面只保留了高权重的 "商品列表"（1/3 文本，但占总权重 1.8/2.3 >= 50%）
        val p1Run2 = listOf(
            ui("@e1", "text", "商品列表", listOf(0, 0, 500, 60), clickable = false),
            ui("@e3", "button", "进入详情", listOf(100, 200, 400, 300), rid = "com.mall:id/enter"),
        )
        val screen2 = PagedScreen(mapOf("p1" to p1Run2, "p2" to p2), start = "p1").apply {
            pkg = "com.mall"
            links["p1" to "进入详情"] = "p2"
        }
        val d2 = toolDispatcher(screen2).also { it.captureTargets = true }
        val sink2 = Events()
        val r2 = Replayer(d2, ActionExecutor(d2, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink2)), {}, sink2, routeStore = store)
        assertEquals(ReplayOutcome.Completed(1), r2.run(RouteChoice(weightedRoute, emptyList())))
    }

    @Test
    fun doesNotGuessWhenTwoHealedCandidatesHaveAmbiguousScores() = runTest {
        val beforeA = Anchors("com.list", listOf("联系人"))
        val afterA = Anchors("com.list", listOf("聊天页"))
        val step = RouteStep(
            tool = "phone_tap",
            argsJson = "{}",
            target = Selector("button", "发送消息", "com.list:id/btn_msg", listOf(100, 400, 400, 480), 0, uniqueAtRecord = true),
            before = beforeA,
            after = afterA,
        )
        val route = Route("r_ambig", "发消息", 0, listOf("com.list"), emptyMap(), listOf(step), lastUsedMs = 0L)

        // 两个高度相似且紧邻的改版按钮同时存在，得分差小于安全阈值，应拒绝对齐并 Diverged
        val page = listOf(
            ui("@e1", "text", "联系人", listOf(0, 0, 999, 60), clickable = false),
            ui("@e2", "text", "发送信息", listOf(100, 410, 400, 490), rid = "com.list:id/msg_1", clickable = true),
            ui("@e3", "text", "发送信息", listOf(100, 420, 400, 500), rid = "com.list:id/msg_2", clickable = true),
        )
        val screen = PagedScreen(mapOf("p1" to page), start = "p1").apply { pkg = "com.list" }
        val d = toolDispatcher(screen).also { it.captureTargets = true }
        val sink = Events()
        val replayer = Replayer(d, ActionExecutor(d, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink)), {}, sink)

        val outcome = replayer.run(RouteChoice(route, emptyList()))
        assertTrue(outcome is ReplayOutcome.Diverged)
        assertTrue(screen.taps.isEmpty())
    }
}

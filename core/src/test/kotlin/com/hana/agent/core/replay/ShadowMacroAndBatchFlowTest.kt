package com.hana.agent.core.replay

import com.hana.agent.core.action.ActionExecutor
import com.hana.agent.core.action.ActionRecorder
import com.hana.agent.core.agent.BatchRunner
import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.gate.ConfirmationGate
import com.hana.agent.core.guard.OperationGuards
import com.hana.agent.core.record.RunEvent
import com.hana.agent.core.record.RunSink
import com.hana.agent.core.tool.FakeScreen
import com.hana.agent.core.tool.execute
import com.hana.agent.core.uistate.ActionResult
import com.hana.agent.core.uistate.ScreenPort
import com.hana.agent.core.uistate.ScreenSnapshot
import com.hana.agent.core.uistate.UiElement
import com.hana.agent.core.uistate.UiTreeNode
import com.hana.agent.core.uistate.UiWindowTree
import com.hana.agent.core.uistate.Verification
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class MacroFlowScreen : ScreenPort by FakeScreen() {
    var pkg: String? = "com.shop"
    var snapshotQueue = ArrayDeque<List<UiElement>>()
    var currentElements: List<UiElement> = emptyList()
    var currentTreeNodes: List<UiTreeNode> = emptyList()
    val actions = mutableListOf<String>()
    private var seq = 0
    var onActionAdvance: (() -> Unit)? = null

    private fun buildSnap(): ScreenSnapshot {
        val elems = if (snapshotQueue.isNotEmpty()) {
            currentElements = snapshotQueue.removeFirst()
            currentElements
        } else {
            currentElements
        }
        val nodes = if (currentTreeNodes.isNotEmpty()) {
            currentTreeNodes
        } else {
            elems.map { UiTreeNode(it.role, it.bounds, ref = it.ref, text = it.name, resourceId = it.resourceId, clickable = it.clickable) }
        }
        val win = UiWindowTree(1, "application", 0, "Shop", pkg, true, true, nodes)
        return ScreenSnapshot("s${++seq}", pkg, elems, 1080, 2400, windows = listOf(win))
    }

    override suspend fun snapshot(): ScreenSnapshot = buildSnap()
    override suspend fun peek(): ScreenSnapshot {
        val nodes = if (currentTreeNodes.isNotEmpty()) {
            currentTreeNodes
        } else {
            currentElements.map { UiTreeNode(it.role, it.bounds, ref = it.ref, text = it.name, resourceId = it.resourceId, clickable = it.clickable) }
        }
        val win = UiWindowTree(1, "application", 0, "Shop", pkg, true, true, nodes)
        return ScreenSnapshot("peek_${seq}", pkg, currentElements, 1080, 2400, windows = listOf(win))
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult {
        actions += "swipe"
        onActionAdvance?.invoke()
        return ActionResult.Ok(changed = true)
    }

    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
        val name = currentElements.firstOrNull { it.ref == ref }?.name ?: ref
        actions += "tap:$name"
        onActionAdvance?.invoke()
        return ActionResult.Ok(changed = true)
    }

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
        actions += "type:$text"
        onActionAdvance?.invoke()
        return ActionResult.Ok(verification = Verification.MATCH)
    }

    override suspend fun back(): ActionResult {
        actions += "back"
        onActionAdvance?.invoke()
        return ActionResult.Ok(changed = true)
    }
}

class ShadowMacroAndBatchFlowTest {

    private fun runner(screen: ScreenPort, clock: () -> Long = { 1000L }): BatchRunner {
        val dispatcher = toolDispatcher(screen)
        val guards = OperationGuards()
        val sink = object : RunSink { override fun event(e: RunEvent) {} }
        val recorder = ActionRecorder(sink, clock)
        return BatchRunner(dispatcher, guards, ActionExecutor(dispatcher, guards, ConfirmationGate(), {}, recorder))
    }

    @Test
    fun minesScrollUntilTextDismissPopupStackAndParameterizedSequenceMacros() {
        val a = Anchors("com.shop", listOf("首页"))
        val routes = listOf("蓝牙耳机", "机械键盘", "显示器").mapIndexed { idx, query ->
            Route(
                id = "r_$idx",
                template = "搜索{1}",
                slots = 1,
                packages = listOf("com.shop"),
                appVersions = emptyMap(),
                steps = listOf(
                    RouteStep("phone_tap", "{}", Selector("button", "跳过", "com.shop:id/skip", listOf(800, 50, 950, 110), 0, true), a, a),
                    RouteStep("phone_tap", "{}", Selector("button", "关闭", "com.shop:id/close", listOf(450, 800, 550, 880), 0, true), a, a),
                    RouteStep("phone_tap", "{}", Selector("edit", "搜索商品", "com.shop:id/search", listOf(100, 80, 800, 150), 0, true), a, a),
                    RouteStep("phone_type_text", """{"text":"$query"}""", Selector("edit", "搜索商品", "com.shop:id/search", listOf(100, 80, 800, 150), 0, true), a, a),
                    RouteStep("phone_swipe", """{"startX":500,"startY":780,"endX":500,"endY":260}""", null, a, a),
                    RouteStep("phone_swipe", """{"startX":500,"startY":780,"endX":500,"endY":260}""", null, a, a),
                    RouteStep("phone_tap", "{}", Selector("button", "商品详情", "com.shop:id/item", listOf(100, 500, 900, 700), 0, true), a, a),
                ),
                lastUsedMs = idx.toLong(),
            )
        }

        val mined = ShadowMacroMiner.mineFromRoutes(routes, minSupport = 2)
        val names = mined.map { it.name }
        assertTrue("Expected scroll_until_text in $names", "scroll_until_text" in names)
        assertTrue("Expected dismiss_popup_stack in $names", "dismiss_popup_stack" in names)
        val parameterized = mined.firstOrNull { it.params.isNotEmpty() && it.name.startsWith("seq_") }
        assertTrue("Expected parameterized sequence macro in $mined", parameterized != null)
        assertEquals(3, parameterized!!.supportCount)
    }

    @Test
    fun executesScrollUntilTextMacroAndStopsWhenTargetAppearsInUiTree() = runTest {
        val screen = MacroFlowScreen()
        var swipeCount = 0
        screen.currentElements = listOf(ui("@e1", "text", "商品列表", listOf(0, 0, 999, 80), clickable = false))
        screen.onActionAdvance = {
            swipeCount++
            if (swipeCount >= 3) {
                screen.currentElements = listOf(
                    ui("@e1", "text", "商品列表", listOf(0, 0, 999, 80), clickable = false),
                    ui("@e2", "button", "订单详情", listOf(100, 400, 500, 480)),
                )
            }
        }

        val r = runner(screen)
        val out = r.runMacro(
            macro = ShadowMacroLibrary.scrollUntilText(),
            params = mapOf("target" to "订单详情", "maxSwipes" to "6"),
            callId = "m1",
        ).content

        assertEquals("true", out["ok"]!!.jsonPrimitive.content)
        assertEquals(3, out["loopIterations"]!!.jsonPrimitive.int)
        assertEquals(3, out["completed"]!!.jsonPrimitive.int)
        assertEquals(listOf("swipe", "swipe", "swipe"), screen.actions)
    }

    @Test
    fun enforcesStrictLoopIterationLimitOnScrollUntilText() = runTest {
        val screen = MacroFlowScreen()
        screen.currentElements = listOf(ui("@e1", "text", "永远找不到", listOf(0, 0, 999, 80), clickable = false))

        val r = runner(screen)
        val out = r.runMacro(
            macro = ShadowMacroLibrary.scrollUntilText(),
            params = mapOf("target" to "目标文案", "maxSwipes" to "3"),
            callId = "m2",
        ).content

        assertEquals("false", out["ok"]!!.jsonPrimitive.content)
        assertEquals("flow_loop_limit_exceeded", out["reason"]!!.jsonPrimitive.content)
        assertEquals(3, out["completed"]!!.jsonPrimitive.int)
        assertEquals(3, screen.actions.size)
    }

    @Test
    fun executesDismissPopupStackUntilAllOverlaysCleared() = runTest {
        val screen = MacroFlowScreen()
        // 第1屏有"跳过"开屏广告，第2屏有"以后再说"活动弹窗，第3屏干净首页
        screen.currentElements = listOf(
            ui("@e1", "button", "跳过", listOf(800, 50, 960, 120)),
        )
        screen.snapshotQueue.addLast(listOf(ui("@e1", "button", "跳过", listOf(800, 50, 960, 120))))
        var stepIdx = 0
        screen.onActionAdvance = {
            stepIdx++
            if (stepIdx == 1) {
                val nextElems = listOf(ui("@e2", "button", "以后再说", listOf(400, 700, 600, 780)))
                screen.currentElements = nextElems
                screen.snapshotQueue.addLast(nextElems)
            } else {
                val homeElems = listOf(ui("@e3", "text", "首页推荐", listOf(0, 0, 999, 100), clickable = false))
                screen.currentElements = homeElems
                screen.snapshotQueue.addLast(homeElems)
            }
        }

        val r = runner(screen)
        val out = r.runMacro(
            macro = ShadowMacroLibrary.dismissPopupStack(maxPopups = 5),
            callId = "m3",
        ).content

        assertEquals("true", out["ok"]!!.jsonPrimitive.content)
        assertEquals(2, out["completed"]!!.jsonPrimitive.int)
        assertEquals(listOf("tap:跳过", "tap:以后再说"), screen.actions)
    }

    @Test
    fun executesConditionalBranchBasedOnUiTreeAndPackageName() = runTest {
        val screen = MacroFlowScreen()
        screen.pkg = "com.shop"
        screen.currentElements = listOf(
            ui("@e1", "button", "立即领券", listOf(100, 500, 400, 580)),
        )

        val flow = listOf(
            FlowNode.If(
                condition = MacroPredicate.And(
                    listOf(
                        MacroPredicate.PackageEquals("com.shop"),
                        MacroPredicate.TextContains("领券"),
                    ),
                ),
                thenBranch = listOf(FlowNode.TapMatching(listOf("立即领券"))),
                elseBranch = listOf(FlowNode.Step("phone_back", "{}")),
            ),
        )

        val out1 = runner(screen).runFlow(flow, callId = "f1").content
        assertEquals("true", out1["ok"]!!.jsonPrimitive.content)
        assertEquals(1, out1["branchesTaken"]!!.jsonPrimitive.int)
        assertEquals(listOf("tap:立即领券"), screen.actions)

        // 当包名或文案不满足条件时走 else 分支
        screen.actions.clear()
        screen.currentElements = listOf(ui("@e2", "text", "其他页面", listOf(0, 0, 500, 80), clickable = false))
        val out2 = runner(screen).runFlow(flow, callId = "f2").content
        assertEquals("true", out2["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("back"), screen.actions)
    }

    @Test
    fun enforcesGlobalStepLimitAndTimeoutBudgetInFlow() = runTest {
        val screen = MacroFlowScreen()
        screen.currentElements = listOf(ui("@e1", "text", "列表", listOf(0, 0, 999, 80), clickable = false))

        // 1. 步数硬限制
        val manySteps = List(6) { FlowNode.Step("phone_swipe", """{"startX":500,"startY":700,"endX":500,"endY":300}""") }
        val stepLimited = runner(screen).runFlow(manySteps, callId = "f_lim", maxTotalSteps = 3).content
        assertEquals("false", stepLimited["ok"]!!.jsonPrimitive.content)
        assertEquals("flow_step_limit_exceeded", stepLimited["reason"]!!.jsonPrimitive.content)
        assertEquals(3, stepLimited["completed"]!!.jsonPrimitive.int)

        // 2. 全局超时限制
        var nowMs = 10_000L
        val timeoutScreen = MacroFlowScreen().apply {
            currentElements = listOf(ui("@e1", "text", "列表", listOf(0, 0, 999, 80), clickable = false))
            onActionAdvance = { nowMs += 600L }
        }
        val timedOut = runner(timeoutScreen, clock = { nowMs }).runFlow(manySteps, callId = "f_time", maxDurationMs = 1_000L).content
        assertFalse(timedOut["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("flow_timeout_exceeded", timedOut["reason"]!!.jsonPrimitive.content)
    }
}

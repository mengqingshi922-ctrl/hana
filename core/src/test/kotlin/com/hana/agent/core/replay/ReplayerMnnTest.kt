package com.hana.agent.core.replay

import com.hana.agent.core.action.ActionExecutor
import com.hana.agent.core.action.ActionRecorder
import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.edge.EdgeDecisionPort
import com.hana.agent.core.gate.ConfirmationGate
import com.hana.agent.core.guard.OperationGuards
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayerMnnTest {

    @Test
    fun tier4MnnHealsUiLabelModifications() = runTest {
        // 历史录制时按钮文本为“筛选”，当前界面版本更新变为“高级筛选”
        val homeA = Anchors("com.taobao.taobao", listOf("商品列表"))
        val filterA = Anchors("com.taobao.taobao", listOf("筛选面板"))
        val step = RouteStep(
            tool = "phone_tap",
            argsJson = "{}",
            target = Selector("button", "筛选", "btn_filter", listOf(500, 900, 900, 1000), 0, true),
            before = homeA,
            after = filterA,
        )
        val route = Route("r_test", "测试路线", 1, listOf("com.taobao.taobao"), emptyMap(), listOf(step), lastUsedMs = 0)
        val choice = RouteChoice(route, emptyList())

        val home = listOf(
            ui("@e1", "text", "商品列表", listOf(0, 0, 999, 60), clickable = false),
            ui("@e2", "button", "高级筛选", listOf(500, 900, 900, 1000), rid = "btn_filter_v2"),
        )
        val filterPanel = listOf(
            ui("@e3", "text", "筛选面板", listOf(0, 0, 999, 60), clickable = false),
        )
        val s = PagedScreen(mapOf("home" to home, "filter_page" to filterPanel), start = "home").apply {
            pkg = "com.taobao.taobao"
            links["home" to "高级筛选"] = "filter_page"
        }

        val d = toolDispatcher(s).also { it.captureTargets = true }
        val sink = Events()

        val stubPort = object : EdgeDecisionPort {
            override val isReady: Boolean = true
            override suspend fun classifyIntent(task: String, recentContextSummary: String, confidenceThreshold: Float) = null
            override suspend fun pickObstacleDismissRef(userGoal: String, dialogClues: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun matchReplayCandidate(
                stepGoal: String,
                recordedLabel: String,
                candidates: List<EdgeDecisionPort.EdgeCandidate>,
                confidenceThreshold: Float,
            ): String? {
                // 端侧语义对齐将“筛选”成功对齐到“高级筛选”(@e2)
                return if (recordedLabel == "筛选") candidates.firstOrNull { it.label == "高级筛选" }?.ref else null
            }
            override suspend fun resolveAppFastAction(task: String, installedApps: List<Pair<String, String>>) = null
        }

        val executor = ActionExecutor(d, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink))
        val replayer = Replayer(
            tools = d,
            executor = executor,
            gate = {},
            sink = sink,
            edgePort = stubPort,
        )

        val outcome = replayer.run(choice)
        assertTrue("经验回放失败，实际结果为: $outcome", outcome is ReplayOutcome.Completed)
        assertEquals(1, (outcome as ReplayOutcome.Completed).done)
        assertEquals(listOf("高级筛选"), s.taps)
    }

    @Test
    fun tier4PrioritizesNearbyCandidatesOverDistantElements() = runTest {
        val homeA = Anchors("com.taobao.taobao", listOf("商品列表"))
        val filterA = Anchors("com.taobao.taobao", listOf("筛选面板"))
        val step = RouteStep(
            tool = "phone_tap",
            argsJson = "{}",
            target = Selector("button", "排序", "btn_sort", listOf(500, 900, 900, 1000), 0, true),
            before = homeA,
            after = filterA,
        )
        val route = Route("r_test_nearby", "测试空间就近路线", 1, listOf("com.taobao.taobao"), emptyMap(), listOf(step), lastUsedMs = 0)
        val choice = RouteChoice(route, emptyList())

        // 构造前置10个顶部的干扰控件（模拟顶部导航与状态栏）与商品列表锚点
        val homeElements = mutableListOf<com.hana.agent.core.uistate.UiElement>(
            ui("@e1", "text", "商品列表", listOf(0, 0, 999, 60), clickable = false)
        )
        for (idx in 2..11) {
            homeElements += ui("@e$idx", "button", "顶部操作_${idx - 1}", listOf(10 * (idx - 1), 0, 10 * (idx - 1) + 50, 50), rid = "top_item_$idx")
        }
        // 目标改版按钮处于页面底部，与记录位置完全一致
        homeElements += ui("@e12", "button", "综合排序", listOf(500, 900, 900, 1000), rid = "btn_sort_v2")

        val filterPanel = listOf(
            ui("@e13", "text", "筛选面板", listOf(0, 0, 999, 60), clickable = false),
        )
        val s = PagedScreen(mapOf("home" to homeElements, "filter_page" to filterPanel), start = "home").apply {
            pkg = "com.taobao.taobao"
            links["home" to "综合排序"] = "filter_page"
        }

        val d = toolDispatcher(s).also { it.captureTargets = true }
        val sink = Events()

        val stubPort = object : EdgeDecisionPort {
            override val isReady: Boolean = true
            override suspend fun classifyIntent(task: String, recentContextSummary: String, confidenceThreshold: Float) = null
            override suspend fun pickObstacleDismissRef(userGoal: String, dialogClues: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun matchReplayCandidate(
                stepGoal: String,
                recordedLabel: String,
                candidates: List<EdgeDecisionPort.EdgeCandidate>,
                confidenceThreshold: Float,
            ): String? {
                // 确保底部的 @e12 没有因为前面的 10 个无关元素被截断丢弃
                return candidates.firstOrNull { it.label == "综合排序" }?.ref
            }
            override suspend fun resolveAppFastAction(task: String, installedApps: List<Pair<String, String>>) = null
        }

        val executor = ActionExecutor(d, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink))
        val replayer = Replayer(
            tools = d,
            executor = executor,
            gate = {},
            sink = sink,
            edgePort = stubPort,
        )

        val outcome = replayer.run(choice)
        assertTrue("经验回放应借助空间就近命中底层按钮，实际结果为: $outcome", outcome is ReplayOutcome.Completed)
        assertEquals(listOf("综合排序"), s.taps)
    }
}

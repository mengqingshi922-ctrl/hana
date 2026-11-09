package com.hana.agent.core.vision

import com.hana.agent.core.agent.FakeModel
import com.hana.agent.core.model.ModelReply
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.StopReason
import com.hana.agent.core.uistate.ScreenFamily
import com.hana.agent.core.uistate.ScreenPort
import com.hana.agent.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocatorEmpiricalChallengerTest {
    private fun locator(screen: ScreenPort, model: FakeModel, registry: LocateRegistry = LocateRegistry()) =
        Locator(screen, VisionService(model, JvmTestImageOps), registry, ScreenFamily("phone", "p"))

    private fun unverified(vararg replies: String) = FakeModel(
        *replies.map { ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }.toTypedArray(),
        groundingVerified = false
    )

    private fun found(r: LocateResult) = r as? LocateResult.Found ?: throw AssertionError("expected Found, got $r")
    private fun notFound(r: LocateResult) = r as? LocateResult.NotFound ?: throw AssertionError("expected NotFound, got $r")

    /**
     * 对抗性场景 1:
     * 残缺树中存在同名目标文本，但视觉定位模型给出的 bounding box 发生了漂移 (未落在目标元素上)。
     * 预期：触发 driftDetected = true，严密阻断 strongConfidence 直通！
     * 系统强制进入 secondLook，如果 secondLook 未确认 (例如第二次视觉模型返回 null)，
     * 必须返回 LocateResult.NotFound("unverified_grounding")，绝不允许幻觉框直通！
     */
    @Test
    fun partialTree_targetInTree_driftDetected_blocksStrongConfidenceAndRequiresSecondLook() = runTest {
        // 构造残缺树：若干无名空白按钮 + 位于底部右侧的真实发送按钮 (700..900, 850..950)
        val blank = (1..4).map { UiElement("@b$it", "button", "", 0, 0, listOf(0, 500 + it * 80, 200, 560 + it * 80), null, true, false, false) }
        val targetButton = uiEl("@e9", "发送消息", listOf(700, 850, 900, 950))
        val screen = LocScreen(blank + targetButton)

        // 视觉模型给出一个漂移到屏幕顶部的框 (0..1000, 100..161)，中心为 (500, 130)，完全脱离了目标元素
        // 视觉模型返回了 seen="发送"，复看返回了 "是"，但未经验证 (groundingVerified = false)
        val driftedBox = """{"bbox_2d":[0,100,1000,161],"seen":"发送"}"""
        // 第二次核验给 null
        val model = unverified(driftedBox, "是", "null")

        val result = locator(screen, model).locate("发送按钮", "发送")
        val nf = notFound(result)
        assertEquals("unverified_grounding", nf.reason)
        // 验证系统确实发起了第 3 次请求 (触发了 secondLook，而没有在第 2 次复看时提前直通)
        assertEquals(3, model.requests.size)
    }

    /**
     * 对抗性场景 2:
     * 目标文本在树中，虽然发生漂移，但如果 secondLook 经过严密比对与二次复看完全一致，放行。
     * 预期：第二次给出互相包容的框、一致的 seen 且复看通过时，放行 Found。
     */
    @Test
    fun partialTree_driftDetected_secondConsistentLook_passes() = runTest {
        val blank = (1..4).map { UiElement("@b$it", "button", "", 0, 0, listOf(0, 500 + it * 80, 200, 560 + it * 80), null, true, false, false) }
        val targetButton = uiEl("@e9", "发送消息", listOf(700, 850, 900, 950))
        val screen = LocScreen(blank + targetButton)

        val box1 = """{"bbox_2d":[0,100,1000,161],"seen":"发送"}"""
        val box2 = """{"bbox_2d":[10,105,990,158],"seen":"发送"}"""
        // 4 次请求：box1 -> confirm1("是") -> box2 -> confirm2("是")
        val model = unverified(box1, "是", box2, "是")

        val result = locator(screen, model).locate("发送按钮", "发送")
        val f = found(result)
        assertEquals("vision", f.source)
        assertEquals(4, model.requests.size)
    }

    /**
     * 对抗性场景 3:
     * 目标文本在树中，且视觉框与控件树真实元素完全重合 (落在真实元素内部，nameChecked = true)。
     * 预期：driftDetected = false，有树上真实文本背书，一次复看后直接放行，无需触发 secondLook (仅需 2 次请求)。
     */
    @Test
    fun partialTree_visualBoundsCoincideWithTreeElement_directFoundWithoutSecondLook() = runTest {
        val blank = (1..4).map { UiElement("@b$it", "button", "", 0, 0, listOf(0, 500 + it * 80, 200, 560 + it * 80), null, true, false, false) }
        // 目标元素：位于 (700, 850) 到 (900, 950)，中心 (800, 900)
        val targetButton = UiElement("@e9", "button", "发送消息", 800, 900, listOf(700, 850, 900, 950), null, true, false, false)
        // 控件树里还有另一个同名项，避免直接走 elements 精确匹配
        val dupButton = UiElement("@e10", "button", "发送消息", 100, 200, listOf(50, 150, 150, 250), null, true, false, false)
        val screen = LocScreen(blank + targetButton + dupButton)

        // 视觉定位框准确框选了 targetButton：bbox_2d = [700, 850, 900, 950]，中心 (800, 900)
        val accurateBox = """{"bbox_2d":[700,850,900,950],"seen":"发送消息"}"""
        val model = unverified(accurateBox, "是")

        val result = locator(screen, model).locate("发送按钮", "发送")
        val f = found(result)
        assertEquals("vision", f.source)
        assertFalse("有树上文字背书，unverified 必须为 false", f.unverified)
        // 仅仅进行了 2 次模型请求 (一次 locate，一次 confirm)，没有触发 secondLook
        assertEquals(2, model.requests.size)
    }

    /**
     * 对抗性场景 4:
     * 残缺树中完全不存在目标文本 (例如自定义绘制或纯图)。
     * 预期：targetInTree = false，driftDetected = false，走 visionOnly 分支，
     * 允许放行但必须明确标记 unverified = true，防止智能体误以为获得了可靠接地。
     */
    @Test
    fun partialTree_targetNotInTree_allowedThroughAsVisionOnlyMarkedUnverified() = runTest {
        val blank = (1..4).map { UiElement("@b$it", "button", "", 0, 0, listOf(0, 500 + it * 80, 200, 560 + it * 80), null, true, false, false) }
        val targetButton = uiEl("@e9", "发送消息", listOf(700, 850, 900, 950))
        val screen = LocScreen(blank + targetButton)

        // 寻找 "表情" 按钮，该文字在树中完全不存在
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"表情"}"""
        val model = unverified(box, "是")

        val result = locator(screen, model).locate("表情按钮", "表情")
        val f = found(result)
        assertTrue("树中无佐证，必须显式标注 unverified = true", f.unverified)
        assertEquals("vision", f.source)
        assertEquals(2, model.requests.size)
    }
}

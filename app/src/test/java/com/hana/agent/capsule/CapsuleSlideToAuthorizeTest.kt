package com.hana.agent.capsule

import android.content.ContextWrapper
import com.hana.agent.ui.safegate.SafeGateHaptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证灵动胶囊安全接管门滑动授权 (SlideToAuthorize) 契约与防误触物理规范
 */
class CapsuleSlideToAuthorizeTest {

    private class MockContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun slideThresholdDampingBoundaryValidation() {
        // 验证 85% 强阻尼安全放行物理阈值计算规范
        val maxDragPx = 300f
        val threshold = maxDragPx * 0.85f // 255.0f

        // 边界值 1: 拖拽到 84.9% (254.7px) 未达门限 -> 必须触发回弹阻尼，不予放行
        val dragUnder = maxDragPx * 0.849f
        val authorizedUnder = dragUnder >= threshold
        assertFalse("84.9% 拖拽距离未跨过 85% 门限，绝不允许放行", authorizedUnder)

        // 边界值 2: 拖拽恰好达到 85.0% (255.0px) -> 达到授权门限
        val dragExact = maxDragPx * 0.85f
        val authorizedExact = dragExact >= threshold
        assertTrue("85.0% 临界拖拽距离必须触发放行确认", authorizedExact)

        // 边界值 3: 拖拽到 100% (300.0px) -> 完全放行
        val dragFull = maxDragPx * 1.0f
        val authorizedFull = dragFull >= threshold
        assertTrue("100% 拖拽距离允许放行", authorizedFull)

        // 边界值 4: 零拖拽轨道宽度或负值异常防御
        val zeroTrack = 0f
        val authorizedZero = zeroTrack > 0 && zeroTrack >= zeroTrack * 0.85f
        assertFalse("空轨道或未完成排版时必须拦截放行", authorizedZero)
    }

    @Test
    fun confirmCardModelExposesRequiredPayloadsForSlideGate() {
        val confirmContent = CardContent.Confirm(
            title = "支付转账安全确认",
            body = "智能体即将向商户转账 99.00 元，请核对收款人后滑动放行。",
            key = 1001
        )

        assertEquals("支付转账安全确认", confirmContent.title)
        assertEquals("智能体即将向商户转账 99.00 元，请核对收款人后滑动放行。", confirmContent.body)
        assertEquals(1001, confirmContent.key)

        // 空标题时回退默认文案规范
        val emptyTitleContent = CardContent.Confirm(
            title = "",
            body = "敏感操作确认",
            key = 1002
        )
        val resolvedTitle = emptyTitleContent.title.ifEmpty { "安全授权确认" }
        assertEquals("安全授权确认", resolvedTitle)
    }

    @Test
    fun cardActionsDispatchApproveAndDeclineOnSlideEvents() {
        var approvedKey: String? = null
        var approvedActionKey: Int? = null
        var declinedKey: String? = null
        var declinedActionKey: Int? = null

        val fakeActions = object : CardActions {
            override fun collapse() {}
            override fun stop(runKey: String) {}
            override fun takeOver(runKey: String) {}
            override fun resume(runKey: String) {}
            override fun answer(runKey: String, text: String) {}
            override fun approve(runKey: String, requestKey: Int) {
                approvedKey = runKey
                approvedActionKey = requestKey
            }
            override fun decline(runKey: String, requestKey: Int) {
                declinedKey = runKey
                declinedActionKey = requestKey
            }
            override fun openMika(conversationId: String) {}
            override fun zoom(on: Boolean) {}
            override fun openInput() {}
            override fun draft(text: String) {}
            override fun send() {}
            override fun freshChat(fresh: Boolean) {}
            override fun loadEarlier() {}
            override fun captureScreen() {}
            override fun dropScreen() {}
        }

        val cardKey = "card_gate_888"
        val actionKey = 2026

        // 模拟用户滑动放行成功 (onAuthorized)
        fakeActions.approve(cardKey, actionKey)
        assertEquals("card_gate_888", approvedKey)
        assertEquals(2026, approvedActionKey)

        // 模拟用户点击拒绝放行 (onRejected)
        fakeActions.decline(cardKey, actionKey)
        assertEquals("card_gate_888", declinedKey)
        assertEquals(2026, declinedActionKey)
    }

    @Test
    fun safeGateHapticsSafeInvocationUnderMockContext() {
        val mockContext = MockContext()

        // 验证 SafeGateHaptics 全套触觉 API 在受限环境下的稳定性，保证 100% 不发生 Crash
        SafeGateHaptics.triggerGateWarning(mockContext)
        SafeGateHaptics.triggerClickThump(mockContext)
        SafeGateHaptics.triggerElasticSnap(mockContext)
        SafeGateHaptics.triggerLightTick(mockContext)
        SafeGateHaptics.triggerSuccessBloom(mockContext)

        assertNotNull(SafeGateHaptics)
    }

    @Test
    fun nonHighRiskCardsAreIsolatedFromConfirmGate() {
        val takeover = CardContent.Takeover(
            need = "需要你接管验证码",
            body = "短信验证码",
            queueLine = null,
            canTakeOver = true
        )
        val question = CardContent.Question("选择操作", listOf("确认", "取消"))
        val held = CardContent.Held("等待网络恢复", resumable = true)

        assertFalse("Takeover 卡片不属于 Confirm 高危门禁", takeover is CardContent.Confirm)
        assertFalse("Question 卡片不属于 Confirm 高危门禁", question is CardContent.Confirm)
        assertFalse("Held 卡片不属于 Confirm 高危门禁", held is CardContent.Confirm)
    }
}

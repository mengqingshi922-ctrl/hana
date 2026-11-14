package com.hana.agent.core.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MadeAgents 分层反思校验与步后审计单元测试。
 */
class WatchdogPostExpectationTest {

    @Test
    fun testPostActionScreenChangeMismatch() {
        val watchdog = StateEntropyWatchdog()
        val expectation = PostActionExpectation(
            actionTool = "phone_tap",
            expectScreenChange = true,
            expectedTextTokens = emptyList(),
            expectedPackage = "com.hana.shop",
        )

        // 实际屏幕未改变
        val result = watchdog.evaluatePostAction(
            expectation = expectation,
            actualChanged = false,
            actualTexts = listOf("商品列表", "立即购买"),
            actualPackage = "com.hana.shop",
        )

        assertFalse(result.met)
        assertTrue(result.mismatchReason!!.contains("did not change as expected"))
        assertEquals(SelfHealingTier.TIER_1_DISMISS_OBSTACLE, result.suggestedRecovery)
    }

    @Test
    fun testPostActionPackageMismatch() {
        val watchdog = StateEntropyWatchdog()
        val expectation = PostActionExpectation(
            actionTool = "phone_tap",
            expectScreenChange = true,
            expectedTextTokens = emptyList(),
            expectedPackage = "com.hana.shop",
        )

        // 实际跳到了广告应用或意外切后台
        val result = watchdog.evaluatePostAction(
            expectation = expectation,
            actualChanged = true,
            actualTexts = listOf("广告详情", "点击下载"),
            actualPackage = "com.ad.tracker",
        )

        assertFalse(result.met)
        assertTrue(result.mismatchReason!!.contains("Package mismatch"))
        assertEquals(SelfHealingTier.TIER_2_SAFETY_BACK, result.suggestedRecovery)
    }

    @Test
    fun testPostActionExpectedTextMissing() {
        val watchdog = StateEntropyWatchdog()
        val expectation = PostActionExpectation(
            actionTool = "phone_tap",
            expectScreenChange = true,
            expectedTextTokens = listOf("支付成功", "订单详情"),
            expectedPackage = "com.hana.shop",
        )

        // 实际屏幕出现报错文案，缺少预期成功 token
        val result = watchdog.evaluatePostAction(
            expectation = expectation,
            actualChanged = true,
            actualTexts = listOf("余额不足", "支付失败，请重新尝试"),
            actualPackage = "com.hana.shop",
        )

        assertFalse(result.met)
        assertTrue(result.mismatchReason!!.contains("Expected text tokens not found"))
        // 由于界面包含明显错误文案，推荐对应自愈策略
        assertEquals(SelfHealingTier.TIER_1_DISMISS_OBSTACLE, result.suggestedRecovery)
    }

    @Test
    fun testPostActionAllExpectationsMet() {
        val watchdog = StateEntropyWatchdog()
        val expectation = PostActionExpectation(
            actionTool = "phone_tap",
            expectScreenChange = true,
            expectedTextTokens = listOf("支付成功"),
            expectedPackage = "com.hana.shop",
        )

        val result = watchdog.evaluatePostAction(
            expectation = expectation,
            actualChanged = true,
            actualTexts = listOf("收银台", "支付成功", "查看订单详情"),
            actualPackage = "com.hana.shop",
        )

        assertTrue(result.met)
        assertNull(result.mismatchReason)
        assertNull(result.suggestedRecovery)
    }
}

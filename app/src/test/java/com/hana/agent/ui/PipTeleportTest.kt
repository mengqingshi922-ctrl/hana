package com.hana.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipTeleportTest {

    @Test
    fun teleportForTakeoverExemptsTouchPassthroughEvenWhenSettingDisabled() {
        val coordinator = PipTeleportCoordinator()
        // 设置里默认关闭触摸穿透
        assertFalse(coordinator.isEffectiveTouchEnabled(settingEnabled = false))

        val state = coordinator.teleportForTakeover(
            reason = "captcha",
            message = "请拖动滑块完成验证",
        )
        assertTrue(state.active)
        assertTrue(state.touchExempted)
        assertEquals("captcha", state.reason)
        // 接管期间即使设置总开关关闭，也临时豁免放行 PipInput
        assertTrue(coordinator.isEffectiveTouchEnabled(settingEnabled = false))
        assertTrue(coordinator.isEffectiveTouchEnabled(settingEnabled = true))
    }

    @Test
    fun frameChangeBeforeUserInteractionDoesNotAutoRestoreUntilUserActs() {
        val coordinator = PipTeleportCoordinator()
        coordinator.teleportForTakeover("slider_captcha")

        // 锚定第一帧画面哈希
        assertFalse(coordinator.onFrameHash(1001))
        assertTrue(coordinator.state.value.active)

        // 用户还没动手时仅背景动画/倒计时刷新（哈希变了）不应误关闭瞬移小窗
        assertFalse(coordinator.onFrameHash(1002))
        assertTrue(coordinator.state.value.active)

        // 用户开始滑动滑块注入触摸后，若画面变成新页面（与锚点不同），自动恢复
        coordinator.onUserTouchInjected()
        assertTrue(coordinator.state.value.userInteracted)

        val restored = coordinator.onFrameHash(2048)
        assertTrue(restored)
        assertFalse(coordinator.state.value.active)
        assertFalse(coordinator.state.value.touchExempted)
        assertTrue(coordinator.state.value.autoRestored)
        // 自动恢复后重新遵守用户的触摸开关设置（关闭则不允许穿透）
        assertFalse(coordinator.isEffectiveTouchEnabled(settingEnabled = false))
    }

    @Test
    fun eligibleReasonsRecognizeCaptchaSliderAnd2FA() {
        assertTrue(PipTeleportCoordinator.isTeleportEligibleReason("captcha"))
        assertTrue(PipTeleportCoordinator.isTeleportEligibleReason("slider_verify"))
        assertTrue(PipTeleportCoordinator.isTeleportEligibleReason("2FA"))
        assertTrue(PipTeleportCoordinator.isTeleportEligibleReason("需要滑块验证码"))
        assertTrue(PipTeleportCoordinator.isTeleportEligibleReason("login"))
        assertFalse(PipTeleportCoordinator.isTeleportEligibleReason("payment"))
        assertFalse(PipTeleportCoordinator.isTeleportEligibleReason(null))
        assertFalse(PipTeleportCoordinator.isTeleportEligibleReason(""))
    }

    @Test
    fun onTakeoverResolvedClearsActiveTeleportState() {
        val coordinator = PipTeleportCoordinator()
        coordinator.teleportForTakeover("2fa")
        assertTrue(coordinator.state.value.active)

        coordinator.onTakeoverResolved()
        assertFalse(coordinator.state.value.active)
        assertFalse(coordinator.state.value.touchExempted)
        assertFalse(coordinator.state.value.autoRestored)
    }
}

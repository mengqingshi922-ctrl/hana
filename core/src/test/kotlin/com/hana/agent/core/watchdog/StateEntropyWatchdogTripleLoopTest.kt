package com.hana.agent.core.watchdog

import com.hana.agent.core.tool.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateEntropyWatchdogTripleLoopTest {

    @Test
    fun navigationDriftTriggeredAfterFourConsecutiveSwipeBackWaitActions() {
        val watchdog = StateEntropyWatchdog(navigationDriftWindow = 4)

        watchdog.recordAction("phone_swipe", Verb.Swipe)
        watchdog.recordAction("wait", Verb.Wait)
        watchdog.recordAction("phone_back", Verb.Back)
        assertFalse(watchdog.detectNavigationDrift())
        assertEquals(WatchdogVerdict.HEALTHY, watchdog.evaluate())
        assertNull(watchdog.navigationDriftPrompt())

        // 第 4 步纯导航/等待无业务交互：立即触发连续导航漂移与纠偏提醒
        watchdog.recordAction("phone_swipe", Verb.Swipe)
        assertTrue(watchdog.detectNavigationDrift())
        assertEquals(WatchdogVerdict.NAVIGATION_DRIFT, watchdog.evaluate())

        val report = watchdog.evaluateTripleLoop()
        assertEquals(WatchdogVerdict.NAVIGATION_DRIFT, report.verdict)
        assertNotNull(report.navigationDriftPrompt)
        assertTrue(report.navigationDriftPrompt!!.contains("连续 4 步"))
        assertTrue(report.guidance!!.contains("phone_swipe -> wait -> phone_back -> phone_swipe"))
    }

    @Test
    fun businessInteractionResetsNavigationDriftCounter() {
        val watchdog = StateEntropyWatchdog(navigationDriftWindow = 4)

        watchdog.recordAction("phone_swipe", Verb.Swipe)
        watchdog.recordAction("phone_back", Verb.Back)
        watchdog.recordAction("wait", Verb.Wait)
        // 发生实质业务点击交互，清零漂移计数
        watchdog.recordAction("phone_tap", Verb.Tap)
        assertFalse(watchdog.detectNavigationDrift())

        watchdog.recordAction("phone_swipe", Verb.Swipe)
        watchdog.recordAction("phone_swipe", Verb.Swipe)
        watchdog.recordAction("wait", Verb.Wait)
        assertFalse(watchdog.detectNavigationDrift())
        assertEquals(WatchdogVerdict.HEALTHY, watchdog.evaluate())
    }

    @Test
    fun detectsFormValidationRedTextAndExtractsErrorContextOnStagnation() {
        val watchdog = StateEntropyWatchdog()

        val beforeTexts = listOf("注册账号", "手机号", "短信验证码", "提交")
        val afterTexts = listOf("注册账号", "手机号", "短信验证码", "提交", "手机号格式不正确")
        val observations = listOf(
            FormTextObservation("注册账号", colorHex = "#222222"),
            FormTextObservation("手机号格式不正确", colorHex = "#FF3B30"),
        )

        val alert = watchdog.detectFormValidationError(
            beforeTexts = beforeTexts,
            afterTexts = afterTexts,
            observations = observations,
            stagnated = true,
        )
        assertNotNull(alert)
        assertTrue(alert!!.isRedHighlighted)
        assertEquals(listOf("手机号格式不正确"), alert.errorTexts)
        assertTrue(alert.prompt.contains("红字"))
        assertTrue(alert.prompt.contains("手机号格式不正确"))

        assertEquals(WatchdogVerdict.FORM_VALIDATION_ERROR, watchdog.evaluate())
        val report = watchdog.evaluateTripleLoop()
        assertEquals(WatchdogVerdict.FORM_VALIDATION_ERROR, report.verdict)
        assertEquals(alert, report.formValidationAlert)
    }

    @Test
    fun detectsErrorCopyWithoutColorMetadataAndViaRecordScreenTexts() {
        val watchdog = StateEntropyWatchdog()

        watchdog.recordScreenTexts(listOf("登录", "用户名", "密码", "确认登录"), isPostSubmit = false)
        val alert = watchdog.recordScreenTexts(
            currentTexts = listOf("登录", "用户名", "密码", "确认登录", "Password must be at least 8 characters", "验证码不能为空"),
            isPostSubmit = true,
            stagnated = true,
        )
        assertNotNull(alert)
        assertFalse(alert!!.isRedHighlighted)
        assertEquals(
            listOf("Password must be at least 8 characters", "验证码不能为空"),
            alert.errorTexts,
        )
        assertEquals(WatchdogVerdict.FORM_VALIDATION_ERROR, watchdog.evaluate())
    }

    @Test
    fun nonStagnatedPageTransitionDoesNotTriggerFormValidationError() {
        val watchdog = StateEntropyWatchdog()
        val alert = watchdog.detectFormValidationError(
            beforeTexts = listOf("提交"),
            afterTexts = listOf("提交失败"),
            observations = listOf(FormTextObservation("提交失败", isRedOrErrorColor = true)),
            stagnated = false,
        )
        assertNull(alert)
        assertEquals(WatchdogVerdict.HEALTHY, watchdog.evaluate())
    }

    @Test
    fun resetClearsNavigationDriftAndFormErrorState() {
        val watchdog = StateEntropyWatchdog(navigationDriftWindow = 4)
        repeat(4) { watchdog.recordAction("phone_swipe", Verb.Swipe) }
        watchdog.detectFormValidationError(listOf("提交"), listOf("提交", "必填项不能为空"), stagnated = true)
        assertEquals(WatchdogVerdict.FORM_VALIDATION_ERROR, watchdog.evaluate())

        watchdog.reset()
        assertFalse(watchdog.detectNavigationDrift())
        assertEquals(WatchdogVerdict.HEALTHY, watchdog.evaluate())
    }
}

package com.hana.agent.ui.safegate

import android.content.Context
import android.content.ContextWrapper
import android.os.CancellationSignal
import com.hana.agent.core.risk.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricSafeGateTest {

    private class MockContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun classifierDetectsT3HighRiskOperationsAndIgnoresBenignActions() {
        assertEquals(
            T3RiskCategory.TRANSFER,
            T3RiskClassifier.classify(IrrevAction.pay, title = "确认转账给张三", description = "金额 1000 元"),
        )
        assertEquals(
            T3RiskCategory.ACCOUNT_DELETE,
            T3RiskClassifier.classify(IrrevAction.delete, title = "注销账号", description = "永久删除所有资料"),
        )
        assertEquals(
            T3RiskCategory.PAYMENT,
            T3RiskClassifier.classify(IrrevAction.pay, title = "立即支付", description = "订单金额 88.00 元"),
        )
        assertTrue(T3RiskClassifier.requiresBiometricGate(IrrevAction.pay, "确认付款"))
        assertFalse(T3RiskClassifier.requiresBiometricGate(IrrevAction.send, "发送你好"))
        assertNull(T3RiskClassifier.classify(null, "查看天气", "查询北京天气"))
    }

    @Test
    fun slideBelowThresholdDoesNotInvokeBiometricAndStaysLocked() {
        var authInvoked = false
        var authorizedCount = 0
        val fakeAuth = object : BiometricAuthenticator {
            override fun checkAvailability(context: Context) = BiometricAvailability.BIOMETRIC_READY
            override fun authenticate(
                context: Context,
                request: BiometricGateRequest,
                onResult: (BiometricAuthResult) -> Unit,
            ): CancellationSignal? {
                authInvoked = true
                return null
            }
        }

        val controller = BiometricSafeGateController(fakeAuth)
        val req = BiometricGateRequest("确认转账", "转账", "向张三转账 500 元", T3RiskCategory.TRANSFER)

        controller.onSlideThresholdReached(
            context = MockContext(),
            request = req,
            slideFraction = 0.84f,
            onAuthorized = { authorizedCount++ },
        )

        assertFalse(authInvoked)
        assertEquals(0, authorizedCount)
        assertEquals(BiometricGatePhase.SLIDE_LOCKED, controller.phase)
    }

    @Test
    fun slideAtThresholdRequiresHardwareBiometricSuccessWithMatchingNonce() {
        var capturedCallback: ((BiometricAuthResult) -> Unit)? = null
        var authorizedResult: BiometricAuthResult.Success? = null
        val fakeAuth = object : BiometricAuthenticator {
            override fun checkAvailability(context: Context) = BiometricAvailability.BIOMETRIC_READY
            override fun authenticate(
                context: Context,
                request: BiometricGateRequest,
                onResult: (BiometricAuthResult) -> Unit,
            ): CancellationSignal? {
                capturedCallback = onResult
                return null
            }
        }

        val controller = BiometricSafeGateController(fakeAuth)
        val req = BiometricGateRequest("支付确认", "支付", "支付 199 元", T3RiskCategory.PAYMENT)

        controller.onSlideThresholdReached(
            context = MockContext(),
            request = req,
            slideFraction = 0.86f,
            onAuthorized = { authorizedResult = it },
        )

        assertEquals(BiometricGatePhase.VERIFYING_BIOMETRIC, controller.phase)
        assertNull("未完成指纹/面容前绝对禁止放行", authorizedResult)
        assertNotNull(capturedCallback)

        // 1. 伪造挑战码的回调必须被拒绝
        capturedCallback!!.invoke(BiometricAuthResult.Success("biometric", attestedNonce = "forged-nonce"))
        assertEquals(BiometricGatePhase.ERROR, controller.phase)
        assertNull(authorizedResult)

        // 2. 重置并重新滑动，返回匹配挑战码的成功结果才放行
        controller.reset()
        controller.onSlideThresholdReached(
            context = MockContext(),
            request = req,
            slideFraction = 0.90f,
            onAuthorized = { authorizedResult = it },
        )
        capturedCallback!!.invoke(BiometricAuthResult.Success("biometric", attestedNonce = req.challengeNonce))
        assertEquals(BiometricGatePhase.AUTHORIZED, controller.phase)
        assertNotNull(authorizedResult)
        assertEquals(req.challengeNonce, authorizedResult!!.attestedNonce)
    }

    @Test
    fun biometricCancelOrFailureBlocksAuthorizationAndAllowsRetry() {
        var capturedCallback: ((BiometricAuthResult) -> Unit)? = null
        var authorizedCount = 0
        val fakeAuth = object : BiometricAuthenticator {
            override fun checkAvailability(context: Context) = BiometricAvailability.DEVICE_CREDENTIAL_ONLY
            override fun authenticate(
                context: Context,
                request: BiometricGateRequest,
                onResult: (BiometricAuthResult) -> Unit,
            ): CancellationSignal? {
                capturedCallback = onResult
                return null
            }
        }

        val controller = BiometricSafeGateController(fakeAuth)
        val req = BiometricGateRequest("删号确认", "删号", "注销账号", T3RiskCategory.ACCOUNT_DELETE)

        controller.onSlideThresholdReached(MockContext(), req, 0.9f, { authorizedCount++ })
        capturedCallback!!.invoke(BiometricAuthResult.Canceled("user_canceled"))
        assertEquals(BiometricGatePhase.SLIDE_LOCKED, controller.phase)
        assertEquals(0, authorizedCount)

        controller.onSlideThresholdReached(MockContext(), req, 0.9f, { authorizedCount++ })
        capturedCallback!!.invoke(BiometricAuthResult.Failed(7, "尝试次数过多，请稍后再试"))
        assertEquals(BiometricGatePhase.ERROR, controller.phase)
        assertEquals(0, authorizedCount)
        assertNotNull(controller.lastErrorMessage)
    }

    @Test
    fun unenrolledFallbackPolicyControlsStrictVsCompatibleBehavior() {
        val unenrolledAuth = object : BiometricAuthenticator {
            override fun checkAvailability(context: Context) = BiometricAvailability.NONE_ENROLLED
            override fun authenticate(
                context: Context,
                request: BiometricGateRequest,
                onResult: (BiometricAuthResult) -> Unit,
            ): CancellationSignal? {
                onResult(BiometricAuthResult.Unavailable(BiometricAvailability.NONE_ENROLLED, "未录入生物特征"))
                return null
            }
        }

        val req = BiometricGateRequest("转账", "转账", "向李四转账", T3RiskCategory.TRANSFER)

        // 严格模式：未录入生物特征/锁屏时不放行
        var strictAuthorized = false
        val strictController = BiometricSafeGateController(unenrolledAuth, BiometricFallbackPolicy.REQUIRE_DEVICE_AUTH)
        strictController.onSlideThresholdReached(MockContext(), req, 0.95f, { strictAuthorized = true })
        assertFalse(strictAuthorized)
        assertEquals(BiometricGatePhase.ERROR, strictController.phase)

        // 兼容模式：设备未设防时按阻尼滑动降级放行并标注类型
        var compatResult: BiometricAuthResult.Success? = null
        val compatController = BiometricSafeGateController(unenrolledAuth, BiometricFallbackPolicy.ALLOW_SLIDE_ONLY_IF_UNENROLLED)
        compatController.onSlideThresholdReached(MockContext(), req, 0.95f, { compatResult = it })
        assertNotNull(compatResult)
        assertEquals("slide_fallback_unenrolled", compatResult!!.authType)
        assertEquals(BiometricGatePhase.AUTHORIZED, compatController.phase)
    }

    @Test
    fun androidBiometricAuthenticatorHandlesMockContextWithoutCrash() {
        val mockContext = MockContext()
        val availability = AndroidBiometricAuthenticator.checkAvailability(mockContext)
        assertEquals(BiometricAvailability.UNAVAILABLE, availability)

        var result: BiometricAuthResult? = null
        val signal = AndroidBiometricAuthenticator.authenticate(
            mockContext,
            BiometricGateRequest("测试", "支付", "测试支付"),
        ) { result = it }
        assertNull(signal)
        assertTrue(result is BiometricAuthResult.Unavailable)
    }
}

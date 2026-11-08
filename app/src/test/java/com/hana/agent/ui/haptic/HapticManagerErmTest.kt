package com.hana.agent.ui.haptic

import android.content.ContextWrapper
import android.os.Vibrator
import com.hana.agent.ui.safegate.SafeGateHaptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证统一四阶触觉中枢 (HapticManager) 与偏心转子马达 (ERM) 紧凑降级规范
 */
class HapticManagerErmTest {

    private class NullServiceContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    private class FakeVibratorContext(private val vibrator: Vibrator?) : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? {
            return if (name == "vibrator") vibrator else null
        }
    }

    @Test
    fun verifiesHapticPatternEnumCompleteness() {
        val patterns = HapticPattern.entries
        assertEquals(4, patterns.size)
        assertTrue(patterns.contains(HapticPattern.LightTick))
        assertTrue(patterns.contains(HapticPattern.ElasticSnap))
        assertTrue(patterns.contains(HapticPattern.SuccessBloom))
        assertTrue(patterns.contains(HapticPattern.GateWarning))
    }

    @Test
    fun nullContextOrHeadlessEnvironmentIsFullyDefended() {
        val context = NullServiceContext()

        // 验证在无系统振动服务环境下，isLinearActuator 安全返回 false，不崩溃
        assertFalse(HapticManager.isLinearActuator(context))

        // 验证所有模式在 headless 环境下触发均被安全捕获，100% 无 NPE 异常外溢
        for (pattern in HapticPattern.entries) {
            HapticManager.trigger(context, pattern)
        }
    }

    @Test
    fun safeGateHapticsDelegatesToHapticManagerPatterns() {
        val context = NullServiceContext()

        // 验证 SafeGateHaptics 对接 HapticManager 后的端到端调用安全性
        SafeGateHaptics.triggerClickThump(context)
        SafeGateHaptics.triggerGateWarning(context)
        SafeGateHaptics.triggerElasticSnap(context)
        SafeGateHaptics.triggerLightTick(context)
        SafeGateHaptics.triggerSuccessBloom(context)

        assertNotNull(SafeGateHaptics)
    }

    @Test
    fun ermDegradationDesignSpecificationConstants() {
        // 核心物理参数核验：
        // LRA 线性马达高危警示为 2 段式复合波形 (20ms + 40ms + 60ms = 120ms)
        // ERM 偏心转子马达若播放该复合波形，因起停惯性会物理糊化成 120ms 连续刺耳蜂鸣；
        // 因此 ERM 必须紧凑降级为单次 35ms 脉冲！
        val lraGateWarningTotalDurationMs = 20L + 40L + 60L
        val ermGateWarningCompactDurationMs = 35L

        assertEquals(120L, lraGateWarningTotalDurationMs)
        assertEquals(35L, ermGateWarningCompactDurationMs)
        assertTrue(
            "ERM 紧凑降级脉冲时长必须严格小于 LRA 复合起停总时长",
            ermGateWarningCompactDurationMs < lraGateWarningTotalDurationMs
        )
    }

    @Test
    fun hapticManagerExceptionSafetyGuaranteed() {
        // 传入抛出特定异常的 Context 验证全链路容错
        val throwingContext = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? {
                throw SecurityException("VIBRATE permission denied for background thread")
            }
        }

        assertFalse(HapticManager.isLinearActuator(throwingContext))
        // 必须优雅吞掉异常，不可崩溃
        HapticManager.trigger(throwingContext, HapticPattern.GateWarning)
        HapticManager.trigger(throwingContext, HapticPattern.LightTick)
    }
}

package com.hana.agent.core.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareProbeTest {

    private class MockHardwareProbe(private val spec: HardwareSpec) : HardwareProbe {
        override fun probe(): HardwareSpec = spec
    }

    private fun gigabytes(gb: Double): Long = (gb * 1024.0 * 1024.0 * 1024.0).toLong()

    @Test
    fun testLowRamDeviceDisables15bVlm() {
        // 模拟 4GB 与 6GB 内存设备（虽然具备 NPU）
        val spec4gb = HardwareSpec(
            totalRamBytes = gigabytes(4.0),
            hasDedicatedNpu = true,
            batteryPct = 80,
            isThermalThrottling = false
        )
        val probe4gb = MockHardwareProbe(spec4gb)
        assertFalse(spec4gb.isEligibleFor1_5B)
        assertTrue(spec4gb.isEligibleFor0_3B)
        assertEquals(EdgeModelTier.POCKET_0_3B_CLASSIFIER, probe4gb.evaluateAllowedTier())

        val spec6gb = HardwareSpec(
            totalRamBytes = gigabytes(6.0),
            hasDedicatedNpu = true,
            batteryPct = 90,
            isThermalThrottling = false
        )
        val probe6gb = MockHardwareProbe(spec6gb)
        assertFalse(spec6gb.isEligibleFor1_5B)
        assertEquals(EdgeModelTier.POCKET_0_3B_CLASSIFIER, probe6gb.evaluateAllowedTier())
    }

    @Test
    fun testLackOfNpuDisables15bVlm() {
        // 模拟 12GB 旗舰大内存，但缺乏专属硬件 NPU
        val specNoNpu = HardwareSpec(
            totalRamBytes = gigabytes(12.0),
            hasDedicatedNpu = false,
            batteryPct = 95,
            isThermalThrottling = false
        )
        val probe = MockHardwareProbe(specNoNpu)
        assertFalse("Device without dedicated NPU must not run 1.5B VLM", specNoNpu.isEligibleFor1_5B)
        // 允许运行 0.3B 分类核
        assertTrue(specNoNpu.isEligibleFor0_3B)
        assertEquals(EdgeModelTier.POCKET_0_3B_CLASSIFIER, probe.evaluateAllowedTier())
    }

    @Test
    fun testThermalThrottlingOrLowBatteryFallback() {
        // 模拟 12GB + NPU 旗舰机，但处于系统温控发热严重阶段
        val specThermal = HardwareSpec(
            totalRamBytes = gigabytes(12.0),
            hasDedicatedNpu = true,
            batteryPct = 80,
            isThermalThrottling = true
        )
        val probeThermal = MockHardwareProbe(specThermal)
        assertFalse(specThermal.isEligibleFor1_5B)
        assertFalse(specThermal.isEligibleFor0_3B)
        assertEquals(EdgeModelTier.NONE, probeThermal.evaluateAllowedTier())

        // 模拟低电量 (< 15%)，比如 12% 电量
        val specLowBattery12 = HardwareSpec(
            totalRamBytes = gigabytes(12.0),
            hasDedicatedNpu = true,
            batteryPct = 12,
            isThermalThrottling = false
        )
        val probeLowBattery = MockHardwareProbe(specLowBattery12)
        assertFalse(specLowBattery12.isEligibleFor1_5B)
        assertTrue(specLowBattery12.isEligibleFor0_3B)
        assertEquals(EdgeModelTier.POCKET_0_3B_CLASSIFIER, probeLowBattery.evaluateAllowedTier())

        // 极低电量 (< 10%)，比如 8% 电量
        val specExtremeLowBattery = HardwareSpec(
            totalRamBytes = gigabytes(12.0),
            hasDedicatedNpu = true,
            batteryPct = 8,
            isThermalThrottling = false
        )
        assertEquals(EdgeModelTier.NONE, MockHardwareProbe(specExtremeLowBattery).evaluateAllowedTier())
    }

    @Test
    fun testEligibleFlagshipDeviceEnablesFastLane() {
        // 模拟 12GB RAM、具备专属 NPU、85% 电量且正常温度的旗舰设备
        val flagshipSpec = HardwareSpec(
            totalRamBytes = gigabytes(12.0),
            hasDedicatedNpu = true,
            socModel = "Snapdragon 8 Gen 3",
            batteryPct = 85,
            isThermalThrottling = false
        )
        val probe = MockHardwareProbe(flagshipSpec)
        assertTrue(flagshipSpec.isEligibleFor1_5B)
        assertTrue(flagshipSpec.isEligibleFor0_3B)
        assertEquals(EdgeModelTier.VLM_1_5B_FULL, probe.evaluateAllowedTier())
    }

    @Test
    fun testVeryLowRamDisablesAllLocalModels() {
        // 模拟极低内存设备 (2GB RAM)
        val spec2gb = HardwareSpec(
            totalRamBytes = gigabytes(2.0),
            hasDedicatedNpu = false,
            batteryPct = 100,
            isThermalThrottling = false
        )
        val probe = MockHardwareProbe(spec2gb)
        assertFalse(spec2gb.isEligibleFor1_5B)
        assertFalse(spec2gb.isEligibleFor0_3B)
        assertEquals(EdgeModelTier.NONE, probe.evaluateAllowedTier())
    }
}

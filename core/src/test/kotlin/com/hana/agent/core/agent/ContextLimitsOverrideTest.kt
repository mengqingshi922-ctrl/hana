package com.hana.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显式触发点不能被"窗口比例下限"吃掉。
 * 模型池取两个通道的最小窗口后，128K 窗口的下限是 64K，
 * 会把"压到 28K"这件事静默改成 64K —— 每步多背几万 token。
 */
class ContextLimitsOverrideTest {

    @Test
    fun explicitOverrideCanGoBelowTheWindowShareFloor() {
        val l = ContextLimits.of(128_000, 28_000, allowLowOverride = true)
        assertEquals("显式 28K 夹到绝对下限 32K", ContextLimits.MIN_OVERRIDE_TRIGGER, l.trigger)
        assertTrue("仍要有下限，别开局就压", l.trigger >= ContextLimits.MIN_OVERRIDE_TRIGGER)
    }

    @Test
    fun withoutOverrideTheWindowShareFloorStillApplies() {
        val l = ContextLimits.of(128_000)
        assertEquals("默认触发点是可用窗口减余量", l.usable - l.margin, l.trigger)
        assertTrue("且明显高于显式覆盖时的绝对下限", l.trigger > ContextLimits.MIN_OVERRIDE_TRIGGER)
    }

    @Test
    fun bigWindowWithoutOverrideIsUnchanged() {
        val l = ContextLimits.of(512_000)
        assertTrue("512K 窗口默认触发点在 25 万以上", l.trigger > 200_000)
    }

    @Test
    fun overrideIsCappedByUsableWindow() {
        val l = ContextLimits.of(32_000, 999_999, allowLowOverride = true)
        assertEquals("不能超过可用窗口", l.usable, l.trigger)
    }
}

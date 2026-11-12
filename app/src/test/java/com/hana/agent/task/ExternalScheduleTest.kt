package com.hana.agent.task

import com.hana.agent.settings.FeatureFlags
import com.hana.agent.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExternalScheduleTest {

    @Before
    fun setUp() {
        ExternalSchedule.RateLimiter.reset()
        ExternalSchedule.RateLimiter.minIntervalMs = 60_000L
    }

    @Test
    fun constantsMatchContract() {
        assertEquals("com.hana.agent.ACTION_CREATE_TASK", ExternalSchedule.ACTION_CREATE_TASK)
        assertEquals("task", ExternalSchedule.EXTRA_TASK)
        assertEquals("autoStart", ExternalSchedule.EXTRA_AUTO_START)
        assertEquals("source", ExternalSchedule.EXTRA_SOURCE)
    }

    @Test
    fun externalBroadcastFeatureFlagDefaultsToFalse() {
        val prefs = MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertFalse(flags.externalBroadcastEnabled())
    }

    @Test
    fun externalBroadcastFeatureFlagCanBeToggled() {
        val prefs = MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertFalse(flags.externalBroadcastEnabled())

        flags.setExternalBroadcastEnabled(true)
        assertTrue(flags.externalBroadcastEnabled())

        flags.setExternalBroadcastEnabled(false)
        assertFalse(flags.externalBroadcastEnabled())
    }

    @Test
    fun rateLimiterEnforcesMinInterval() {
        val now = 1_000_000L
        ExternalSchedule.RateLimiter.minIntervalMs = 60_000L

        // 第一次调用应该放行
        assertTrue(ExternalSchedule.RateLimiter.tryAcquire(now))
        assertEquals(now, ExternalSchedule.RateLimiter.lastAcceptedTime())

        // 59秒后（未满1分钟）应该被限流拦截
        assertFalse(ExternalSchedule.RateLimiter.tryAcquire(now + 59_000L))

        // 60秒（达到1分钟）后应该放行
        assertTrue(ExternalSchedule.RateLimiter.tryAcquire(now + 60_000L))
        assertEquals(now + 60_000L, ExternalSchedule.RateLimiter.lastAcceptedTime())

        // 紧接着再次被拦截
        assertFalse(ExternalSchedule.RateLimiter.tryAcquire(now + 60_001L))
    }

    @Test
    fun rateLimiterResetClearsLastTimestamp() {
        val now = 2_000_000L
        assertTrue(ExternalSchedule.RateLimiter.tryAcquire(now))
        assertFalse(ExternalSchedule.RateLimiter.tryAcquire(now + 1000L))

        ExternalSchedule.RateLimiter.reset()
        assertEquals(0L, ExternalSchedule.RateLimiter.lastAcceptedTime())
        assertTrue(ExternalSchedule.RateLimiter.tryAcquire(now + 1000L))
    }
}

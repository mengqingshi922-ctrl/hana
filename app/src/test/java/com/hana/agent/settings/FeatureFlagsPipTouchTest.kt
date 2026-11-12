package com.hana.agent.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsPipTouchTest {

    @Test
    fun pipTouchDefaultsToFalse() {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        assertFalse(flags.pipTouchEnabled())
    }

    @Test
    fun pipTouchCanBeToggled() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)

        flags.setPipTouchEnabled(true)
        assertTrue(flags.pipTouchEnabled())

        flags.setPipTouchEnabled(false)
        assertFalse(flags.pipTouchEnabled())
    }

    @Test
    fun changesEmitCurrentThenEachChange() = runTest {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        assertFalse(flags.pipTouchEnabledChanges().first())

        val seen = mutableListOf<Boolean>()
        val job = launch { flags.pipTouchEnabledChanges().take(3).toList(seen) }
        runCurrent()

        flags.setPipTouchEnabled(true)
        runCurrent()
        flags.setPipTouchEnabled(false)
        job.join()

        assertEquals(listOf(false, true, false), seen)
    }
}

package com.hana.agent.core.usage

import com.hana.agent.core.model.Usage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BudgetBreaker] 四维预算熔断与 80%/100% 水位单元测试。 */
class BudgetBreakerTest {

    @Test
    fun tokensDimensionTriggers80PercentWarningAnd100PercentExceeded() {
        val breaker = BudgetBreaker(TaskBudget(maxTokens = 1_000L))

        val normal = breaker.recordModelCall(Usage(inputTokens = 500, outputTokens = 299))
        assertTrue(normal is BudgetWatermark.Normal)
        assertFalse(breaker.shouldDowngradeToLight())
        assertNull(breaker.warningNotice())

        // 达到 800 / 1000 = 80%：触发 Warning 并建议降级 light
        val warn = breaker.recordModelCall(Usage(inputTokens = 1, outputTokens = 0))
        assertTrue(warn is BudgetWatermark.Warning)
        warn as BudgetWatermark.Warning
        assertEquals(setOf(BudgetDimension.Tokens), warn.dimensions)
        assertTrue(warn.suggestLightDowngrade)
        assertTrue(breaker.shouldDowngradeToLight())
        assertFalse(breaker.isExceeded())
        assertNotNull(breaker.warningNotice())
        assertTrue(breaker.checkOrThrow() is BudgetWatermark.Warning)

        // 达到 1000 / 1000 = 100%：触发 Exceeded 并在 checkOrThrow 抛出 BudgetExceeded
        val exceeded = breaker.recordModelCall(Usage(inputTokens = 150, outputTokens = 50))
        assertTrue(exceeded is BudgetWatermark.Exceeded)
        assertTrue(breaker.isExceeded())
        val ex = runCatching { breaker.checkOrThrow() }.exceptionOrNull()
        assertTrue(ex is BudgetExceeded)
        ex as BudgetExceeded
        assertEquals(setOf(BudgetDimension.Tokens), ex.dimensions)
        assertEquals(1_000L, ex.snapshot.tokensUsed)
    }

    @Test
    fun costUsdDimensionAccountsForCachedTokensAndExplicitCost() {
        val pricing = BudgetPricing(
            inputPerMillionUsd = 2.0,
            outputPerMillionUsd = 10.0,
            cachedPerMillionUsd = 0.5,
        )
        val breaker = BudgetBreaker(
            budget = TaskBudget(maxCostUsd = 1.0),
            defaultPricing = pricing,
        )

        // nonCachedInput = 100_000 (0.20) + cached = 200_000 (0.10) + output = 50_000 (0.50) => 0.80 USD (80%)
        val warn = breaker.recordModelCall(
            Usage(inputTokens = 300_000, outputTokens = 50_000, cachedInputTokens = 200_000)
        )
        assertTrue(warn is BudgetWatermark.Warning)
        assertEquals(0.80, warn.snapshot.costUsd, 1e-6)
        assertEquals(setOf(BudgetDimension.CostUsd), (warn as BudgetWatermark.Warning).dimensions)

        // 再计入 0.25 USD 显式费用 => 1.05 USD >= 1.00 USD
        val exceeded = breaker.recordExternalCall(calls = 1, costUsd = 0.25)
        assertTrue(exceeded is BudgetWatermark.Exceeded)
        assertEquals(1.05, exceeded.snapshot.costUsd, 1e-6)
        assertEquals(setOf(BudgetDimension.CostUsd), (exceeded as BudgetWatermark.Exceeded).dimensions)
    }

    @Test
    fun wallTimeDimensionUsesInjectedClockAndResetsCleanly() {
        var now = 10_000L
        val breaker = BudgetBreaker(
            budget = TaskBudget(maxWallTimeMs = 10_000L),
            clock = { now },
        )

        assertTrue(breaker.check() is BudgetWatermark.Normal)

        now = 18_000L // 8_000 / 10_000 = 80%
        val warn = breaker.check()
        assertTrue(warn is BudgetWatermark.Warning)
        assertEquals(setOf(BudgetDimension.WallTimeMs), (warn as BudgetWatermark.Warning).dimensions)

        now = 20_500L // 10_500 / 10_000 = 105%
        assertTrue(breaker.check() is BudgetWatermark.Exceeded)

        breaker.reset()
        val afterReset = breaker.check()
        assertTrue(afterReset is BudgetWatermark.Normal)
        assertEquals(0L, afterReset.snapshot.wallTimeMs)
        assertEquals(0L, afterReset.snapshot.tokensUsed)
        assertEquals(0, afterReset.snapshot.externalCalls)
    }

    @Test
    fun externalCallsDimensionTracksBothModelAndNonModelCalls() {
        val breaker = BudgetBreaker(TaskBudget(maxExternalCalls = 5))

        assertTrue(breaker.recordModelCall(usage = null) is BudgetWatermark.Normal) // 1
        assertTrue(breaker.recordExternalCall() is BudgetWatermark.Normal) // 2
        assertTrue(breaker.recordExternalCall() is BudgetWatermark.Normal) // 3

        val warn = breaker.recordModelCall(usage = null) // 4 / 5 = 80%
        assertTrue(warn is BudgetWatermark.Warning)
        assertEquals(setOf(BudgetDimension.ExternalCalls), (warn as BudgetWatermark.Warning).dimensions)

        val exceeded = breaker.recordExternalCall() // 5 / 5 = 100%
        assertTrue(exceeded is BudgetWatermark.Exceeded)
        assertEquals(5, exceeded.snapshot.externalCalls)
    }

    @Test
    fun coroutineContextCarriesBudgetBreakerElement() = runTest {
        val breaker = BudgetBreaker(TaskBudget(maxExternalCalls = 3))
        withContext(breaker) {
            val found = currentCoroutineContext()[BudgetBreaker]
            assertSame(breaker, found)
            found!!.recordExternalCall(calls = 3)
            assertTrue(found.isExceeded())
        }
    }
}

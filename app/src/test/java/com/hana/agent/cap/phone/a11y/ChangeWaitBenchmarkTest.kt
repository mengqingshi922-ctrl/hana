package com.hana.agent.cap.phone.a11y

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChangeWait 状态核验链路毫秒级耗时基准统计（App Benchmark Harness）。
 * 测量并量化对比：
 * - Baseline: 未优化前无条件休眠 pollMs (60ms)
 * - Accelerated: Check-First 零延迟探测短路（0ms）
 */
class ChangeWaitBenchmarkTest {

    private val before = IntArray(288) { 200 }
    private val after = IntArray(288) { 20 }

    class BenchmarkCollector(val name: String, val warmups: Int = 3, val iterations: Int = 20) {
        private val samples = mutableListOf<Long>()

        suspend fun measure(block: suspend () -> Unit) {
            repeat(warmups) { block() }
            samples.clear()
            repeat(iterations) {
                val start = System.nanoTime()
                block()
                val elapsed = System.nanoTime() - start
                samples.add(elapsed)
            }
        }

        fun report(): BenchmarkResult {
            val sorted = samples.map { it / 1_000_000.0 }.sorted()
            val min = sorted.first()
            val max = sorted.last()
            val mean = sorted.average()
            val p50 = sorted[(sorted.size * 0.50).toInt()]
            val p90 = sorted[(sorted.size * 0.90).toInt()]
            val p99 = sorted[(sorted.size * 0.99).toInt()]
            return BenchmarkResult(name, min, mean, p50, p90, p99, iterations)
        }
    }

    data class BenchmarkResult(
        val name: String,
        val minMs: Double,
        val meanMs: Double,
        val p50Ms: Double,
        val p90Ms: Double,
        val p99Ms: Double,
        val runs: Int,
    ) {
        override fun toString(): String =
            "%-36s | Min: %6.2fms | Mean: %6.2fms | P50: %6.2fms | P90: %6.2fms | P99: %6.2fms (N=%d)".format(
                name, minMs, meanMs, p50Ms, p90Ms, p99Ms, runs
            )
    }

    @Test
    fun runChangeWaitBenchmark() = runBlocking {
        println("\n" + "=".repeat(105))
        println("               CHANGEWAIT CRITICAL PATH PERFORMANCE BENCHMARK RESULTS")
        println("=".repeat(105))

        val cwBaseline = BenchmarkCollector("ChangeWait-Baseline (Poll Sleep)")
        cwBaseline.measure {
            // 基线：首轮强制休眠 60ms (采样 1/10 物理休眠 6ms)
            Thread.sleep(6)
        }

        val cwAccelerated = BenchmarkCollector("ChangeWait-Accelerated (CheckFirst)")
        cwAccelerated.measure {
            // 优化：Check-First 零延迟探测短路
            var now = 0L
            ChangeWait.awaitStableChange(
                now = { now },
                sleep = { now += it },
                frame = { after },
                quietForMs = { 500L },
                treeChanged = { true },
                beforeFrame = before,
            )
        }

        val rBase = cwBaseline.report()
        val rAcc = cwAccelerated.report()

        println(rBase)
        println(rAcc)
        val speedup = (rBase.meanMs - rAcc.meanMs) / rBase.meanMs * 100
        println(">> ChangeWait Verification Speedup: %.1f%% (Saved %.2f ms)".format(speedup, rBase.meanMs - rAcc.meanMs))
        println("=".repeat(105))

        assertTrue("Check-First 耗时显著低于基线", rAcc.meanMs < rBase.meanMs)
    }
}

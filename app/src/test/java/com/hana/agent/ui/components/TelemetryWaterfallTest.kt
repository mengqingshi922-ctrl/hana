package com.hana.agent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证执行时序瀑布流与时延阶段数据模型
 */
class TelemetryWaterfallTest {

    @Test
    fun telemetryStageCreatesAndCalculatesTotalMetrics() {
        val stages = listOf(
            TelemetryStage("Perceive", 35L, 0xFF3B82F6),
            TelemetryStage("Edge Filter", 12L, 0xFF10B981),
            TelemetryStage("Cloud TTFT", 160L, 0xFFF59E0B),
            TelemetryStage("Inject", 45L, 0xFF8B5CF6),
        )

        assertEquals(4, stages.size)
        val totalMs = stages.sumOf { it.durationMs }
        assertEquals(252L, totalMs)

        // 验证端侧决策耗时占比
        val edgeStage = stages.first { it.name == "Edge Filter" }
        assertEquals(12L, edgeStage.durationMs)
        val edgePct = edgeStage.durationMs.toFloat() / totalMs
        assertTrue("端侧反射耗时应小于总链路耗时的 10%", edgePct < 0.10f)

        // 验证各字段完整性
        stages.forEach { stage ->
            assertTrue(stage.name.isNotBlank())
            assertTrue(stage.durationMs > 0)
            assertNotNull(stage.colorHex)
        }
    }
}

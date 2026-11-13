package com.hana.agent.ui

import com.hana.agent.ui.components.TouchVisualBus
import com.hana.agent.ui.components.VisualTouchEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 跨 App 手势可视化水波纹与滑动拖尾 [TouchVisualBus] 纯逻辑单元测试：
 * 验证坐标归一化、边界裁剪、除零安全以及事件活跃期判定与过期自动剔除。
 */
class VisualTouchOverlayTest {

    @Before
    fun setUp() {
        TouchVisualBus.resetForTesting()
    }

    @Test
    fun normalizeCoordinatesSafetyAndClamping() {
        // 1. 正常比例映射
        assertEquals(0.5f, TouchVisualBus.normalize(540f, 1080), 0.0001f)
        assertEquals(0.25f, TouchVisualBus.normalize(250f, 1000), 0.0001f)

        // 2. 边界刚好对齐
        assertEquals(0f, TouchVisualBus.normalize(0f, 1080), 0.0001f)
        assertEquals(1f, TouchVisualBus.normalize(1080f, 1080), 0.0001f)

        // 3. 越界 Clamp 到 [0f, 1f]
        assertEquals(0f, TouchVisualBus.normalize(-50f, 1080), 0.0001f)
        assertEquals(1f, TouchVisualBus.normalize(2000f, 1080), 0.0001f)

        // 4. 除零与非法屏幕分辨率安全保护
        assertEquals(0f, TouchVisualBus.normalize(500f, 0), 0.0001f)
        assertEquals(0f, TouchVisualBus.normalize(500f, -100), 0.0001f)
    }

    @Test
    fun emitTapNormalAndCoordinatesClamping() = runBlocking {
        val timestamp = 10000L
        val firstDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) { TouchVisualBus.events.first() }
        }
        val emitResult = TouchVisualBus.emitTap(
            x = 540f,
            y = 1200f,
            screenW = 1080,
            screenH = 2400,
            timestampMs = timestamp,
        )
        assertTrue("发射应成功", emitResult)

        val event = firstDeferred.await()
        assertTrue("事件应为 Tap 类型", event is VisualTouchEvent.Tap)
        val tap = event as VisualTouchEvent.Tap
        assertEquals(0.5f, tap.normX, 0.0001f)
        assertEquals(0.5f, tap.normY, 0.0001f)
        assertEquals(timestamp, tap.timestampMs)

        // 越界坐标 Clamp 验证
        val secondDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) { TouchVisualBus.events.first() }
        }
        TouchVisualBus.emitTap(
            x = -100f,
            y = 3000f,
            screenW = 1080,
            screenH = 2400,
            timestampMs = timestamp + 10L,
        )
        val clampedEvent = secondDeferred.await() as VisualTouchEvent.Tap
        assertEquals(0f, clampedEvent.normX, 0.0001f)
        assertEquals(1f, clampedEvent.normY, 0.0001f)
    }

    @Test
    fun emitTapDivisionByZeroProtection() {
        // 宽高异常时严禁除以零，安全返回 false 且不崩溃
        assertFalse(TouchVisualBus.emitTap(100f, 100f, screenW = 0, screenH = 2400))
        assertFalse(TouchVisualBus.emitTap(100f, 100f, screenW = 1080, screenH = 0))
        assertFalse(TouchVisualBus.emitTap(100f, 100f, screenW = -1, screenH = -1))
    }

    @Test
    fun emitStrokeNormalAndCoordinatesClamping() = runBlocking {
        val xs = listOf(-50f, 540f, 1200f)
        val ys = listOf(0f, 1200f, 2600f)
        val timestamp = 20000L
        val eventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) { TouchVisualBus.events.first() }
        }
        val ok = TouchVisualBus.emitStroke(
            xs = xs,
            ys = ys,
            screenW = 1080,
            screenH = 2400,
            durationMs = 350L,
            timestampMs = timestamp,
        )
        assertTrue("滑动轨迹发射应成功", ok)

        val event = eventDeferred.await()
        assertTrue("事件应为 Stroke 类型", event is VisualTouchEvent.Stroke)
        val stroke = event as VisualTouchEvent.Stroke
        assertEquals(3, stroke.points.size)
        assertEquals(350L, stroke.durationMs)
        assertEquals(timestamp, stroke.timestampMs)

        // 第一个点越界裁剪: (-50 -> 0f, 0 -> 0f)
        assertEquals(0f, stroke.points[0].first, 0.0001f)
        assertEquals(0f, stroke.points[0].second, 0.0001f)

        // 第二个点居中: (540 -> 0.5f, 1200 -> 0.5f)
        assertEquals(0.5f, stroke.points[1].first, 0.0001f)
        assertEquals(0.5f, stroke.points[1].second, 0.0001f)

        // 第三个点越界裁剪: (1200 -> 1f, 2600 -> 1f)
        assertEquals(1f, stroke.points[2].first, 0.0001f)
        assertEquals(1f, stroke.points[2].second, 0.0001f)
    }

    @Test
    fun emitStrokeEdgeCasesProtection() {
        // 空点列表
        assertFalse(TouchVisualBus.emitStroke(emptyList(), emptyList(), 1080, 2400, 300L))
        // 坐标列表长度不一致
        assertFalse(TouchVisualBus.emitStroke(listOf(10f), listOf(10f, 20f), 1080, 2400, 300L))
        // 屏幕分辨率异常
        assertFalse(TouchVisualBus.emitStroke(listOf(10f), listOf(20f), 0, 2400, 300L))
        assertFalse(TouchVisualBus.emitStroke(listOf(10f), listOf(20f), 1080, -1, 300L))
    }

    @Test
    fun tapActiveLifecycleAndReduceMotion() {
        val tap = VisualTouchEvent.Tap(id = 1L, normX = 0.5f, normY = 0.5f, timestampMs = 1000L)

        // 1. 常规模式：持续 320ms
        assertTrue("0ms 刚产生时处于活跃期", TouchVisualBus.isEventActive(tap, nowMs = 1000L, reduceMotion = false))
        assertTrue("200ms 中途处于活跃期", TouchVisualBus.isEventActive(tap, nowMs = 1200L, reduceMotion = false))
        assertTrue("319ms 临界前夕仍处于活跃期", TouchVisualBus.isEventActive(tap, nowMs = 1319L, reduceMotion = false))
        assertFalse("320ms 整点已完成消散", TouchVisualBus.isEventActive(tap, nowMs = 1320L, reduceMotion = false))
        assertFalse("500ms 彻底过期", TouchVisualBus.isEventActive(tap, nowMs = 1500L, reduceMotion = false))

        // 2. 减弱动效模式：缩短至 160ms 瞬时标记
        assertTrue("0ms 处于活跃期", TouchVisualBus.isEventActive(tap, nowMs = 1000L, reduceMotion = true))
        assertTrue("100ms 仍处于活跃期", TouchVisualBus.isEventActive(tap, nowMs = 1100L, reduceMotion = true))
        assertFalse("160ms 已过期", TouchVisualBus.isEventActive(tap, nowMs = 1160L, reduceMotion = true))
    }

    @Test
    fun strokeActiveLifecycleAndReduceMotion() {
        // 常规模式，滑动耗时 400ms，总消散期 = max(450L, 400L + 200L) = 600ms
        val stroke = VisualTouchEvent.Stroke(
            id = 2L,
            points = listOf(0.1f to 0.1f, 0.8f to 0.8f),
            durationMs = 400L,
            timestampMs = 5000L,
        )
        assertTrue("滑动中 300ms 活跃", TouchVisualBus.isEventActive(stroke, nowMs = 5300L, reduceMotion = false))
        assertTrue("拖尾渐隐中 550ms 活跃", TouchVisualBus.isEventActive(stroke, nowMs = 5550L, reduceMotion = false))
        assertFalse("600ms 拖尾结束过期", TouchVisualBus.isEventActive(stroke, nowMs = 5600L, reduceMotion = false))

        // 短滑动，耗时 50ms，总消散期取最小值保底 = max(450L, 50L + 200L) = 450ms
        val shortStroke = VisualTouchEvent.Stroke(
            id = 3L,
            points = listOf(0.2f to 0.2f, 0.3f to 0.3f),
            durationMs = 50L,
            timestampMs = 5000L,
        )
        assertTrue("400ms 内保底活跃", TouchVisualBus.isEventActive(shortStroke, nowMs = 5400L, reduceMotion = false))
        assertFalse("450ms 保底时间到期", TouchVisualBus.isEventActive(shortStroke, nowMs = 5450L, reduceMotion = false))

        // 减弱动效模式：统一缩短至 180ms
        assertTrue("100ms 活跃", TouchVisualBus.isEventActive(stroke, nowMs = 5100L, reduceMotion = true))
        assertFalse("180ms 到期", TouchVisualBus.isEventActive(stroke, nowMs = 5180L, reduceMotion = true))
    }

    @Test
    fun pruneExpiredEventsPurgesOnlyFinishedEvents() {
        val baseTime = 10000L
        val tapExpired = VisualTouchEvent.Tap(id = 1L, normX = 0.1f, normY = 0.1f, timestampMs = baseTime - 400L)
        val tapActive = VisualTouchEvent.Tap(id = 2L, normX = 0.2f, normY = 0.2f, timestampMs = baseTime - 100L)
        val strokeExpired = VisualTouchEvent.Stroke(
            id = 3L,
            points = listOf(0.1f to 0.1f, 0.2f to 0.2f),
            durationMs = 200L,
            timestampMs = baseTime - 500L,
        )
        val strokeActive = VisualTouchEvent.Stroke(
            id = 4L,
            points = listOf(0.3f to 0.3f, 0.4f to 0.4f),
            durationMs = 300L,
            timestampMs = baseTime - 200L,
        )

        val allEvents = listOf(tapExpired, tapActive, strokeExpired, strokeActive)
        val activeEvents = TouchVisualBus.pruneExpiredEvents(allEvents, nowMs = baseTime, reduceMotion = false)

        assertEquals("仅保留未过期事件", 2, activeEvents.size)
        assertEquals(2L, activeEvents[0].id)
        assertEquals(4L, activeEvents[1].id)
    }
}

package com.hana.agent.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class GesturePacingDynamicsTest {

    @Test
    fun testPointModelWithDynamicsProperties() {
        val pt = GesturePacing.Point(x = 100.0, y = 200.0, t = 50L, pressure = 0.75f, touchMajor = 11.5f, touchMinor = 9.8f)
        assertEquals(100.0, pt.x, 1e-6)
        assertEquals(200.0, pt.y, 1e-6)
        assertEquals(50L, pt.t)
        assertEquals(0.75f, pt.pressure, 1e-3f)
        assertEquals(11.5f, pt.touchMajor, 1e-3f)
        assertEquals(9.8f, pt.touchMinor, 1e-3f)

        // 默认值测试
        val defaultPt = GesturePacing.Point(x = 50.0, y = 50.0, t = 0L)
        assertEquals(0.8f, defaultPt.pressure, 1e-3f)
        assertEquals(12f, defaultPt.touchMajor, 1e-3f)
        assertEquals(10f, defaultPt.touchMinor, 1e-3f)
    }

    @Test
    fun testPressureProfileCurve() {
        val durationMs = 300L

        // 1. 按下瞬间 (0..15ms)
        val pStart = GesturePacing.calculatePressure(0L, durationMs)
        val pClimbing = GesturePacing.calculatePressure(7L, durationMs)
        val pClimbed = GesturePacing.calculatePressure(15L, durationMs)
        assertEquals(0.2f, pStart, 1e-2f)
        assertTrue(pClimbing in 0.2f..0.8f)
        assertEquals(0.8f, pClimbed, 1e-2f)

        // 2. 滑动中 (中段)
        val pCruise = GesturePacing.calculatePressure(150L, durationMs, speed = 1.0, avgSpeed = 1.0)
        assertTrue(pCruise in 0.65f..0.85f)

        // 3. 抬手前夕 (最后 20ms: 280..300ms)
        val pPreUp = GesturePacing.calculatePressure(290L, durationMs)
        val pUp = GesturePacing.calculatePressure(300L, durationMs)
        assertTrue(pPreUp in 0.1f..0.8f)
        assertEquals(0.1f, pUp, 1e-2f)

        // 接触面积与压力正相关
        val majorDown = GesturePacing.calculateTouchMajor(pStart)
        val majorCruise = GesturePacing.calculateTouchMajor(pCruise)
        assertTrue(majorCruise > majorDown)
    }

    @Test
    fun testGaussianJitterGeneration() {
        val r = Random(42)
        val offsets = (0 until 1000).map {
            val (jx, jy) = GesturePacing.applyGaussianJitter(r, 0.0, 0.0, sigma = 1.2)
            hypot(jx, jy)
        }
        val meanOffset = offsets.average()
        // Rayleign 分布均值约为 sigma * sqrt(pi/2) ≈ 1.2 * 1.253 = 1.50
        assertTrue("mean offset $meanOffset should be around 1.5", meanOffset in 1.2..1.8)
    }

    @Test
    fun testStrokeCubicBSplineDynamics() {
        val pacing = GesturePacing.human(Random(123))
        val start = 100.0 to 100.0
        val end = 500.0 to 900.0
        val stroke = pacing.strokeCubicBSpline(
            start = start,
            end = end,
            durationMs = 400L,
            bounds = 1080 to 2400,
            overshootPx = 3.0,
            jitterSigma = 1.2
        )

        val pts = stroke.points
        assertTrue(pts.size >= 10)

        // 起点与终点验证
        assertEquals(start.first, pts.first().x, 1e-6)
        assertEquals(start.second, pts.first().y, 1e-6)
        assertEquals(0L, pts.first().t)
        assertEquals(end.first, pts.last().x, 1e-6)
        assertEquals(end.second, pts.last().y, 1e-6)

        // 时间单调递增
        assertTrue(pts.zipWithNext().all { (a, b) -> b.t >= a.t })

        // 边界约束
        assertTrue(pts.all { it.x in 0.0..1079.0 && it.y in 0.0..2399.0 })

        // 首尾压力检验
        assertTrue(pts.first().pressure in 0.2f..0.3f)
        assertTrue(pts.last().pressure in 0.1f..0.2f)

        // 中间采样点包含高斯微抖动
        val distancesFromLine = pts.drop(1).dropLast(1).map { p ->
            val num = abs((end.second - start.second) * p.x - (end.first - start.first) * p.y + end.first * start.second - end.second * start.first)
            val den = hypot(end.first - start.first, end.second - start.second)
            num / den
        }
        // 抖动与曲率使中间点偏离直线的距离存在波动
        assertTrue(distancesFromLine.max() > 1.0)
    }

    @Test
    fun testOvershootAndDampedSettleEmpirical() {
        val pacing = GesturePacing.human(Random.Default)
        val testDistances = listOf(60.0, 150.0, 300.0, 600.0, 1000.0)

        for (dist in testDistances) {
            val start = 100.0 to 100.0
            val end = (100.0 + dist) to 100.0
            val stroke = pacing.strokeCubicBSpline(
                start = start,
                end = end,
                durationMs = 350L,
                overshootPx = 3.0,
                jitterSigma = 0.0 // 几何基准
            )
            val pts = stroke.points
            val n = pts.size

            val maxX = pts.maxOf { it.x }
            val maxIdx = pts.indexOfFirst { it.x == maxX }
            val overshoot = maxX - end.first

            // 1. 验证过冲量严格在 2.0 ~ 4.0px
            assertTrue("dist $dist: overshoot $overshoot should be in 2.0..4.0", overshoot in 2.0..4.0)

            // 2. 验证终点精准落定
            assertEquals(end.first, pts.last().x, 1e-6)

            // 3. 验证阻尼多步回正（消除单步断崖反弹，峰值在倒数第3~4步）
            assertTrue("dist $dist: maxIdx $maxIdx should be < ${n - 2}", maxIdx < n - 2)
            assertTrue("dist $dist: penultimate point ${pts[n - 2].x} should be settling towards end", pts[n - 2].x <= maxX)
        }
    }
}

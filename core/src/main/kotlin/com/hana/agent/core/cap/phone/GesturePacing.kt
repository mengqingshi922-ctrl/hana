package com.hana.agent.core.cap.phone

import com.hana.agent.core.uistate.DOUBLE_TAP_GAP_MS
import com.hana.agent.core.uistate.Pace
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 手机手势的节奏:手指落下前停一下、点按按住的时长有长有短、落点在元素中心附近略有偏差(始终在元素里)、
 * 滑动沿一小段弧线走、先加速后减速、时长在给定值上下浮动。所有时长与幅度都有上下限([Limits]),一次动作的总时长因此有界。
 * [Instant] 不加任何停顿、偏差和弧度,时长和以前一样(点按 [TAP_MS]、双击间隔 [DOUBLE_TAP_GAP_MS]、滑动按给定时长匀速走直线)。
 */
class GesturePacing(private val random: Random?, val limits: Limits = Limits()) {
    /**
     * 上下限。时长单位毫秒,距离单位像素。
     * [approachMs]:手指落下前的停顿;[pressMs]:单击按住;[doublePressMs] / [doubleGapMs]:双击每下按住、两下之间
     * (系统认双击要两下间隔 40–300 毫秒);[jitterFraction] / [maxJitterPx] / [minJitterSpanPx]:点元素时落点偏离中心
     * 不超过元素宽(高)的这一比例和这么多像素,比 [minJitterSpanPx] 窄(矮)的方向不偏;[pointJitterPx]:按坐标点时的偏差;
     * [durationJitter]:滑动时长上下浮动的比例;[holdStretch]:原地按住(起终点几乎重合,如长按)只加长、不缩短,至多加这一比例;
     * [stillPx]:起终点相距不到这么远算原地按住;[bowFraction] / [maxBowPx]:弧线偏离直线的最大幅度;
     * [sampleMs]:手指移动时相邻两点的时间间隔;[maxPoints]:一笔最多这么多点;
     * [dragMs]:不短于这个时长的滑动当慢拖,末尾减速到停、松手时不甩;更短的当快滑,松手时的速度为平均速度的 [flingEndSpeed] 倍
     * (和以前匀速滑动的松手速度相当,滚动的远近不变)。
     */
    data class Limits(
        val approachMs: LongRange = 30L..120L,
        val pressMs: LongRange = 50L..120L,
        val doublePressMs: LongRange = 40L..90L,
        val doubleGapMs: LongRange = 80L..140L,
        val jitterFraction: Double = 0.2,
        val maxJitterPx: Int = 12,
        val minJitterSpanPx: Int = 24,
        val pointJitterPx: Int = 3,
        val durationJitter: Double = 0.08,
        val holdStretch: Double = 0.12,
        val stillPx: Double = 3.0,
        val bowFraction: Double = 0.06,
        val maxBowPx: Double = 30.0,
        val sampleMs: LongRange = 8L..16L,
        val maxPoints: Int = 600,
        val dragMs: Long = 800L,
        val flingEndSpeed: ClosedFloatingPointRange<Double> = 0.9..1.0,
    )

    /** 一笔里的一个点:相对落下时刻 [t] 毫秒时手指在([x],[y])，以及拟人触控压力与接触面面积。 */
    data class Point(
        val x: Double,
        val y: Double,
        val t: Long,
        val pressure: Float = 0.8f,
        val touchMajor: Float = 12f,
        val touchMinor: Float = 10f,
    )

    /** 一笔:从按下到抬起的点(第一个 t 为 0,最后一个是抬起的时刻和位置)。 */
    data class Stroke(val points: List<Point>) {
        val durationMs: Long get() = points.last().t
    }

    /** 有节奏(不是 [Instant])。 */
    val paced: Boolean get() = random != null

    fun approach(): Long = pick(limits.approachMs, 0L)
    fun press(): Long = pick(limits.pressMs, TAP_MS)
    fun doublePress(): Long = pick(limits.doublePressMs, TAP_MS)
    fun doubleGap(): Long = pick(limits.doubleGapMs, DOUBLE_TAP_GAP_MS)

    private fun pick(r: LongRange, instant: Long): Long = random?.let { Pace.pick(it, r) } ?: instant

    /**
     * 点元素([left],[top])–([right],[bottom])(右、下不含)时的落点:中心(和 Rect.centerX/centerY 一样取整)附近,
     * 偏离不超过宽(高)的 [Limits.jitterFraction] 与 [Limits.maxJitterPx],靠中心的更多;窄的方向不偏。落点总在元素里。
     */
    fun within(left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int> {
        val cx = (left + right) shr 1
        val cy = (top + bottom) shr 1
        val r = random ?: return cx to cy
        fun reach(span: Int) = if (span < limits.minJitterSpanPx) 0 else minOf((span * limits.jitterFraction).toInt(), limits.maxJitterPx)
        return (cx + offset(r, reach(right - left))).coerceIn(left, maxOf(left, right - 1)) to
            (cy + offset(r, reach(bottom - top))).coerceIn(top, maxOf(top, bottom - 1))
    }

    /** 按坐标点时的落点:给定点附近 [Limits.pointJitterPx] 以内(不到 1dp,不会点偏到别的控件);坐标不小于 0。 */
    fun near(x: Int, y: Int): Pair<Int, Int> {
        val r = random ?: return x to y
        return (x + offset(r, limits.pointJitterPx)).coerceAtLeast(0) to (y + offset(r, limits.pointJitterPx)).coerceAtLeast(0)
    }

    // 两个均匀数之和(三角分布):落在中心附近的多,不超过 ±reach
    private fun offset(r: Random, reach: Int): Int =
        if (reach <= 0) 0 else ((r.nextDouble() + r.nextDouble() - 1.0) * reach).roundToInt().coerceIn(-reach, reach)

    /** 几根手指一起动的手势(不经 [stroke] 排点)的时长:给定值上下浮动 [Limits.durationJitter]。 */
    fun duration(durationMs: Long): Long {
        val r = random ?: return durationMs
        return (durationMs * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
    }

    /**
     * 一根手指沿 [path](至少两个点)走 [durationMs] 左右的一笔。起点、终点不变;[bow] 为 true 时两点之间走一小段弧线
     * (只对两个点的路径,多点路径是调用方画好的,照着走)。[bounds] 给了(宽,高)时点都夹在屏内。
     * 起终点几乎重合时是原地按住:只有起点和终点,时长只加长不缩短(长按仍然够长)。
     * [Instant]:点就是 [path],按路径长短匀速分配时间,时长正是 [durationMs]。
     */
    fun stroke(path: List<Pair<Double, Double>>, durationMs: Long, bow: Boolean = false, bounds: Pair<Int, Int>? = null): Stroke {
        require(path.size >= 2) { "a stroke needs at least two points" }
        val total = durationMs.coerceAtLeast(1)
        val r = random ?: return Stroke(uniform(path, total))
        val start = path.first()
        val end = path.last()
        val length = lengthOf(path)
        if (length < limits.stillPx) {
            val hold = (total * (1 + r.nextDouble(0.0, limits.holdStretch))).roundToLong()
            return Stroke(listOf(Point(start.first, start.second, 0), Point(end.first, end.second, hold)))
        }
        val t = (total * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
        val shape = if (bow && path.size == 2) {
            val c = Pace.control(start, end, Pace.bow(r, length, limits.bowFraction, limits.maxBowPx))
            (0..CURVE_SEGMENTS).map { Pace.bezier(start, c, end, it.toDouble() / CURVE_SEGMENTS) }
        } else path
        val endSpeed = if (t >= limits.dragMs) 0.0 else r.nextDouble(limits.flingEndSpeed.start, limits.flingEndSpeed.endInclusive)
        val step = Pace.pick(r, limits.sampleMs).coerceAtLeast(1)
        val n = ceil(t.toDouble() / step).toInt().coerceIn(1, limits.maxPoints - 1)
        val cum = cumulative(shape)
        val points = (0..n).map { i ->
            val tm = if (i == n) t else (t * i.toDouble() / n).roundToLong()
            val (x, y) = when (i) {
                0 -> start
                n -> end
                else -> at(shape, cum, cum.last() * progress(i.toDouble() / n, endSpeed))
            }
            Point(x, y, tm)
        }
        return Stroke(if (bounds == null) points else points.map { it.copy(x = it.x.coerceIn(0.0, bounds.first - 1.0), y = it.y.coerceIn(0.0, bounds.second - 1.0)) })
    }

    /**
     * 三阶非均匀 B 样条拟人动力学轨迹生成：
     * 控制点规划：起点 P0、初速度引导 P1、巡航曲率 P2、终端过冲回正控制点 P3（Δd ≈ 2 ~ 4px）。
     * 采样点叠加 σ = 1.2px 高斯微抖动（Jitter）与动态触控压力 Profile（按下 0.2->0.8，抬起前夕急剧衰减至 0.1）。
     */
    fun strokeCubicBSpline(
        start: Pair<Double, Double>,
        end: Pair<Double, Double>,
        durationMs: Long,
        bounds: Pair<Int, Int>? = null,
        overshootPx: Double = 3.0,
        jitterSigma: Double = 1.2,
    ): Stroke {
        val total = durationMs.coerceAtLeast(1)
        val r = random ?: return Stroke(uniform(listOf(start, end), total))
        val dist = hypot(end.first - start.first, end.second - start.second)
        if (dist < limits.stillPx) {
            val hold = (total * (1 + r.nextDouble(0.0, limits.holdStretch))).roundToLong()
            val p0 = Point(start.first, start.second, 0, pressure = 0.2f, touchMajor = calculateTouchMajor(0.2f), touchMinor = calculateTouchMinor(0.2f))
            val p1 = Point(end.first, end.second, hold, pressure = 0.1f, touchMajor = calculateTouchMajor(0.1f), touchMinor = calculateTouchMinor(0.1f))
            return Stroke(listOf(p0, p1))
        }

        val t = (total * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
        val ux = (end.first - start.first) / dist
        val uy = (end.second - start.second) / dist
        val nx = -uy
        val ny = ux

        val bow = Pace.bow(r, dist, limits.bowFraction, limits.maxBowPx)
        val p0 = start
        val p1 = (start.first + 0.35 * (end.first - start.first) + nx * bow * 0.8) to
                 (start.second + 0.35 * (end.second - start.second) + ny * bow * 0.8)
        val actualOvershoot = overshootPx.coerceIn(2.0, 4.0)
        val p3 = (end.first + ux * actualOvershoot) to (end.second + uy * actualOvershoot)
        val p2Dist = (dist * 0.04).coerceIn(6.0, 16.0)
        val p2 = (p3.first - ux * p2Dist + nx * bow * 0.3) to
                 (p3.second - uy * p2Dist + ny * bow * 0.3)

        val step = Pace.pick(r, limits.sampleMs).coerceAtLeast(1)
        val n = ceil(t.toDouble() / step).toInt().coerceIn(1, limits.maxPoints - 1)
        val avgSpeed = dist / t.toDouble()

        val settleSteps = when {
            n >= 8 -> 3
            n >= 5 -> 2
            else -> 1
        }
        val nMain = n - settleSteps

        val points = mutableListOf<Point>()
        for (i in 0..n) {
            val u = i.toDouble() / n
            val tm = if (i == n) t else (t * u).roundToLong()
            val (baseX, baseY) = when {
                i == 0 -> start
                i == n -> end
                i > nMain -> {
                    val stepFromEnd = n - i
                    val frac = when (settleSteps) {
                        3 -> if (stepFromEnd == 1) 0.25 else 0.70
                        2 -> if (stepFromEnd == 1) 0.35 else 0.70
                        else -> 0.0
                    }
                    val bx = end.first + ux * actualOvershoot * frac
                    val by = end.second + uy * actualOvershoot * frac
                    if (jitterSigma > 0.0) {
                        applyGaussianJitter(r, bx, by, jitterSigma)
                    } else bx to by
                }
                else -> {
                    val uMain = i.toDouble() / nMain
                    val inv = 1.0 - uMain
                    val bx = inv * inv * inv * p0.first + 3 * inv * inv * uMain * p1.first + 3 * inv * uMain * uMain * p2.first + uMain * uMain * uMain * p3.first
                    val by = inv * inv * inv * p0.second + 3 * inv * inv * uMain * p1.second + 3 * inv * uMain * uMain * p2.second + uMain * uMain * uMain * p3.second
                    if (jitterSigma > 0.0) {
                        applyGaussianJitter(r, bx, by, jitterSigma)
                    } else bx to by
                }
            }
            val clampedX = if (bounds != null) baseX.coerceIn(0.0, bounds.first - 1.0) else baseX
            val clampedY = if (bounds != null) baseY.coerceIn(0.0, bounds.second - 1.0) else baseY

            val instSpeed = if (points.isEmpty()) avgSpeed * 0.3 else {
                val last = points.last()
                val dt = (tm - last.t).coerceAtLeast(1)
                hypot(clampedX - last.x, clampedY - last.y) / dt
            }

            val pressure = calculatePressure(tm, t, instSpeed, avgSpeed)
            val touchMajor = calculateTouchMajor(pressure)
            val touchMinor = calculateTouchMinor(pressure)

            points += Point(clampedX, clampedY, tm, pressure, touchMajor, touchMinor)
        }
        return Stroke(points)
    }

    companion object {
        /**
         * 动态触控压力 Profile 计算:
         * 1. 按下瞬间 (Down, 0..15ms): 压力从 0.2 快速爬升至 0.8;
         * 2. 滑动中 (距抬手 > 20ms): 依瞬时速度与均速比值反比微调 (0.65f ~ 0.85f);
         * 3. 抬起前夕 (Up, 最后 20ms 内): 急剧衰减至 0.1f。
         */
        fun calculatePressure(t: Long, durationMs: Long, speed: Double = 0.0, avgSpeed: Double = 0.0): Float {
            if (durationMs <= 0L) return 0.8f
            val remain = durationMs - t
            return when {
                t <= 15L -> {
                    val progress = (t.toDouble() / 15.0).coerceIn(0.0, 1.0)
                    (0.2 + 0.6 * progress).toFloat()
                }
                remain <= 20L -> {
                    val progress = (remain.toDouble() / 20.0).coerceIn(0.0, 1.0)
                    (0.1 + 0.7 * progress).toFloat()
                }
                else -> {
                    if (avgSpeed > 0.0) {
                        val ratio = (speed / avgSpeed).coerceIn(0.5, 2.0)
                        (0.8 - (ratio - 1.0) * 0.1).toFloat().coerceIn(0.65f, 0.85f)
                    } else 0.8f
                }
            }
        }

        fun calculateTouchMajor(pressure: Float): Float = 10f + pressure * 2.5f
        fun calculateTouchMinor(pressure: Float): Float = 8f + pressure * 2.5f

        /**
         * 采样点叠加白噪声高斯微抖动 (Gaussian Hand Jitter, \sigma = 1.2px)
         */
        fun applyGaussianJitter(r: Random, x: Double, y: Double, sigma: Double = 1.2): Pair<Double, Double> {
            val u1 = r.nextDouble().coerceAtLeast(1e-7)
            val u2 = r.nextDouble()
            val mag = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * sigma
            val dx = mag * kotlin.math.cos(2.0 * Math.PI * u2)
            val dy = mag * kotlin.math.sin(2.0 * Math.PI * u2)
            return (x + dx) to (y + dy)
        }

        /** 不加节奏时点按按住的时长。 */
        const val TAP_MS = 50L

        /** 弧线拆成这么多小段再按时间取点。 */
        private const val CURVE_SEGMENTS = 32

        /** 不加停顿、偏差和弧度。 */
        val Instant = GesturePacing(null)

        /** 像人手的节奏;[random] 可换成固定种子(测试)。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = GesturePacing(random, limits)

        /**
         * 极速手势物理注入标定常量:
         * 靠近停顿标定 15ms，按住时长标定 20ms，单次物理点击注入总耗时标定为 35ms。
         */
        const val FAST_APPROACH_MS = 15L
        const val FAST_PRESS_MS = 20L
        const val FAST_CLICK_TOTAL_MS = 35L

        /** 标定 35ms 极速手势上下限 (靠近 15ms、按住 20ms) */
        val Click35msLimits = Limits(
            approachMs = 15L..15L,
            pressMs = 20L..20L,
            doublePressMs = 20L..20L,
            doubleGapMs = 35L..50L,
        )

        /**
         * 快速手势注入上下限:常规按键时长与靠近停顿标定为：
         * 靠近停顿 15ms（10~20ms）、按键时长（30~50ms，典型/极速路径 20ms），
         * 为高频调用提供高速物理触摸派发路径。
         */
        val FastLimits = Limits(
            approachMs = 10L..20L,
            pressMs = 30L..50L,
            doublePressMs = 30L..50L,
            doubleGapMs = 40L..80L,
        )

        /** 快速拟真手势节奏 */
        fun fast(random: Random = Random.Default, limits: Limits = FastLimits) = GesturePacing(random, limits)

        /** 快速手势节奏实例/别名 */
        val Fast = fast()
        val FastPacing = Fast

        /**
         * 时间走到 [u](0..1)时走过的路程比例:三次 Hermite,起步速度 0,收尾速度为平均速度的 [endSpeed] 倍(0..3 之间单调)。
         * [endSpeed] 为 0 时两头慢中间快、停稳再抬手;为 1 时松手速度和匀速一样。
         */
        internal fun progress(u: Double, endSpeed: Double): Double {
            val u2 = u * u
            val u3 = u2 * u
            return (-2 * u3 + 3 * u2) + endSpeed * (u3 - u2)
        }

        private fun lengthOf(path: List<Pair<Double, Double>>): Double = cumulative(path).last()

        private fun cumulative(path: List<Pair<Double, Double>>): DoubleArray {
            val out = DoubleArray(path.size)
            for (i in 1 until path.size) out[i] = out[i - 1] + hypot(path[i].first - path[i - 1].first, path[i].second - path[i - 1].second)
            return out
        }

        /** 沿折线走过 [s] 时的位置。 */
        private fun at(path: List<Pair<Double, Double>>, cum: DoubleArray, s: Double): Pair<Double, Double> {
            var i = 1
            while (i < path.size - 1 && cum[i] < s) i++
            val seg = cum[i] - cum[i - 1]
            val f = if (seg <= 0) 1.0 else ((s - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
            val (x0, y0) = path[i - 1]
            val (x1, y1) = path[i]
            return x0 + (x1 - x0) * f to y0 + (y1 - y0) * f
        }

        /** 不加节奏时的一笔:就是给定的点,时间按路程匀速分配(和系统按一条路径匀速走的一样)。 */
        private fun uniform(path: List<Pair<Double, Double>>, total: Long): List<Point> {
            val cum = cumulative(path)
            val length = cum.last()
            return path.mapIndexed { i, (x, y) ->
                val t = when {
                    i == path.lastIndex -> total
                    length <= 0 -> 0L
                    else -> (total * cum[i] / length).roundToLong()
                }
                Point(x, y, t)
            }
        }
    }
}

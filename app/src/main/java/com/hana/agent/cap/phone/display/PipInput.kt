package com.hana.agent.cap.phone.display

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.hana.agent.shell.IHanaShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 虚拟屏小窗（PiP）直接手势交互与反向注入接口。
 * 允许用户在悬浮小窗中直接用手指操作正在后台虚拟屏上运行的任务。
 */
interface PipInput {
    /**
     * 在虚拟屏指定归一化相对比例坐标 (fx, fy) 处执行单指点击。
     * [fx]、[fy] 相对比例取值范围为 [0f, 1f]。
     */
    suspend fun tap(fx: Float, fy: Float)

    /**
     * 在虚拟屏上执行单指连续滑动。
     * [points] 为归一化相对比例坐标序列，每个点的坐标均为 [0f, 1f]。
     * [durationMs] 为滑动总耗时（毫秒）。
     */
    suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long)

    /**
     * 向虚拟屏注入系统返回键 (KEYCODE_BACK)。
     */
    suspend fun back()

    companion object {
        const val TAG = "PipInput"

        /**
         * 将相对比例归一化坐标 [fx] 转换为逻辑/物理屏幕上的实际 X 像素坐标。
         */
        fun toPixelX(fx: Float, width: Int): Int {
            if (width <= 0) return 0
            return (fx.coerceIn(0f, 1f) * width).roundToInt().coerceIn(0, width - 1)
        }

        /**
         * 将相对比例归一化坐标 [fy] 转换为逻辑/物理屏幕上的实际 Y 像素坐标。
         */
        fun toPixelY(fy: Float, height: Int): Int {
            if (height <= 0) return 0
            return (fy.coerceIn(0f, 1f) * height).roundToInt().coerceIn(0, height - 1)
        }
    }
}

/**
 * [PipInput] 的底层实现类，绑定 [IHanaShell] 并根据目标虚拟屏幕尺寸执行坐标换算与输入注入。
 */
class DisplayPipInput(
    private val shell: IHanaShell,
    private val displayIdProvider: () -> Int,
    private val widthProvider: () -> Int,
    private val heightProvider: () -> Int,
) : PipInput {

    constructor(
        shell: IHanaShell,
        displayId: Int,
        width: Int,
        height: Int,
    ) : this(shell, { displayId }, { width }, { height })

    constructor(shell: IHanaShell, display: AgentDisplay) : this(
        shell,
        { display.displayId },
        { display.width },
        { display.height },
    )

    override suspend fun tap(fx: Float, fy: Float) = withContext(Dispatchers.IO) {
        val displayId = displayIdProvider()
        val width = widthProvider()
        val height = heightProvider()
        if (displayId < 0 || width <= 0 || height <= 0) {
            runCatching { Log.w(PipInput.TAG, "tap ignored: invalid display $displayId ($width x $height)") }
            return@withContext
        }
        val px = PipInput.toPixelX(fx, width)
        val py = PipInput.toPixelY(fy, height)
        runCatching {
            shell.injectTap(displayId, px, py)
        }.onFailure { e ->
            runCatching { Log.e(PipInput.TAG, "shell.injectTap failed on display $displayId at ($px, $py)", e) }
        }
        Unit
    }

    override suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) = withContext(Dispatchers.IO) {
        val displayId = displayIdProvider()
        val width = widthProvider()
        val height = heightProvider()
        if (displayId < 0 || width <= 0 || height <= 0 || points.isEmpty()) {
            runCatching { Log.w(PipInput.TAG, "swipe ignored: invalid display $displayId ($width x $height) or empty points") }
            return@withContext
        }

        // 底层 Shell.injectStroke 要求至少 2 个点；若只有 1 个点则退化为点击
        if (points.size == 1) {
            tap(points[0].first, points[0].second)
            return@withContext
        }

        // 下采样防止超出底层限制 (1024 点)
        val sampledPoints = if (points.size > MAX_STROKE_POINTS) {
            val step = points.size.toFloat() / (MAX_STROKE_POINTS - 1)
            List(MAX_STROKE_POINTS) { i ->
                val index = (i * step).roundToInt().coerceIn(0, points.size - 1)
                points[index]
            }
        } else {
            points
        }

        val n = sampledPoints.size
        val xs = IntArray(n) { PipInput.toPixelX(sampledPoints[it].first, width) }
        val ys = IntArray(n) { PipInput.toPixelY(sampledPoints[it].second, height) }
        val totalMs = durationMs.coerceIn(10L, MAX_STROKE_MS)

        // 构造单调递增的时间轴：times[0] 必须为 0L
        val times = LongArray(n) { i ->
            (i.toLong() * totalMs / (n - 1)).coerceAtLeast(0L)
        }
        for (i in 1 until n) {
            if (times[i] < times[i - 1]) {
                times[i] = times[i - 1]
            }
        }

        runCatching {
            shell.injectStroke(displayId, xs, ys, times)
        }.onFailure { e ->
            runCatching { Log.e(PipInput.TAG, "shell.injectStroke failed on display $displayId", e) }
        }
        Unit
    }

    override suspend fun back() = withContext(Dispatchers.IO) {
        val displayId = displayIdProvider()
        if (displayId < 0) {
            runCatching { Log.w(PipInput.TAG, "back ignored: invalid display $displayId") }
            return@withContext
        }
        runCatching {
            shell.injectKey(displayId, KeyEvent.KEYCODE_BACK)
        }.onFailure { e ->
            runCatching { Log.e(PipInput.TAG, "shell.injectKey(KEYCODE_BACK) failed on display $displayId", e) }
        }
        Unit
    }

    companion object {
        private const val MAX_STROKE_POINTS = 1_000
        private const val MAX_STROKE_MS = 10_000L
    }
}

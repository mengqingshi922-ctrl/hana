package com.hana.agent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.hana.agent.ui.theme.HanaTheme
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 手势可视化事件模型：承载点击与多点轨迹滑动动画数据。
 * 坐标均使用 [0f, 1f] 归一化屏幕比例，自适应各种悬浮小窗尺寸与分辨率。
 */
sealed interface VisualTouchEvent {
    val id: Long
    val timestampMs: Long

    /** 单点点击/敲击可视化事件 */
    data class Tap(
        override val id: Long,
        val normX: Float,
        val normY: Float,
        override val timestampMs: Long = System.currentTimeMillis(),
    ) : VisualTouchEvent

    /** 多点连续笔划/滑动手势可视化事件 */
    data class Stroke(
        override val id: Long,
        val points: List<Pair<Float, Float>>,
        val durationMs: Long,
        override val timestampMs: Long = System.currentTimeMillis(),
    ) : VisualTouchEvent
}

/**
 * 跨 App 手势可视化全局非阻塞事件总线：
 * 采用背压丢弃最旧策略 (BufferOverflow.DROP_OLDEST)，零挂起发布，保障主交互与 Shell 线程安全。
 */
object TouchVisualBus {
    private val seq = AtomicLong(1L)
    private val _events = MutableSharedFlow<VisualTouchEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<VisualTouchEvent> = _events.asSharedFlow()

    const val TAP_DURATION_NORMAL_MS = 320L
    const val TAP_DURATION_REDUCED_MS = 160L
    const val STROKE_FADE_EXTRA_MS = 200L
    const val STROKE_MIN_DURATION_MS = 450L
    const val STROKE_REDUCED_MS = 180L

    /** 坐标归一化安全函数：具备除零保护与 [0f, 1f] 边界 Clamp */
    fun normalize(value: Float, maxDim: Int): Float {
        if (maxDim <= 0) return 0f
        return (value / maxDim.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * 判断事件在给定时间戳 [nowMs] 下是否仍处于活跃动画周期内。
     */
    fun isEventActive(event: VisualTouchEvent, nowMs: Long, reduceMotion: Boolean = false): Boolean {
        val elapsed = nowMs - event.timestampMs
        if (elapsed < 0L) return true
        val totalDuration = when (event) {
            is VisualTouchEvent.Tap -> if (reduceMotion) TAP_DURATION_REDUCED_MS else TAP_DURATION_NORMAL_MS
            is VisualTouchEvent.Stroke -> if (reduceMotion) STROKE_REDUCED_MS else max(STROKE_MIN_DURATION_MS, event.durationMs + STROKE_FADE_EXTRA_MS)
        }
        return elapsed < totalDuration
    }

    /**
     * 过滤并剔除已过期事件（纯逻辑函数，便于单元测试脱机验证）。
     */
    fun pruneExpiredEvents(events: List<VisualTouchEvent>, nowMs: Long, reduceMotion: Boolean = false): List<VisualTouchEvent> {
        return events.filter { isEventActive(it, nowMs, reduceMotion) }
    }

    /**
     * 广播点击事件：将物理像素坐标转换为归一化比例并安全发布。
     */
    fun emitTap(x: Float, y: Float, screenW: Int, screenH: Int, timestampMs: Long = System.currentTimeMillis()): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        val normX = normalize(x, screenW)
        val normY = normalize(y, screenH)
        val event = VisualTouchEvent.Tap(
            id = seq.getAndIncrement(),
            normX = normX,
            normY = normY,
            timestampMs = timestampMs,
        )
        return _events.tryEmit(event)
    }

    /**
     * 广播滑动笔划事件：将点序列转换为归一化比例并安全发布。
     */
    fun emitStroke(
        xs: List<Float>,
        ys: List<Float>,
        screenW: Int,
        screenH: Int,
        durationMs: Long,
        timestampMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        if (xs.isEmpty() || xs.size != ys.size) return false
        val points = xs.indices.map { i ->
            Pair(normalize(xs[i], screenW), normalize(ys[i], screenH))
        }
        val event = VisualTouchEvent.Stroke(
            id = seq.getAndIncrement(),
            points = points,
            durationMs = durationMs.coerceAtLeast(1L),
            timestampMs = timestampMs,
        )
        return _events.tryEmit(event)
    }

    /** 直接发射已构建事件（主要用于测试及自定义扩展） */
    fun emitEvent(event: VisualTouchEvent): Boolean {
        return _events.tryEmit(event)
    }

    /** 重置 ID 计数器，供单元测试隔离使用 */
    fun resetForTesting() {
        seq.set(1L)
    }
}

/**
 * 悬浮手势可视化遮罩层：
 * 纯自绘 Canvas，不消耗/不拦截任何触摸指针。在小窗或虚拟屏上层渲染 Hana 触控水波纹与滑动彗星拖尾。
 * 空闲时停止帧循环，零 CPU 占用；仅在有活跃手势动画时通过 withFrameNanos 按需驱动。
 */
@Composable
fun VisualTouchOverlay(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reduceMotion: Boolean = false,
) {
    if (!enabled) return

    val activeEvents = remember { mutableStateListOf<VisualTouchEvent>() }
    var frameTrigger by remember { mutableLongStateOf(0L) }
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val accentColor = runCatching { HanaTheme.colors.accent }.getOrDefault(Color(0xFF00E5FF))

    LaunchedEffect(enabled, currentReduceMotion) {
        // 协程 1：非阻塞监听新事件
        launch {
            TouchVisualBus.events.collect { event ->
                activeEvents.add(event)
            }
        }
        // 协程 2：当存在活跃事件时按需驱动 withFrameNanos 帧循环，清空后自动挂起休眠
        snapshotFlow { activeEvents.isNotEmpty() }
            .filter { it }
            .collectLatest {
                while (activeEvents.isNotEmpty()) {
                    withFrameNanos { frameNanos ->
                        frameTrigger = frameNanos
                    }
                    val now = System.currentTimeMillis()
                    activeEvents.removeAll { event ->
                        !TouchVisualBus.isEventActive(event, now, currentReduceMotion)
                    }
                }
            }
    }

    Canvas(modifier = modifier) {
        // 读取 frameTrigger 建立 Compose 绘制阶段订阅
        if (frameTrigger < 0L) return@Canvas
        if (activeEvents.isEmpty()) return@Canvas

        val now = System.currentTimeMillis()
        for (event in activeEvents) {
            when (event) {
                is VisualTouchEvent.Tap -> drawTapEffect(event, now, currentReduceMotion, accentColor)
                is VisualTouchEvent.Stroke -> drawStrokeEffect(event, now, currentReduceMotion, accentColor)
            }
        }
    }
}

private fun DrawScope.drawTapEffect(
    event: VisualTouchEvent.Tap,
    now: Long,
    reduceMotion: Boolean,
    accentColor: Color,
) {
    val elapsed = (now - event.timestampMs).coerceAtLeast(0L)
    val center = Offset(event.normX * size.width, event.normY * size.height)

    if (reduceMotion) {
        // 减弱动效模式：瞬时高亮静态标记，平滑淡出
        val totalMs = TouchVisualBus.TAP_DURATION_REDUCED_MS.toFloat()
        val p = (elapsed / totalMs).coerceIn(0f, 1f)
        val alpha = (1f - p * 0.7f).coerceIn(0f, 1f)
        drawCircle(
            color = accentColor.copy(alpha = alpha * 0.8f),
            radius = 12.dp.toPx(),
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = alpha),
            radius = 4.dp.toPx(),
            center = center,
        )
        return
    }

    // 常规模式：双层柔光水波纹扩散环 + 中心脉冲高光点
    val totalMs = TouchVisualBus.TAP_DURATION_NORMAL_MS.toFloat()
    val p = (elapsed / totalMs).coerceIn(0f, 1f)
    // 三次方减速曲线：展开迅速，末尾柔和消散
    val easeOut = 1f - (1f - p) * (1f - p) * (1f - p)

    // 1. 外层水波纹扩展环
    val outerR = 6.dp.toPx() + 32.dp.toPx() * easeOut
    val outerAlpha = ((1f - p) * 0.7f).coerceIn(0f, 1f)
    val outerStrokeWidth = (2.5.dp.toPx() * (1f - p * 0.4f)).coerceAtLeast(1f)
    drawCircle(
        color = accentColor.copy(alpha = outerAlpha),
        radius = outerR,
        center = center,
        style = Stroke(width = outerStrokeWidth),
    )

    // 2. 内层紧凑柔光环
    val innerR = 4.dp.toPx() + 18.dp.toPx() * easeOut
    val innerAlpha = ((1f - p) * 0.9f).coerceIn(0f, 1f)
    val innerStrokeWidth = (2.dp.toPx() * (1f - p * 0.3f)).coerceAtLeast(1f)
    drawCircle(
        color = accentColor.copy(alpha = innerAlpha),
        radius = innerR,
        center = center,
        style = Stroke(width = innerStrokeWidth),
    )

    // 3. 中心脉冲水晶点
    val coreR = (4.dp.toPx() * (1f - p * 0.4f)).coerceAtLeast(1.5f)
    val coreAlpha = (1f - p * 0.6f).coerceIn(0f, 1f)
    drawCircle(
        color = accentColor.copy(alpha = coreAlpha),
        radius = coreR,
        center = center,
    )
    drawCircle(
        color = Color.White.copy(alpha = (1f - p) * 0.75f),
        radius = (coreR * 0.6f).coerceAtLeast(1f),
        center = center,
    )
}

private fun DrawScope.drawStrokeEffect(
    event: VisualTouchEvent.Stroke,
    now: Long,
    reduceMotion: Boolean,
    accentColor: Color,
) {
    if (event.points.isEmpty()) return
    val pts = event.points.map { Offset(it.first * size.width, it.second * size.height) }
    val elapsed = (now - event.timestampMs).coerceAtLeast(0L)

    if (reduceMotion) {
        val totalMs = TouchVisualBus.STROKE_REDUCED_MS.toFloat()
        val p = (elapsed / totalMs).coerceIn(0f, 1f)
        val alpha = ((1f - p) * 0.8f).coerceIn(0f, 1f)
        if (pts.size == 1) {
            drawCircle(color = accentColor.copy(alpha = alpha), radius = 6.dp.toPx(), center = pts[0])
            return
        }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                lineTo(pts[i].x, pts[i].y)
            }
        }
        drawPath(
            path = path,
            color = accentColor.copy(alpha = alpha),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        return
    }

    val totalMs = max(TouchVisualBus.STROKE_MIN_DURATION_MS, event.durationMs + TouchVisualBus.STROKE_FADE_EXTRA_MS).toFloat()
    val p = (elapsed / totalMs).coerceIn(0f, 1f)

    // 手势滑动期间保持高亮；滑动结束后渐隐
    val globalAlpha = if (elapsed < event.durationMs) {
        0.92f
    } else {
        val fadeProgress = ((elapsed - event.durationMs).toFloat() / (totalMs - event.durationMs).coerceAtLeast(1f)).coerceIn(0f, 1f)
        ((1f - fadeProgress) * 0.92f).coerceIn(0f, 1f)
    }

    if (pts.size == 1) {
        drawCircle(
            color = accentColor.copy(alpha = globalAlpha),
            radius = 6.dp.toPx(),
            center = pts[0],
        )
        return
    }

    // 构造平滑路径（贝塞尔平滑）
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        if (pts.size == 2) {
            lineTo(pts[1].x, pts[1].y)
        } else {
            for (i in 1 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                val midX = (p0.x + p1.x) / 2f
                val midY = (p0.y + p1.y) / 2f
                quadraticBezierTo(p0.x, p0.y, midX, midY)
            }
            lineTo(pts.last().x, pts.last().y)
        }
    }

    // 从起点到终点的渐变发光笔刷：起点淡化尾迹，终点明亮
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = globalAlpha * 0.2f),
            accentColor.copy(alpha = globalAlpha * 0.7f),
            accentColor.copy(alpha = globalAlpha),
        ),
        start = pts.first(),
        end = pts.last(),
    )

    // 1. 外层发光柔晕
    drawPath(
        path = path,
        brush = gradientBrush,
        style = Stroke(
            width = 8.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    // 2. 核心高亮轨迹线
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = globalAlpha * 0.3f),
                Color.White.copy(alpha = globalAlpha * 0.9f),
            ),
            start = pts.first(),
            end = pts.last(),
        ),
        style = Stroke(
            width = 3.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    // 3. 终点水滴圆头
    drawCircle(
        color = accentColor.copy(alpha = globalAlpha),
        radius = 4.5.dp.toPx(),
        center = pts.last(),
    )
    drawCircle(
        color = Color.White.copy(alpha = globalAlpha * 0.9f),
        radius = 2.5.dp.toPx(),
        center = pts.last(),
    )
}

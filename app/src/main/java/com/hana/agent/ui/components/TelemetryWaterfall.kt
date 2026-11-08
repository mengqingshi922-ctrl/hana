package com.hana.agent.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.agent.ui.theme.MikaShapes
import com.hana.agent.ui.theme.MikaTheme

/**
 * 执行链路单阶段时延度量数据模型
 */
data class TelemetryStage(
    val name: String,         // Perceive, Edge Filter, Cloud TTFT, Inject
    val durationMs: Long,     // 耗时毫秒
    val colorHex: Long,       // 对应视觉标识色
)

/**
 * 毫秒级执行时序瀑布流组件 (Telemetry Waterfall)：
 * 直观呈现 [Perceive] -> [Edge Filter] -> [Cloud TTFT] -> [Inject] 四阶段时延开销与占比。
 */
@Composable
fun TelemetryWaterfall(
    stages: List<TelemetryStage>,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val totalMs = stages.sumOf { it.durationMs }.coerceAtLeast(1L)
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .testTag("telemetry-waterfall")
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.edge, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 头部统计栏
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF3B82F6)))
                Text(
                    "执行时序瀑布流",
                    style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    color = c.ink,
                )
            }
            Text(
                "全链路耗时: ${totalMs}ms",
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = c.ink2,
            )
        }

        // 连贯时序比例进度条
        Row(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(MikaShapes.Pill)
                .background(c.fill),
        ) {
            stages.forEach { stage ->
                val weight = (stage.durationMs.toFloat() / totalMs).coerceIn(0.01f, 1f)
                Box(
                    Modifier
                        .weight(weight)
                        .height(8.dp)
                        .background(Color(stage.colorHex)),
                )
            }
        }

        // 四阶段详细耗时卡片网格
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            stages.forEach { stage ->
                val pct = (stage.durationMs.toFloat() / totalMs * 100f).toInt()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(stage.colorHex)))
                        Text(
                            stage.name,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            color = c.ink,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "${stage.durationMs}ms",
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            color = Color(stage.colorHex),
                        )
                        Text(
                            "($pct%)",
                            style = TextStyle(fontSize = 11.sp),
                            color = c.ink3,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Canvas 神经核反射脉冲指示器 (Edge Pulse Chart)：
 * 实时跳动 10ms~18ms 波动折线与电平微光指示灯。
 */
@Composable
fun EdgePulseChart(
    historyLatenciesMs: List<Float>,
    isReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(12.dp)

    // 电平指示灯呼吸微光动画
    val transition = rememberInfiniteTransition(label = "EdgePulseGlow")
    val ledAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ledAlpha",
    )

    // 默认基准波动序列 (10ms ~ 18ms)
    val points = if (historyLatenciesMs.isNotEmpty()) historyLatenciesMs else listOf(12f, 14.5f, 11f, 15.2f, 13f, 16.8f, 12.4f)
    val latestMs = points.lastOrNull() ?: 12f
    val activeColor = Color(0xFF10B981) // 翡翠绿
    val standbyColor = Color(0xFF6B7280) // 待机灰

    Column(
        modifier = modifier
            .testTag("edge-pulse-chart")
            .fillMaxWidth()
            .clip(shape)
            .background(c.fill)
            .border(1.dp, c.edge, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 电平指示灯与时延标签
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 呼吸电平微光灯
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isReady) activeColor.copy(alpha = ledAlpha) else standbyColor,
                        ),
                )
                Text(
                    if (isReady) "端侧神经核反射 ⚡" else "端侧待机中 (云端兜底)",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = if (isReady) c.ink else c.ink3,
                )
            }

            Text(
                "%.1f ms".format(latestMs),
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                color = if (isReady) activeColor else standbyColor,
            )
        }

        // Canvas 动态波动折线与半透明渐变面积
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .testTag("edge-pulse-canvas"),
        ) {
            val width = size.width
            val height = size.height
            if (points.size < 2 || width <= 0f || height <= 0f) return@Canvas

            val minVal = 8f
            val maxVal = 22f
            val range = (maxVal - minVal).coerceAtLeast(1f)

            val stepX = width / (points.size - 1)
            val path = Path()
            val fillPath = Path()

            points.forEachIndexed { i, latency ->
                val normY = (1f - ((latency - minVal) / range).coerceIn(0f, 1f))
                val x = i * stepX
                val y = normY * (height - 8f) + 4f

                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, height)
                    fillPath.lineTo(x, y)
                } else {
                    val prevX = (i - 1) * stepX
                    val prevY = (1f - ((points[i - 1] - minVal) / range).coerceIn(0f, 1f)) * (height - 8f) + 4f
                    val midX = (prevX + x) / 2f
                    path.cubicTo(midX, prevY, midX, y, x, y)
                    fillPath.cubicTo(midX, prevY, midX, y, x, y)
                }
            }

            fillPath.lineTo(width, height)
            fillPath.close()

            val lineColor = if (isReady) activeColor else standbyColor
            // 绘制渐变下方填充
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent),
                    startY = 0f,
                    endY = height,
                ),
            )

            // 绘制主折线
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // 绘制最新点的发光微光圆点
            val lastX = width
            val lastY = (1f - ((points.last() - minVal) / range).coerceIn(0f, 1f)) * (height - 8f) + 4f
            drawCircle(
                color = lineColor.copy(alpha = 0.4f),
                radius = 5.dp.toPx(),
                center = Offset(lastX, lastY),
            )
            drawCircle(
                color = lineColor,
                radius = 2.5.dp.toPx(),
                center = Offset(lastX, lastY),
            )
        }
    }
}

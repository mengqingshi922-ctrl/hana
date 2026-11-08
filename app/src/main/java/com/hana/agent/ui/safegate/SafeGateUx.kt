package com.hana.agent.ui.safegate

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.agent.ui.theme.MikaShapes
import com.hana.agent.ui.theme.MikaTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 安全接管门触觉马达振动管理器 (Safe Gate Haptics)
 * 统一委托迁移至全局四阶触觉中枢 [com.hana.agent.ui.haptic.HapticManager]
 */
object SafeGateHaptics {
    fun triggerClickThump(context: Context) {
        com.hana.agent.ui.haptic.HapticManager.trigger(context, com.hana.agent.ui.haptic.HapticPattern.GateWarning)
    }

    fun triggerGateWarning(context: Context) {
        com.hana.agent.ui.haptic.HapticManager.trigger(context, com.hana.agent.ui.haptic.HapticPattern.GateWarning)
    }

    fun triggerElasticSnap(context: Context) {
        com.hana.agent.ui.haptic.HapticManager.trigger(context, com.hana.agent.ui.haptic.HapticPattern.ElasticSnap)
    }

    fun triggerLightTick(context: Context) {
        com.hana.agent.ui.haptic.HapticManager.trigger(context, com.hana.agent.ui.haptic.HapticPattern.LightTick)
    }

    fun triggerSuccessBloom(context: Context) {
        com.hana.agent.ui.haptic.HapticManager.trigger(context, com.hana.agent.ui.haptic.HapticPattern.SuccessBloom)
    }
}

/**
 * 人机协同安全接管门 UX：
 * 杜绝轻击误触的滑动放行组件，配合柔和琥珀色呼吸光晕与物理阻尼手感。
 */
@Composable
fun SlideToAuthorize(
    onAuthorized: () -> Unit,
    onRejected: () -> Unit,
    title: String,
    riskDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = MikaTheme.colors
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(20.dp)

    // 琥珀色呼吸光晕动效
    val transition = rememberInfiniteTransition(label = "SafeGateHalo")
    val haloPulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloPulse",
    )

    val amberColor = Color(0xFFF59E0B)

    Column(
        modifier = modifier
            .testTag("safe-gate-card")
            .fillMaxWidth()
            .drawBehind {
                val strokePx = 4.dp.toPx()
                drawRoundRect(
                    color = amberColor.copy(alpha = haloPulse * 0.45f),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                    style = Stroke(width = strokePx),
                )
            }
            .clip(shape)
            .background(c.surface)
            .border(1.dp, amberColor.copy(alpha = 0.5f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 头部警示标与标题
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(amberColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("⚠️", style = TextStyle(fontSize = 18.sp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title.ifEmpty { "安全授权确认" },
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = c.ink,
                )
                Text(
                    "高危操作安全门禁",
                    style = TextStyle(fontSize = 11.5.sp),
                    color = amberColor,
                )
            }
        }

        // 风险说明
        Text(
            riskDescription.ifEmpty { "智能体即将执行关键敏感动作，请仔细核对后滑动确认放行。" },
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            color = c.ink2,
        )

        // 滑动确认轨道区域
        var trackWidthPx by remember { mutableFloatStateOf(0f) }
        val thumbSize = 48.dp
        val density = LocalDensity.current
        val thumbSizePx = with(density) { thumbSize.toPx() }
        val maxDragPx = (trackWidthPx - thumbSizePx).coerceAtLeast(0f)
        val dragOffset = remember { Animatable(0f) }

        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(MikaShapes.Pill)
                .background(c.fill)
                .border(1.dp, c.edge, MikaShapes.Pill)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .testTag("slide-track"),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 背景引导文字
            val dragFraction = if (maxDragPx > 0) (dragOffset.value / maxDragPx).coerceIn(0f, 1f) else 0f
            Text(
                "向右滑动确认放行 ❯❯❯",
                style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                color = amberColor.copy(alpha = (1f - dragFraction).coerceIn(0.2f, 1f)),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            // 滑块拇指
            Box(
                Modifier
                    .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
                    .padding(4.dp)
                    .size(thumbSize)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(amberColor)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                val prev = dragOffset.value
                                val next = (prev + delta).coerceIn(0f, maxDragPx)
                                val threshold = maxDragPx * 0.85f
                                if (prev < threshold && next >= threshold && maxDragPx > 0) {
                                    SafeGateHaptics.triggerGateWarning(context)
                                }
                                dragOffset.snapTo(next)
                            }
                        },
                        onDragStopped = {
                            if (maxDragPx > 0 && dragOffset.value >= maxDragPx * 0.85f) {
                                // 达到 85% 阈值，成功授权
                                scope.launch {
                                    dragOffset.animateTo(maxDragPx, tween(100))
                                    SafeGateHaptics.triggerClickThump(context)
                                    onAuthorized()
                                }
                            } else {
                                // 未达阈值，弹性回弹
                                scope.launch {
                                    dragOffset.animateTo(0f, tween(250))
                                }
                            }
                        },
                    )
                    .testTag("slide-thumb"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "➔",
                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }

        // 底部拒绝按钮
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRejected)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("safe-gate-reject"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "拒绝执行",
                    style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    color = c.ink3,
                )
            }
        }
    }
}

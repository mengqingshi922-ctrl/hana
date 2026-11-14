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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.agent.ui.theme.HanaShapes
import com.hana.agent.ui.theme.HanaTheme
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

internal const val SLIDE_AUTHORIZE_THRESHOLD = 0.85f
internal const val SLIDE_DAMPING_FACTOR = 0.85f

/**
 * 人机协同安全接管门 UX（L3 强阻尼滑动门禁）：
 * 杜绝轻击误触的 85% 阻尼滑动放行组件，配合柔和琥珀色呼吸光晕、防注入红/黄牌警告与物理阻尼手感。
 */
@Composable
fun SlideToAuthorize(
    onAuthorized: () -> Unit,
    onRejected: () -> Unit,
    title: String,
    riskDescription: String,
    modifier: Modifier = Modifier,
    fromUntrustedSource: Boolean = false,
    targetLabel: String? = null,
    contentLabel: String? = null,
    impactLabel: String? = null,
) {
    val context = LocalContext.current
    val c = HanaTheme.colors
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
    val emeraldColor = Color(0xFF10B981)
    val untrustedBg = Color(0xFFFEF3C7)
    val untrustedBorder = Color(0xFFD97706)
    val untrustedInk = Color(0xFF78350F)

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
            .border(1.dp, amberColor.copy(alpha = 0.55f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (fromUntrustedSource) {
            Box(
                Modifier
                    .testTag("safe-gate-untrusted-banner")
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(untrustedBg)
                    .border(1.dp, untrustedBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    "⚠️ 该操作指令源于外部网页/群聊内容，请确认是否本人意图",
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = untrustedInk,
                )
            }
        }

        // 头部警示标与标题
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(amberColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("⚠️", style = TextStyle(fontSize = 16.sp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title.ifEmpty { "安全授权确认" },
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "L3 高危操作强阻尼门禁 · 需滑至 85% 以上",
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = amberColor,
                )
            }
        }

        // 业务三要素账单摘要（若传入则结构化展示，否则展示风险说明）
        if (!targetLabel.isNullOrBlank() || !contentLabel.isNullOrBlank() || !impactLabel.isNullOrBlank()) {
            Column(
                Modifier
                    .testTag("safe-gate-bill")
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.fill)
                    .border(0.5.dp, c.edge, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                targetLabel?.takeIf { it.isNotBlank() }?.let { BillLine("操作对象", it, c.ink) }
                contentLabel?.takeIf { it.isNotBlank() }?.let { BillLine("具体内容", it, c.ink2) }
                impactLabel?.takeIf { it.isNotBlank() }?.let { BillLine("金额/后果", it, amberColor) }
            }
        } else {
            Text(
                riskDescription.ifEmpty { "智能体即将执行关键敏感动作，请仔细核对后滑动确认放行。" },
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                color = c.ink2,
            )
        }

        // 滑动确认轨道区域（85% 阻尼阈值）
        var trackWidthPx by remember { mutableFloatStateOf(0f) }
        var settled by remember { mutableStateOf(false) }
        val thumbSize = 46.dp
        val density = LocalDensity.current
        val thumbSizePx = with(density) { thumbSize.toPx() }
        val maxDragPx = (trackWidthPx - thumbSizePx).coerceAtLeast(0f)
        val dragOffset = remember { Animatable(0f) }
        val dragFraction = if (maxDragPx > 0) (dragOffset.value / maxDragPx).coerceIn(0f, 1f) else 0f
        val reachedThreshold = dragFraction >= SLIDE_AUTHORIZE_THRESHOLD
        val activeColor = if (reachedThreshold) emeraldColor else amberColor

        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(HanaShapes.Pill)
                .background(c.fill)
                .border(1.dp, if (reachedThreshold) emeraldColor.copy(alpha = 0.6f) else c.edge, HanaShapes.Pill)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .testTag("slide-track"),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 轨道进度高亮底色
            if (dragFraction > 0f && trackWidthPx > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = (dragFraction * 0.88f + 0.12f).coerceIn(0f, 1f))
                        .height(54.dp)
                        .clip(HanaShapes.Pill)
                        .background(activeColor.copy(alpha = if (reachedThreshold) 0.22f else 0.14f)),
                )
            }

            // 背景引导文字
            Text(
                if (reachedThreshold) "松手确认放行 ✓" else "向右滑动确认放行 ❯❯❯",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = activeColor.copy(alpha = if (reachedThreshold) 1f else (1f - dragFraction * 0.7f).coerceIn(0.25f, 1f)),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            // 滑块拇指（带 0.85 阻尼手感）
            Box(
                Modifier
                    .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
                    .padding(4.dp)
                    .size(thumbSize)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(activeColor)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        enabled = !settled,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                val prev = dragOffset.value
                                val dampedDelta = delta * SLIDE_DAMPING_FACTOR
                                val next = (prev + dampedDelta).coerceIn(0f, maxDragPx)
                                val threshold = maxDragPx * SLIDE_AUTHORIZE_THRESHOLD
                                if (prev < threshold && next >= threshold && maxDragPx > 0) {
                                    SafeGateHaptics.triggerGateWarning(context)
                                }
                                dragOffset.snapTo(next)
                            }
                        },
                        onDragStopped = {
                            if (!settled && maxDragPx > 0 && dragOffset.value >= maxDragPx * SLIDE_AUTHORIZE_THRESHOLD) {
                                // 达到 85% 阈值，成功授权
                                settled = true
                                scope.launch {
                                    dragOffset.animateTo(maxDragPx, tween(100))
                                    SafeGateHaptics.triggerSuccessBloom(context)
                                    onAuthorized()
                                }
                            } else {
                                // 未达阈值，弹性回弹并触发回弹触感
                                SafeGateHaptics.triggerElasticSnap(context)
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
                    if (reachedThreshold) "✓" else "➔",
                    style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
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
                    .clickable(enabled = !settled, onClick = onRejected)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
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

@Composable
private fun BillLine(label: String, value: String, valueColor: Color) {
    val c = HanaTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = c.ink3,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(
            value,
            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

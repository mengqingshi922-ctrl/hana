package com.hana.agent.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hana.agent.ui.theme.HanaTheme

fun Modifier.shimmer(): Modifier = composed {
    val colors = HanaTheme.colors
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnim by transition.animateFloat(
        initialValue = -300f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    val base = colors.fill
    val highlight = colors.fill.copy(alpha = 0.35f)
    drawBehind {
        val brush = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(translateAnim, 0f),
            end = Offset(translateAnim + 400f, size.height),
        )
        drawRect(brush)
    }
}

/** 骨架屏卡片占位 */
@Composable
fun ShimmerCard(
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(HanaTheme.colors.surface)
            .shimmer()
    )
}

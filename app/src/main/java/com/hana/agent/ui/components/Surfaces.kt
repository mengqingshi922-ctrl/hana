package com.hana.agent.ui.components

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.hana.agent.ui.theme.MikaColors
import com.hana.agent.ui.theme.MikaShapes
import com.hana.agent.ui.theme.MikaSize
import com.hana.agent.ui.theme.MikaTheme
import com.hana.agent.ui.theme.MikaType

/** 页面底:干净的近白(深色下近黑),不加渐变。 */
@Composable
fun PageBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(MikaTheme.colors.bg), content = content)
}

/** 卡片的底:白底 + 黑 5% 描边 + 极淡阴影;深色下只有描边。 */
fun Modifier.cardSurface(colors: MikaColors, shape: Shape = MikaShapes.Card): Modifier =
    (if (colors.cardShadow.alpha > 0f) {
        shadow(6.dp, shape, clip = false, ambientColor = colors.cardShadow.copy(alpha = 0.45f), spotColor = colors.cardShadow.copy(alpha = 0.16f))
    } else this)
        .background(colors.surface, shape)
        .border(0.5.dp, colors.edge, shape)

/** 悬浮元素的阴影(输入栏、浮在内容上的按钮)。 */
fun Modifier.floatShadow(colors: MikaColors, shape: Shape): Modifier =
    shadow(14.dp, shape, clip = false, ambientColor = colors.floatShadow.copy(alpha = 0.3f), spotColor = colors.floatShadow.copy(alpha = 0.35f))

/** 一张卡片,内容裁在圆角里(行的按压效果不出角)。支持自定义渐变背景与微光边框。 */
@Composable
fun MikaCard(
    modifier: Modifier = Modifier,
    shape: Shape = MikaShapes.Card,
    brush: Brush? = null,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MikaTheme.colors
    val base = if (c.cardShadow.alpha > 0f) {
        Modifier.shadow(6.dp, shape, clip = false, ambientColor = c.cardShadow.copy(alpha = 0.45f), spotColor = c.cardShadow.copy(alpha = 0.16f))
    } else Modifier
    val bgModifier = if (brush != null) base.background(brush, shape) else base.background(c.surface, shape)
    val borderModifier = bgModifier.border(0.5.dp, borderColor ?: c.edge, shape)
    Column(modifier.fillMaxWidth().then(borderModifier).clip(shape), content = content)
}

/**
 * 分组卡片:一组 [ListRow]。下面自带 12dp 间距,和下一组的 [GroupLabel](上 8)合起来是分组之间的 20。
 * 行与行的分隔线由行自己画,从文字处开始。
 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    MikaCard(modifier.padding(bottom = 12.dp), content = content)
}

/** 状态卡:左边圆形图标(就绪态带微呼吸灯),右边标题和说明。用于页面顶上的总状态。 */
@Composable
fun StateCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tone: TileTone = TileTone.Neutral,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MikaTheme.colors
    val (bg, fg) = tone.colors(c)
    val isOk = tone == TileTone.Ok

    val infiniteTransition = rememberInfiniteTransition(label = "stateCardPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 0.12f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    MikaCard(modifier.padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (isOk) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                                alpha = pulseAlpha
                            }
                            .background(c.ok, CircleShape)
                    )
                }
                Box(Modifier.size(34.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(18.dp), tint = fg)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MikaType.BodyStrong, color = c.ink)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
                }
            }
            trailing()
        }
    }
}

// ---------- 悬浮玻璃 ----------

/** 能做背景模糊(安卓 12 起有 RenderEffect);不能时玻璃退回不透明的卡片底。 */
val GlassBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 玻璃后面那层内容的画面:内容用 [glassSource] 画进来,[GlassBar] 取它模糊后垫在自己底下。 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/** 不支持模糊时为 null,[GlassBar] 就用不透明底。 */
@Composable
fun rememberGlassBackdrop(): GlassBackdrop? {
    if (!GlassBlurSupported) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** 把这块内容记成玻璃的背景。玻璃本身不能在这块内容里面。 */
fun Modifier.glassSource(backdrop: GlassBackdrop?): Modifier =
    if (backdrop == null) this
    else onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }

private val GlassBlur = BlurEffect(30f, 30f, TileMode.Clamp)

/**
 * 玻璃输入栏的外壳:半透明底 + 背景模糊 + 悬浮阴影,高至少 52。
 * [backdrop] 为 null(不支持模糊、或后面没有内容)时用不透明底。
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    shape: Shape = MikaShapes.Pill,
    content: @Composable RowScope.() -> Unit,
) {
    val c = MikaTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.floatShadow(c, shape).clip(shape)
            .then(if (backdrop != null) Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier),
    ) {
        if (backdrop != null) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { renderEffect = GlassBlur; clip = true }
                    .drawBehind {
                        val d = backdrop.origin - origin
                        translate(d.x, d.y) { drawLayer(backdrop.layer) }
                    },
            )
        }
        Row(
            Modifier.fillMaxWidth().background(if (backdrop != null) c.glass else c.surface)
                .border(0.5.dp, c.glassEdge, shape)
                .heightIn(min = MikaSize.Composer).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

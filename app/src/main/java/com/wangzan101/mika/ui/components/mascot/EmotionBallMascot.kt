package com.wangzan101.mika.ui.components.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.util.lerp as lerpF
import androidx.compose.ui.unit.Dp
import com.wangzan101.mika.ui.components.GrokBotMood
import com.wangzan101.mika.ui.components.GrokBotShape
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 情绪小球 —— 移植自开源项目 sam70361/aora-bot（Emotion Ball）。
 * 引擎与表情数据个人使用免费、商业需授权；**球形角色视觉形象禁止商用**。
 *
 * 动作分三层：
 *  1. 常驻：呼吸、眨眼、眼环池轮换（逐点弹簧插值）、思考光环、睡眠 zzz；
 *  2. 进场演出：10 套表情带关键帧序列（惊讶弹一下、害羞渐变成粉、唤醒睁眼后自动回待机），
 *     播完按 settle 语义回落或定格；撒花/彩带在进入时触发；
 *  3. 待机小动作：antics 表情每 9~18 秒随机自旋或弹跳一次；切换造型时 96 点轮廓做变形过渡。
 */
@Composable
fun EmotionBallMascot(
    size: Dp,
    emotionId: String,
    modifier: Modifier = Modifier,
    shapeKey: String = "blob",
    onTap: (() -> Unit)? = null,
) {
    // 关键帧序列可以把表情"交给下一个"（例如 唤醒 → 待机放空）
    var autoNext by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(emotionId) { autoNext = null }
    val activeId = autoNext ?: emotionId
    val spec = remember(activeId) { MascotData.emotion(activeId) }

    val targetShape = remember(shapeKey) { MascotData.shapes[shapeKey] ?: MascotData.shapes.getValue("blob") }
    val c = MikaTheme.colors
    val isDark = c.bg.luminance() < 0.42f

    // ---- 造型变形：切形状时把 96 个轮廓点从旧形状插到新形状 ----
    var fromShape by remember { mutableStateOf(targetShape) }
    val shapeMorph = remember { Animatable(1f) }
    LaunchedEffect(shapeKey) {
        if (fromShape !== targetShape) {
            shapeMorph.snapTo(0f)
            shapeMorph.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 90f))
            fromShape = targetShape
        }
    }

    // ---- 眼环池轮换 ----
    var ringIndex by remember(spec.id) { mutableIntStateOf(spec.pool.first()) }
    LaunchedEffect(spec.id) {
        if (spec.pool.size <= 1) return@LaunchedEffect
        while (true) {
            val ms = spec.poolMs
            delay(if (ms != null) Random.nextLong(ms[0].toLong(), ms[1].toLong()) else 3200L)
            ringIndex = spec.pool.filter { it != ringIndex }.random()
        }
    }
    val morph by animateFloatAsState(
        targetValue = ringIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 110f + spec.poolSpeed * 26f),
        label = "mascotMorph",
    )

    val openness by animateFloatAsState(spec.openness, spring(0.72f, 240f), label = "mascotOpen")

    // ---- 呼吸 ----
    val life = rememberInfiniteTransition(label = "mascotLife")
    val t by life.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (spec.zzz) 5200 else 3200, easing = LinearEasing), RepeatMode.Restart),
        label = "mascotT",
    )

    // ---- 眨眼 ----
    var blinking by remember(spec.id) { mutableStateOf(false) }
    LaunchedEffect(spec.id) {
        val bm = spec.blinkMs ?: return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(bm[0].toLong(), bm[1].toLong()))
            blinking = true
            delay(100)
            blinking = false
        }
    }
    val lid by animateFloatAsState(if (blinking) 0.05f else 1f, tween(90), label = "mascotBlink")

    // ---- 进场关键帧演出 ----
    var pose by remember(spec.id) { mutableStateOf<SeqPose?>(null) }
    LaunchedEffect(spec.id) {
        val seq = MascotData.sequences[spec.id]
        if (seq == null) { pose = null; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            pose = seq.sample((now - start).toInt())
            if ((now - start) >= seq.frames.last().at) break
        }
        if (!seq.hold) pose = null
        seq.next?.let { next ->
            delay(160)
            autoNext = next
        }
    }

    // ---- 待机小动作：偶尔自旋或弹一下 ----
    val anticSpin = remember { Animatable(0f) }
    val anticHop = remember { Animatable(0f) }
    LaunchedEffect(spec.id) {
        if (!spec.antics) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(9000L, 18000L))
            if (Random.nextBoolean()) {
                val dir = if (Random.nextBoolean()) 1f else -1f
                anticSpin.animateTo(16f * dir, tween(380))
                anticSpin.animateTo(0f, tween(240))
            } else {
                anticHop.animateTo(-15f, tween(150))
                anticHop.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 320f))
            }
        }
    }

    // ---- 进入时的一次性事件：撒花 / 彩带 ----
    val burst = remember { Animatable(1f) }
    LaunchedEffect(spec.id) {
        if (spec.confetti > 0f || spec.ribbons) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(if (spec.confetti > 0f) 1300 else 900))
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(0.45f, 700f), label = "mascotPress")

    val bodyColor = mascotBodyColor(pose?.color, spec.color, isDark, c.bg)
    val eyeColor = if (bodyColor.luminance() > 0.5f) Color(0xFF17181D) else Color(0xFFF4F6FB)
    val rimColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f)

    val rings = MascotData.rings
    val headCenter = MascotData.HEAD_C

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .then(
                if (onTap != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null, // 自绘图形不要矩形水波纹
                    onClick = onTap,
                ) else Modifier,
            ),
    ) {
        val unit = this.size.minDimension / MascotData.VIEW_SIZE
        val origin = -MascotData.VIEW_MIN * unit
        fun px(x: Float, y: Float) = Offset(origin + x * unit, origin + y * unit)

        // 呼吸 + 表情姿态 + 关键帧 + 待机小动作
        val breathe = 1f + spec.breathe * sin(t * 2f * PI.toFloat())
        val scale = spec.bodyScale * breathe * (pose?.bodyScale ?: 1f)
        val spin = spec.bodyRotate + (pose?.bodyRotate ?: 0f) + anticSpin.value
        val offsetY = spec.bodyY + (pose?.bodyY ?: 0f) + anticHop.value
        fun body(x: Float, y: Float): Offset {
            val dx = (x - headCenter) * scale
            val dy = (y - headCenter) * scale
            val rad = spin * PI.toFloat() / 180f
            return px(headCenter + dx * cos(rad) - dy * sin(rad), headCenter + dx * sin(rad) + dy * cos(rad) + offsetY)
        }

        // 造型变形：两套 96 点轮廓按 shapeMorph 插值
        val mp = shapeMorph.value
        val ringA = fromShape.ring
        val ringB = targetShape.ring
        val faceSx = lerpF(fromShape.sx, targetShape.sx, mp) * lerpF(fromShape.eye, targetShape.eye, mp)
        val faceSy = lerpF(fromShape.sy, targetShape.sy, mp) * lerpF(fromShape.eye, targetShape.eye, mp)
        val faceFx = lerpF(fromShape.fx, targetShape.fx, mp)
        val faceFy = lerpF(fromShape.fy, targetShape.fy, mp)

        if (!isDark) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.Black.copy(alpha = 0.07f), Color.Transparent),
                    center = px(headCenter, headCenter + 18f),
                    radius = MascotData.VIEW_SIZE * 0.46f * unit,
                ),
                radius = MascotData.VIEW_SIZE * 0.46f * unit,
                center = px(headCenter, headCenter + 18f),
            )
        }

        if (spec.orbit) {
            val rc = px(headCenter, headCenter)
            val r = MascotData.VIEW_SIZE * 0.44f * unit
            rotate(t * 360f, rc) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color(0xFF7BD5FF), Color(0xFFB68CFF), Color(0xFFFF8FC7), Color(0xFFFFD36E), Color(0xFF7BD5FF)),
                        center = rc,
                    ),
                    startAngle = 0f, sweepAngle = 300f, useCenter = false,
                    topLeft = Offset(rc.x - r, rc.y - r), size = Size(r * 2, r * 2),
                    style = Stroke(width = 2.2f * unit * 4f, cap = StrokeCap.Round), alpha = 0.85f,
                )
            }
        }

        val bodyPath = Path()
        val bodyLimit = minOf(ringA.size, ringB.size)
        for (i in 0 until bodyLimit step 2) {
            val x = ringA[i] + (ringB[i] - ringA[i]) * mp
            val y = ringA[i + 1] + (ringB[i + 1] - ringA[i + 1]) * mp
            val o = body(x, y)
            if (i == 0) bodyPath.moveTo(o.x, o.y) else bodyPath.lineTo(o.x, o.y)
        }
        bodyPath.close()
        drawPath(
            path = bodyPath,
            brush = Brush.radialGradient(
                listOf(lerp(bodyColor, Color.White, if (isDark) 0.10f else 0.16f), bodyColor),
                center = px(headCenter - 20f, headCenter - 28f),
                radius = MascotData.VIEW_SIZE * 0.62f * unit,
            ),
        )
        drawPath(bodyPath, color = rimColor, style = Stroke(width = 1.1f * unit * 2.2f))

        // ---- 眼睛 ----
        val a = floor(morph).toInt().coerceIn(0, rings.lastIndex)
        val b = (a + 1).coerceAtMost(rings.lastIndex)
        val f = morph - a
        val openBase = openness * lid
        val gazeX = sin(t * 2f * PI.toFloat() * 0.5f) * 1.6f
        val gazeY = sin(t * 2f * PI.toFloat() * 0.37f) * 1.1f

        for (eye in 0..1) {
            val ra = rings[a][eye]
            val rb = rings[b][eye]
            val sz = minOf(ra.size, rb.size)
            val pts = FloatArray(sz)
            for (i in 0 until sz) pts[i] = ra[i] + (rb[i] - ra[i]) * f

            val seqOpen = if (eye == 0) pose?.openL ?: -1f else pose?.openR ?: -1f
            val open = (openBase * (if (seqOpen >= 0f) seqOpen else 1f)).coerceIn(0.02f, 2.4f)
            val eyeSx = (pose?.eyeSx ?: 1f).coerceIn(0.2f, 2.6f)
            val eyeSy = (pose?.eyeSy ?: 1f).coerceIn(0.2f, 2.6f)
            val eyeYOff = pose?.eyeY ?: 0f

            var cx = 0f
            var cy = 0f
            for (i in pts.indices step 2) {
                pts[i] = headCenter + (pts[i] - headCenter) * faceSx + faceFx
                pts[i + 1] = headCenter + (pts[i + 1] - headCenter) * faceSy + faceFy
                cx += pts[i]
                cy += pts[i + 1]
            }
            val n = pts.size / 2
            cx /= n
            cy /= n

            val eyePath = Path()
            for (i in pts.indices step 2) {
                val x = cx + (pts[i] - cx) * eyeSx + gazeX
                val y = cy + (pts[i + 1] - cy) * open * eyeSy + gazeY + eyeYOff
                val o = body(x, y)
                if (i == 0) eyePath.moveTo(o.x, o.y) else eyePath.lineTo(o.x, o.y)
            }
            eyePath.close()
            drawPath(eyePath, color = eyeColor)
        }

        if (spec.zzz) {
            for (k in 0..2) {
                val p = (t + k * 0.33f) % 1f
                val o = px(headCenter + 72f + p * 26f, headCenter - 66f - p * 58f)
                val s = (7f + p * 5f) * unit
                val col = Color(0xFF8A8FA3).copy(alpha = (1f - p) * 0.9f)
                val w = 1.6f * unit * 1.6f
                drawLine(col, Offset(o.x - s * 0.6f, o.y - s * 0.6f), Offset(o.x + s * 0.6f, o.y - s * 0.6f), w, StrokeCap.Round)
                drawLine(col, Offset(o.x + s * 0.6f, o.y - s * 0.6f), Offset(o.x - s * 0.6f, o.y + s * 0.6f), w, StrokeCap.Round)
                drawLine(col, Offset(o.x - s * 0.6f, o.y + s * 0.6f), Offset(o.x + s * 0.6f, o.y + s * 0.6f), w, StrokeCap.Round)
            }
        }

        if (spec.confetti > 0f) {
            val p = burst.value
            if (p < 1f) {
                val rnd = Random(spec.id.hashCode())
                val count = (14 * spec.confetti).roundToInt().coerceAtLeast(6)
                repeat(count) {
                    val ang = rnd.nextFloat() * 2f * PI.toFloat()
                    val dist = (26f + rnd.nextFloat() * 86f) * p
                    val o = px(
                        headCenter + cos(ang) * 18f + cos(ang) * dist,
                        headCenter - 44f + sin(ang) * 10f + sin(ang) * dist + p * p * 46f,
                    )
                    val col = listOf(Color(0xFFFF8FC7), Color(0xFFFFD36E), Color(0xFF7BD5FF), Color(0xFFB68CFF))[it % 4]
                    drawCircle(col.copy(alpha = (1f - p).coerceIn(0f, 1f)), 2.2f * unit * 2.4f, o)
                }
            }
        }

        if (spec.ribbons) {
            val p = burst.value
            if (p < 1f) {
                val rc = px(headCenter, headCenter)
                val r = MascotData.VIEW_SIZE * 0.42f * unit
                rotate(p * 220f - 40f, rc) {
                    drawArc(
                        color = Color(0xFFFFB74D).copy(alpha = (1f - p) * 0.9f),
                        startAngle = 0f, sweepAngle = 150f, useCenter = false,
                        topLeft = Offset(rc.x - r, rc.y - r), size = Size(r * 2, r * 2),
                        style = Stroke(width = 2.4f * unit * 3.4f, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

/** 关键帧插值后的一帧姿态。 */
private class SeqPose(
    val openL: Float, val openR: Float,
    val eyeSx: Float, val eyeSy: Float, val eyeY: Float,
    val bodyY: Float, val bodyScale: Float, val bodyRotate: Float, val color: Color?,
)

private fun MascotData.Sequence.sample(timeMs: Int): SeqPose {
    var lo = frames.first()
    var hi = frames.last()
    for (i in frames.indices) {
        if (frames[i].at <= timeMs) lo = frames[i]
        if (frames[i].at >= timeMs) { hi = frames[i]; break }
    }
    val span = (hi.at - lo.at).coerceAtLeast(1)
    val k = ((timeMs - lo.at).toFloat() / span).coerceIn(0f, 1f)
    return SeqPose(
        openL = mixOpen(lo.openL, hi.openL, k),
        openR = mixOpen(lo.openR, hi.openR, k),
        eyeSx = lo.eyeSx + (hi.eyeSx - lo.eyeSx) * k,
        eyeSy = lo.eyeSy + (hi.eyeSy - lo.eyeSy) * k,
        eyeY = lo.eyeY + (hi.eyeY - lo.eyeY) * k,
        bodyY = lo.bodyY + (hi.bodyY - lo.bodyY) * k,
        bodyScale = lo.bodyScale + (hi.bodyScale - lo.bodyScale) * k,
        bodyRotate = lo.bodyRotate + (hi.bodyRotate - lo.bodyRotate) * k,
        // 两帧都有颜色时按进度混合（害羞是从奶油渐变成粉，不是一步跳过去）
        color = when {
            lo.bodyColor != null && hi.bodyColor != null ->
                lerp(parseHexColor(lo.bodyColor), parseHexColor(hi.bodyColor), k)
            hi.bodyColor != null -> parseHexColor(hi.bodyColor)
            lo.bodyColor != null -> parseHexColor(lo.bodyColor)
            else -> null
        },
    )
}

private fun mixOpen(a: Float, b: Float, k: Float): Float = when {
    a < 0f && b < 0f -> -1f
    a < 0f -> b
    b < 0f -> a
    else -> a + (b - a) * k
}

/** 身体底色:始终用情绪自带的颜色（没有就用奶油色），只在与背景对比不足时才往反方向推一点。 */
private fun mascotBodyColor(seqColor: Color?, hex: String?, isDark: Boolean, bg: Color): Color {
    val base = seqColor ?: hex?.let(::parseHexColor) ?: Color(0xFFF6EFE4)
    val bgLum = bg.luminance()
    val lum = base.luminance()
    return when {
        // 0.2 是"能一眼看出边界"的经验阈值：奶油色对纯白只有 0.10，必须压深才不糊
        abs(lum - bgLum) >= 0.2f -> base
        isDark -> lerp(base, Color.White, 0.28f)
        else -> lerp(base, Color(0xFF6B5F4E), 0.30f)
    }
}

private fun parseHexColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFFF4ECDF))

/** 原来的四种形状映射到开源数据里的身体轮廓。 */
fun shapeKeyFor(shape: GrokBotShape): String = when (shape) {
    GrokBotShape.Circle -> "blob"
    GrokBotShape.Triangle -> "wedge"
    GrokBotShape.Square -> "square"
    GrokBotShape.Diamond -> "gem"
}

/** Mika 的工作状态映射到开源表情库里的 32 个 id 之一。 */
fun mascotEmotionId(mood: GrokBotMood, working: Boolean): String = when {
    working && mood == GrokBotMood.Thinking -> "30" // 思考中：头顶常驻彩带环
    mood == GrokBotMood.Happy -> "33"              // 任务完成：撒花
    mood == GrokBotMood.Wink -> "14"               // 戳一戳：害羞（身体渐变成粉）
    mood == GrokBotMood.Thinking -> "32"           // 忙碌
    mood == GrokBotMood.Curious -> "03"            // 好奇
    mood == GrokBotMood.Shocked -> "13"            // 惊讶（弹一下）
    mood == GrokBotMood.Sleepy -> "00"             // 睡眠：zzz
    else -> "02"                                   // 待机放空
}

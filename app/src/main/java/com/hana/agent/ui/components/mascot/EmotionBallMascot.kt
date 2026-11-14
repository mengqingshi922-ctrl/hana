package com.hana.agent.ui.components.mascot

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpF
import com.hana.agent.ui.components.GrokBotMood
import com.hana.agent.ui.components.GrokBotShape
import com.hana.agent.ui.theme.HanaTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hana 原创吉祥物「星灵花瓣」(Star-Petal Spirit) 动态表情渲染器。
 *
 * 采用自研 48 点眼环 / 96 点星灵花瓣身体轮廓平滑插值引擎，支持全双工语音振幅响应与小窗吸附态：
 *  1. 常驻：呼吸、眨眼、眼环池轮换（逐点弹簧插值）、思考流光轨道、睡眠 zzz；
 *  2. 进场演出：10 套表情带关键帧序列（惊讶弹一下、害羞渐变成粉、唤醒睁眼后自动回待机），
 *     播完按 settle 语义回落或定格；撒花/彩带在进入时触发；
 *  3. 待机小动作与全双工响应：antics 表情每 9~18 秒随机自旋或弹跳一次；
 *     支持 [audioAmplitude] 语音振幅实时微跳动与声波晕环，以及 [pipDocked] 小窗吸附展示状态。
 */
@Composable
fun EmotionBallMascot(
    size: Dp,
    emotionId: String,
    modifier: Modifier = Modifier,
    shapeKey: String = "blob",
    isWorking: Boolean = false,
    audioAmplitude: Float = 0f,
    pipDocked: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    // 订阅跨组件总线：隐私护盾优先级最高，其次是流式内联情绪标签，最后回落参数 emotionId
    val privacyShieldActive by StreamEmotionBus.privacyShieldActive.collectAsState()
    val streamOverrideId by StreamEmotionBus.overrideEmotionId.collectAsState()
    val gazeTarget by MascotGazeBus.target.collectAsState()
    val resolvedEmotionId = resolveEffectiveEmotionId(emotionId, streamOverrideId, privacyShieldActive)

    // 关键帧序列可以把表情"交给下一个"（例如 唤醒 → 待机放空）
    var autoNext by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resolvedEmotionId) { autoNext = null }
    val activeId = if (privacyShieldActive) PRIVACY_SHIELD_EMOTION_ID else (autoNext ?: resolvedEmotionId)
    val spec = remember(activeId) { MascotData.emotion(activeId) }

    val targetShape = remember(shapeKey) { MascotData.resolveShape(shapeKey) }
    val c = HanaTheme.colors
    val isDark = c.bg.luminance() < 0.42f

    // ---- 音频振幅响应式微跳动 ----
    val clampedAmp = audioAmplitude.coerceIn(0f, 1f)
    val smoothAmp by animateFloatAsState(
        targetValue = clampedAmp,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 460f),
        label = "mascotAudioAmp",
    )

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
    val orbitT by life.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2100, easing = LinearEasing), RepeatMode.Restart),
        label = "mascotOrbitT",
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

    // ---- 视线追踪（优先响应 MascotGazeBus，TTL 过期后平滑回落自然待机游移） ----
    var activeGaze by remember { mutableStateOf<GazeTarget?>(null) }
    LaunchedEffect(gazeTarget, privacyShieldActive) {
        val current = gazeTarget
        if (privacyShieldActive || current == null) {
            activeGaze = null
            return@LaunchedEffect
        }
        val remaining = current.expiresAtMs - MascotGazeBus.nowMsProvider()
        if (remaining <= 0L) {
            activeGaze = null
        } else {
            activeGaze = current
            delay(remaining)
            activeGaze = null
        }
    }
    val gazeOffsetTarget = remember(activeGaze) {
        activeGaze?.let {
            ((it.normX - 0.5f) * 2f * MAX_GAZE_SHIFT_X) to ((it.normY - 0.5f) * 2f * MAX_GAZE_SHIFT_Y)
        }
    }
    val trackedGazeX by animateFloatAsState(
        targetValue = gazeOffsetTarget?.first ?: 0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 220f),
        label = "mascotGazeX",
    )
    val trackedGazeY by animateFloatAsState(
        targetValue = gazeOffsetTarget?.second ?: 0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 220f),
        label = "mascotGazeY",
    )
    val idleGazeWeight by animateFloatAsState(
        targetValue = if (gazeOffsetTarget == null) 1f else 0f,
        animationSpec = tween(320),
        label = "mascotIdleGazeWeight",
    )
    val shieldCoverProgress by animateFloatAsState(
        targetValue = if (privacyShieldActive) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 280f),
        label = "mascotPrivacyShield",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(0.45f, 700f), label = "mascotPress")

    val bodyColor = mascotBodyColor(pose?.color, spec.color, isDark, c.bg)
    val eyeColor = if (bodyColor.luminance() > 0.5f) Color(0xFF17181D) else Color(0xFFF4F6FB)
    val rimColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f)

    val rings = MascotData.rings
    val headCenter = MascotData.HEAD_C
    val auroraIntensity = when {
        privacyShieldActive -> 0.85f
        isWorking -> 0.90f
        smoothAmp > 0.03f -> (0.35f + smoothAmp * 0.65f).coerceIn(0f, 1f)
        else -> 0.22f
    }

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .mascotLiquidAuroraEffect(
                intensity = auroraIntensity,
                timeSec = orbitT * 2f * PI.toFloat(),
                isDark = isDark,
            )
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

        // 呼吸 + 表情姿态 + 关键帧 + 待机小动作 + 音频振幅微跳动 + 小窗吸附姿态
        val breatheAmp = if (pipDocked) spec.breathe * 0.65f else spec.breathe
        val breathe = 1f + breatheAmp * sin(t * 2f * PI.toFloat())
        val audioScaleX = 1f + smoothAmp * 0.055f
        val audioScaleY = 1f + smoothAmp * 0.105f
        val scaleBase = spec.bodyScale * breathe * (pose?.bodyScale ?: 1f) * (if (pipDocked) 0.96f else 1f)
        val scaleX = scaleBase * audioScaleX
        val scaleY = scaleBase * audioScaleY
        val dockedTilt = if (pipDocked && !isWorking) -5f else 0f
        val spin = spec.bodyRotate + (pose?.bodyRotate ?: 0f) + anticSpin.value + dockedTilt
        val audioHopY = -smoothAmp * 7.5f
        val offsetY = spec.bodyY + (pose?.bodyY ?: 0f) + anticHop.value + audioHopY
        fun body(x: Float, y: Float): Offset {
            val dx = (x - headCenter) * scaleX
            val dy = (y - headCenter) * scaleY
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

        // 声波振幅光晕（全双工语音播放/交互反馈）
        if (smoothAmp > 0.03f) {
            val voiceCenter = body(headCenter, headCenter)
            val voiceRadius = MascotData.VIEW_SIZE * (0.40f + smoothAmp * 0.09f) * unit
            drawCircle(
                color = c.accent.copy(alpha = (0.14f + smoothAmp * 0.28f).coerceAtMost(0.45f)),
                radius = voiceRadius,
                center = voiceCenter,
                style = Stroke(width = (1.5f + smoothAmp * 2.2f) * unit * 1.5f),
            )
        }

        if (!isDark && !pipDocked) {
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

        // 采集插值形变后身体真实像素轮廓点集
        val bodyLimit = minOf(ringA.size, ringB.size)
        val numPts = bodyLimit / 2
        val bodyPts = ArrayList<Offset>(numPts)
        for (i in 0 until bodyLimit step 2) {
            val x = ringA[i] + (ringB[i] - ringA[i]) * mp
            val y = ringA[i + 1] + (ringB[i + 1] - ringA[i + 1]) * mp
            bodyPts.add(body(x, y))
        }

        // === 几何自适应流光彩圈 (三角机甲/方块/圆球/菱星专属几何流光轨道) ===
        if (spec.orbit || isWorking) {
            // 沿顺时针几何外轮廓法向量外扩，生成与身体等距的几何环绕轨道
            val orbitMargin = (unit * 11.5f).coerceIn(3.dp.toPx(), 7.dp.toPx())
            val orbitPts = ArrayList<Offset>(numPts)
            for (i in 0 until numPts) {
                val prev = bodyPts[(i - 1 + numPts) % numPts]
                val next = bodyPts[(i + 1) % numPts]
                val curr = bodyPts[i]
                val dx = next.x - prev.x
                val dy = next.y - prev.y
                val len = kotlin.math.hypot(dx, dy)
                if (len > 0.0001f) {
                    // 顺时针前进方向 (dx, dy) 的指向外侧单位法向量为 (dy/len, -dx/len)
                    orbitPts.add(Offset(curr.x + (dy / len) * orbitMargin, curr.y + (-dx / len) * orbitMargin))
                } else {
                    orbitPts.add(curr)
                }
            }

            val orbitBasePath = Path().apply {
                for (i in 0 until numPts) {
                    val pt = orbitPts[i]
                    if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                }
                close()
            }

            val pm = androidx.compose.ui.graphics.PathMeasure()
            pm.setPath(orbitBasePath, true)
            val totalLen = pm.length
            if (totalLen > 1f) {
                // 流光彩带占据 78% 的环长，保留 22% 灵动缺口，呈现极速流动的流星霓虹拖尾感
                val sweepRatio = 0.78f
                val segLen = totalLen * sweepRatio
                val start = (orbitT * totalLen) % totalLen
                val stop = start + segLen
                val segmentPath = Path()
                if (stop <= totalLen) {
                    pm.getSegment(start, stop, segmentPath, true)
                } else {
                    pm.getSegment(start, totalLen, segmentPath, true)
                    pm.getSegment(0f, stop - totalLen, segmentPath, false)
                }

                val orbitCenter = body(headCenter, headCenter)
                val orbitColors = listOf(
                    Color(0xFF6366F1), // 极光靛蓝
                    Color(0xFFA855F7), // 赛博亮紫
                    Color(0xFFFF52A8), // 灵动粉红
                    Color(0xFF38BDF8), // 荧光青蓝
                    Color(0xFF34D399), // 翡翠冰绿
                    Color(0xFF6366F1), // 闭合回环
                )
                val strokeWidth = (unit * 8.5f).coerceIn(2.5.dp.toPx(), 4.5.dp.toPx())

                // 1. 底层科技几何轮廓微光暗轨 (指示三角/方块/圆球能量轨道)
                drawPath(
                    path = orbitBasePath,
                    color = if (isDark) Color(0x24FFFFFF) else Color(0x18000000),
                    style = Stroke(width = strokeWidth * 0.42f),
                )

                // 2. 霓虹外发光柔和辉光层 (Glow)
                drawPath(
                    path = segmentPath,
                    brush = Brush.sweepGradient(
                        orbitColors.map { it.copy(alpha = 0.40f) },
                        center = orbitCenter,
                    ),
                    style = Stroke(width = strokeWidth * 2.2f, cap = StrokeCap.Round),
                )

                // 3. 核心高光流光彩带 (贴合三角/方块/圆球轮廓飞驰流转)
                drawPath(
                    path = segmentPath,
                    brush = Brush.sweepGradient(orbitColors, center = orbitCenter),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }

        val bodyPath = Path().apply {
            for (i in 0 until numPts) {
                val pt = bodyPts[i]
                if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
            }
            close()
        }
        drawPath(
            path = bodyPath,
            brush = Brush.radialGradient(
                listOf(lerp(bodyColor, Color.White, if (isDark) 0.10f else 0.16f), bodyColor),
                center = px(headCenter - 20f, headCenter - 28f),
                radius = MascotData.VIEW_SIZE * 0.62f * unit,
            ),
        )
        drawPath(bodyPath, color = if (pipDocked) c.accent.copy(alpha = 0.45f) else rimColor, style = Stroke(width = (if (pipDocked) 1.5f else 1.1f) * unit * 2.2f))

        // ---- 眼睛（支持 MascotGazeBus 实时焦点注视 + 隐私护盾闭目捂眼） ----
        val a = floor(morph).toInt().coerceIn(0, rings.lastIndex)
        val b = (a + 1).coerceAtMost(rings.lastIndex)
        val f = morph - a
        val openBase = openness * lid
        val idleGazeX = sin(t * 2f * PI.toFloat() * 0.5f) * 1.6f
        val idleGazeY = sin(t * 2f * PI.toFloat() * 0.37f) * 1.1f
        val gazeX = trackedGazeX + idleGazeX * idleGazeWeight
        val gazeY = trackedGazeY + idleGazeY * idleGazeWeight

        for (eye in 0..1) {
            val ra = rings[a][eye]
            val rb = rings[b][eye]
            val sz = minOf(ra.size, rb.size)
            val pts = FloatArray(sz)
            for (i in 0 until sz) pts[i] = ra[i] + (rb[i] - ra[i]) * f

            val seqOpen = if (eye == 0) pose?.openL ?: -1f else pose?.openR ?: -1f
            val baseOpen = (openBase * (if (seqOpen >= 0f) seqOpen else 1f)).coerceIn(0.02f, 2.4f)
            // 开启捂眼隐私护盾时，双眼平滑闭合成温和防窥弧线
            val open = lerpF(baseOpen, 0.14f, shieldCoverProgress)
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

        // ---- 接管态双手捂眼隐私护盾演出 ----
        if (shieldCoverProgress > 0.02f) {
            val shieldCenter = body(headCenter, headCenter)
            val shieldRadius = MascotData.VIEW_SIZE * 0.44f * unit
            drawCircle(
                color = Color(0xFF38BDF8).copy(alpha = 0.34f * shieldCoverProgress),
                radius = shieldRadius,
                center = shieldCenter,
                style = Stroke(width = 2.2f * unit * 1.4f),
            )

            val pawColor = lerp(bodyColor, Color.White, if (isDark) 0.20f else 0.28f)
            val pawBorder = if (isDark) Color.White.copy(alpha = 0.35f) else Color(0xFF4A4E5A).copy(alpha = 0.22f)
            val liftY = lerpF(52f, 0f, shieldCoverProgress)
            val pawW = 36f * faceSx * unit * scaleX
            val pawH = 26f * faceSy * unit * scaleY

            for (side in 0..1) {
                val sign = if (side == 0) -1f else 1f
                val eyeAnchorX = headCenter + sign * 28f * faceSx + faceFx
                val eyeAnchorY = headCenter - 2f * faceSy + faceFy + liftY
                val pawCenter = body(eyeAnchorX, eyeAnchorY)
                val pawTilt = sign * (-18f) + spin
                rotate(degrees = pawTilt, pivot = pawCenter) {
                    val topLeft = Offset(pawCenter.x - pawW / 2f, pawCenter.y - pawH / 2f)
                    val corner = CornerRadius(pawW * 0.45f, pawH * 0.45f)
                    drawRoundRect(
                        color = pawColor.copy(alpha = shieldCoverProgress.coerceIn(0f, 1f)),
                        topLeft = topLeft,
                        size = Size(pawW, pawH),
                        cornerRadius = corner,
                    )
                    drawRoundRect(
                        color = pawBorder.copy(alpha = shieldCoverProgress.coerceIn(0f, 1f)),
                        topLeft = topLeft,
                        size = Size(pawW, pawH),
                        cornerRadius = corner,
                        style = Stroke(width = 1.6f * unit),
                    )
                }
            }
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

/** 四种逻辑形态映射到 Hana 星灵花瓣矢量轮廓家族。 */
fun shapeKeyFor(shape: GrokBotShape): String = when (shape) {
    GrokBotShape.Circle -> "blob"
    GrokBotShape.Triangle -> "wedge"
    GrokBotShape.Square -> "square"
    GrokBotShape.Diamond -> "gem"
}

/** 音频振幅微跳动几何参数（供渲染与单元测试校验）。 */
internal fun computeAudioBounceScale(amplitude: Float): Pair<Float, Float> {
    val a = amplitude.coerceIn(0f, 1f)
    return (1f + a * 0.055f) to (1f + a * 0.105f)
}

internal fun computeAudioBounceOffsetY(amplitude: Float): Float = -amplitude.coerceIn(0f, 1f) * 7.5f

/** Hana 的工作状态映射到 32 套情绪状态 id 之一，打通 4 阶段动力学与自愈表情。 */
fun mascotEmotionId(
    mood: GrokBotMood,
    working: Boolean,
    phase: com.hana.agent.capsule.AgentTaskPhase = com.hana.agent.capsule.AgentTaskPhase.Idle,
): String = when {
    working && mood == GrokBotMood.Thinking -> when (phase) {
        com.hana.agent.capsule.AgentTaskPhase.Starting -> "31"    // 阶段1：活力自旋/接收任务
        com.hana.agent.capsule.AgentTaskPhase.Exploring -> "40"   // 阶段2：左右张望寻找控件
        com.hana.agent.capsule.AgentTaskPhase.Operating -> "16"   // 阶段3：专注微表情
        com.hana.agent.capsule.AgentTaskPhase.Approaching -> "19" // 阶段4：期待笑脸/满意
        com.hana.agent.capsule.AgentTaskPhase.Soothing -> "20"    // 阻断自愈：困惑挠头/抱歉合十
        else -> "30"                                             // 思考中：头顶常驻彩带环
    }
    mood == GrokBotMood.Happy -> "33"              // 任务完成：撒花
    mood == GrokBotMood.Wink -> "14"               // 戳一戳：害羞（身体渐变成粉）
    mood == GrokBotMood.Thinking -> "32"           // 忙碌
    mood == GrokBotMood.Curious -> "03"            // 好奇
    mood == GrokBotMood.Shocked -> "13"            // 惊讶（弹一下）
    mood == GrokBotMood.Sleepy -> "00"             // 睡眠：zzz
    else -> "02"                                   // 待机放空
}

internal const val MAX_GAZE_SHIFT_X = 14f
internal const val MAX_GAZE_SHIFT_Y = 10f
internal const val PRIVACY_SHIELD_EMOTION_ID = "14"

/**
 * 决定最终生效的小精灵情绪 ID：
 * 优先级：捂眼隐私护盾 > 流式内联标签覆盖 > 基础工作/心情映射。
 */
internal fun resolveEffectiveEmotionId(
    baseEmotionId: String,
    streamOverrideId: String?,
    privacyShieldActive: Boolean,
): String = when {
    privacyShieldActive -> PRIVACY_SHIELD_EMOTION_ID
    !streamOverrideId.isNullOrBlank() -> streamOverrideId
    else -> baseEmotionId
}

/**
 * 根据当前时间戳与 TTL 计算注视焦点偏移（过期则返回 `null`）。
 */
internal fun computeGazeOffset(
    target: GazeTarget?,
    nowMs: Long,
    maxShiftX: Float = MAX_GAZE_SHIFT_X,
    maxShiftY: Float = MAX_GAZE_SHIFT_Y,
): Pair<Float, Float>? {
    if (target == null || nowMs >= target.expiresAtMs) return null
    val nx = if (target.normX.isFinite()) target.normX.coerceIn(0f, 1f) else 0.5f
    val ny = if (target.normY.isFinite()) target.normY.coerceIn(0f, 1f) else 0.5f
    return ((nx - 0.5f) * 2f * maxShiftX) to ((ny - 0.5f) * 2f * maxShiftY)
}

internal fun isAgslShaderSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU

/**
 * Android 13 (API 33+) 原生 AGSL 液态极光果冻边缘光晕修饰器：
 * 零 SO 库体积开销；低于 API 33 或设备不支持编译时自动降级为原 Canvas 渲染。
 */
internal fun Modifier.mascotLiquidAuroraEffect(
    intensity: Float,
    timeSec: Float,
    isDark: Boolean,
): Modifier = composed {
    if (!isAgslShaderSupported()) return@composed this
    val shader = remember {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                RuntimeShader(MASCOT_AURORA_AGSL)
            } else {
                null
            }
        }.getOrNull()
    } ?: return@composed this

    this.graphicsLayer {
        clip = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val w = size.width.coerceAtLeast(1f)
            val h = size.height.coerceAtLeast(1f)
            shader.setFloatUniform("uResolution", w, h)
            shader.setFloatUniform("uTime", timeSec)
            shader.setFloatUniform("uIntensity", intensity.coerceIn(0f, 1f))
            shader.setFloatUniform("uIsDark", if (isDark) 1f else 0f)
            renderEffect = AndroidRenderEffect
                .createRuntimeShaderEffect(shader, "composable")
                .asComposeRenderEffect()
        }
    }
}

private const val MASCOT_AURORA_AGSL = """
    uniform shader composable;
    uniform float2 uResolution;
    uniform float uTime;
    uniform float uIntensity;
    uniform float uIsDark;

    half4 main(float2 fragCoord) {
        half4 base = composable.eval(fragCoord);
        float minDim = max(min(uResolution.x, uResolution.y), 1.0);
        float stepPx = clamp(minDim * 0.028, 1.5, 5.0);

        half aL = composable.eval(fragCoord + float2(-stepPx, 0.0)).a;
        half aR = composable.eval(fragCoord + float2(stepPx, 0.0)).a;
        half aU = composable.eval(fragCoord + float2(0.0, -stepPx)).a;
        half aD = composable.eval(fragCoord + float2(0.0, stepPx)).a;

        half maxN = max(max(aL, aR), max(aU, aD));
        half minN = min(min(aL, aR), min(aU, aD));

        float outerHalo = clamp(float(maxN - base.a), 0.0, 1.0);
        float innerRim = float(base.a) * clamp(float(1.0 - minN), 0.0, 1.0);

        float2 center = uResolution * 0.5;
        float2 rel = (fragCoord - center) / minDim;
        float wave = 0.5 + 0.5 * sin(atan(rel.y, rel.x) * 3.0 + uTime * 2.0 + length(rel) * 6.0);
        float wave2 = 0.5 + 0.5 * cos(atan(rel.y, rel.x) * 2.0 - uTime * 1.5);

        half3 auroraA = half3(0.22, 0.74, 0.97); // 冰川青
        half3 auroraB = half3(0.66, 0.33, 0.97); // 极光紫
        half3 auroraC = half3(1.00, 0.32, 0.66); // 樱花粉
        half3 auroraCol = mix(mix(auroraA, auroraB, half(wave)), auroraC, half(wave2 * 0.55));

        float haloBoost = (0.18 + 0.36 * uIntensity) * (uIsDark > 0.5 ? 1.0 : 0.65);
        float rimBoost = (0.12 + 0.24 * uIntensity) * (uIsDark > 0.5 ? 1.0 : 0.70);

        half3 outRgb = base.rgb + auroraCol * half(outerHalo * haloBoost + innerRim * rimBoost);
        half outA = clamp(base.a + half(outerHalo * haloBoost * 0.85), half(0.0), half(1.0));
        return half4(outRgb, outA);
    }
"""

package com.hana.agent.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.agent.ui.theme.MikaTheme
import com.hana.agent.ui.theme.MikaType
import com.hana.agent.ui.theme.MikaIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * 灵动小精灵的情绪与表情状态 (基于开源社区 Grok Bot / Bloub 表情系统)
 */
enum class GrokBotMood(val title: String) {
    Idle("待机"),        // 呆萌日常：微幅浮动呼吸，周期性眨眼
    Happy("开心"),       // 开心成就：弯弯笑眼 (^^)，欢快弹跳
    Wink("眨眼"),        // 调皮眨眼：单眼眨动，歪头打趣 (^_-)
    Thinking("思考"),    // 沉思推演：眼神专注游移，思考探索
    Curious("好奇"),     // 好奇张望：眼神偏侧，歪头打量
    Shocked("惊讶"),     // 惊喜目瞪：双眼圆润放大 (O.O)
    Sleepy("休眠"),      // 打盹休眠：双眼微垂为细线 (- -)
}

/**
 * Grok Bot 小精灵的几何形态
 */
enum class GrokBotShape(val title: String, val iconText: String) {
    Circle("圆球", "⚪"),
    Triangle("三角", "🔺"),
    Square("方块", "⬛"),
    Diamond("菱星", "🔷")
}

/**
 * 全局小精灵形态管理单例（自动持久化到 SharedPreferences）
 */
object GrokBotTheme {
    private const val PREFS_NAME = "grok_bot_theme_prefs"
    private const val KEY_SHAPE = "active_bot_shape"

    private val _currentShape = MutableStateFlow(GrokBotShape.Circle)
    val currentShape = _currentShape.asStateFlow()

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedName = sp.getString(KEY_SHAPE, GrokBotShape.Circle.name)
        _currentShape.value = try {
            GrokBotShape.valueOf(savedName ?: GrokBotShape.Circle.name)
        } catch (_: Exception) {
            GrokBotShape.Circle
        }
    }

    fun setShape(context: Context, shape: GrokBotShape) {
        _currentShape.value = shape
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_SHAPE, shape.name).apply()
    }

    fun nextShape(context: Context) {
        val entries = GrokBotShape.values()
        val nextIndex = (_currentShape.value.ordinal + 1) % entries.size
        setShape(context, entries[nextIndex])
    }
}

/**
 * 动态 Grok Bot 小精灵组件 (xAI Bot Avatar 仿真与多几何形态)
 * - 支持圆球、三角、方块、菱星等多种 Grok 系列可爱几何体
 * - 待机状态: 身体微幅呼吸悬浮，随机眨眼，呆萌灵动
 * - 工作状态 (isWorking=true): 眼神好奇专注，身体周围环绕 60fps 旋转霓虹光带 (Orbit)
 * - 可点击交互: 点击可快速循环切换下一款小精灵形态
 */

/**
 * 小精灵组件入口：改用开源 Emotion Ball 的表情引擎（32 套表情 / 25 组轮廓眼环 / 3 种身体）。
 * 旧的手绘实现在同文件里保留为 [LegacyGrokBotAvatar]，不再调用。
 */
@Composable
fun GrokBotAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: GrokBotShape? = null,
    mood: GrokBotMood? = null,
    isWorking: Boolean = false,
    phase: com.hana.agent.capsule.AgentTaskPhase = com.hana.agent.capsule.AgentTaskPhase.Idle,
    bodyColor: Color? = null,
    enableClickToggle: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val globalShape by GrokBotTheme.currentShape.collectAsState()
    val activeShape = shape ?: globalShape
    val activeMood = mood ?: if (isWorking) GrokBotMood.Thinking else GrokBotMood.Idle
    val tap: (() -> Unit)? = when {
        onClick != null -> onClick
        enableClickToggle -> ({ GrokBotTheme.nextShape(context) })
        else -> null
    }
    com.hana.agent.ui.components.mascot.EmotionBallMascot(
        size = size,
        emotionId = com.hana.agent.ui.components.mascot.mascotEmotionId(activeMood, isWorking, phase),
        modifier = modifier,
        shapeKey = com.hana.agent.ui.components.mascot.shapeKeyFor(activeShape),
        onTap = tap,
    )
}

@Composable
private fun LegacyGrokBotAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: GrokBotShape? = null,
    mood: GrokBotMood? = null,
    isWorking: Boolean = false,
    bodyColor: Color? = null,
    enableClickToggle: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val globalShape by GrokBotTheme.currentShape.collectAsState()
    val activeShape = shape ?: globalShape
    val isDark = MikaTheme.colors.isDark
    val activeMood = mood ?: if (isWorking) GrokBotMood.Thinking else GrokBotMood.Idle

    val coroutineScope = rememberCoroutineScope()
    val bounceScaleX = remember { Animatable(1f) }
    val bounceScaleY = remember { Animatable(1f) }

    fun triggerSquish() {
        coroutineScope.launch {
            bounceScaleX.animateTo(1.18f, tween(70))
            bounceScaleY.animateTo(0.82f, tween(70))
            bounceScaleX.animateTo(0.92f, spring(dampingRatio = 0.4f, stiffness = 450f))
            bounceScaleY.animateTo(1.08f, spring(dampingRatio = 0.4f, stiffness = 450f))
            bounceScaleX.animateTo(1f, spring(dampingRatio = 0.6f))
            bounceScaleY.animateTo(1f, spring(dampingRatio = 0.6f))
        }
    }

    val transition = rememberInfiniteTransition(label = "GrokBotTransition")

    // 1. 身体微幅上下呼吸浮动
    val floatOffset by transition.animateFloat(
        initialValue = -1.8f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isWorking) 700 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatAnim"
    )

    // 2. 周期性自然眨眼
    val blinkProgress by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "blinkAnim"
    )
    val eyeOpenScale = when {
        activeMood == GrokBotMood.Sleepy -> 0.15f
        blinkProgress < 0.08f -> (blinkProgress / 0.04f).coerceIn(0.06f, 1f)
        blinkProgress < 0.16f -> (1f - (blinkProgress - 0.08f) / 0.08f).coerceIn(0.06f, 1f)
        else -> 1f
    }

    // 3. 工作状态下的环绕光带旋转角度
    val orbitAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitAnim"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val clickableModifier = when {
        onClick != null -> modifier.clickable(interactionSource = interactionSource, indication = null) {
            triggerSquish()
            onClick()
        }
        enableClickToggle -> modifier.clickable(interactionSource = interactionSource, indication = null) {
            triggerSquish()
            GrokBotTheme.nextShape(context)
        }
        else -> modifier
    }

    Box(
        modifier = clickableModifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val center = Offset(w / 2f, h / 2f + floatOffset * (w / 48f))
            val radius = w * 0.40f

            scale(scaleX = bounceScaleX.value, scaleY = bounceScaleY.value, pivot = center) {
                // === A. 工作模式: 环绕旋转彩色光圈 (Orbit 彩带) ===
                if (isWorking || activeMood == GrokBotMood.Thinking) {
                    rotate(degrees = orbitAngle, pivot = center) {
                        val ringColors = listOf(
                            Color(0xFF6366F1), // 极光靛蓝
                            Color(0xFFA855F7), // 赛博亮紫
                            Color(0xFFEC4899), // 灵动粉红
                            Color(0xFF06B6D4)  // 荧光青蓝
                        )
                        drawCircle(
                            brush = Brush.sweepGradient(ringColors, center),
                            radius = radius * 1.25f,
                            center = center,
                            style = Stroke(width = (w * 0.05f).coerceAtLeast(2f), cap = StrokeCap.Round)
                        )
                    }
                }

                // === B. 核心身体材质与轮廓光绘制 (自适应黑色/白色背景) ===
                val bodyBrush = if (bodyColor != null) {
                    SolidColor(bodyColor)
                } else if (isDark) {
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF424654),
                            Color(0xFF262832),
                            Color(0xFF17181E)
                        ),
                        center = center - Offset(radius * 0.28f, radius * 0.38f),
                        radius = radius * 1.4f
                    )
                } else {
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF323440),
                            Color(0xFF1E1F26),
                            Color(0xFF111216)
                        ),
                        center = center - Offset(radius * 0.25f, radius * 0.35f),
                        radius = radius * 1.35f
                    )
                }

                val rimBrush = if (isDark) {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xF2FFFFFF),
                            Color(0x80FFFFFF),
                            Color(0x28FFFFFF),
                            Color(0x0AFFFFFF)
                        ),
                        start = Offset(center.x - radius, center.y - radius),
                        end = Offset(center.x + radius, center.y + radius)
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0x38000000),
                            Color(0x1C000000),
                            Color(0x0A000000)
                        ),
                        start = Offset(center.x - radius, center.y - radius),
                        end = Offset(center.x + radius, center.y + radius)
                    )
                }
                val rimWidth = (w * 0.038f).coerceIn(1.5f, 3.5f)

                when (activeShape) {
                    GrokBotShape.Circle -> {
                        if (isDark) {
                            drawCircle(
                                color = Color(0x2EFFFFFF),
                                radius = radius + rimWidth * 0.8f,
                                center = center
                            )
                        } else {
                            drawCircle(
                                color = Color(0x14000000),
                                radius = radius * 1.05f,
                                center = center + Offset(0f, radius * 0.08f)
                            )
                        }
                        drawCircle(brush = bodyBrush, radius = radius, center = center)
                        drawCircle(brush = rimBrush, radius = radius, center = center, style = Stroke(width = rimWidth))
                    }

                    GrokBotShape.Triangle -> {
                        val r = radius * 1.08f
                        val path = Path()
                        val pTop = Offset(center.x, center.y - r * 0.95f)
                        val pRight = Offset(center.x + r * cos(Math.toRadians(30.0)).toFloat(), center.y + r * sin(Math.toRadians(30.0)).toFloat() * 0.9f)
                        val pLeft = Offset(center.x - r * cos(Math.toRadians(30.0)).toFloat(), center.y + r * sin(Math.toRadians(30.0)).toFloat() * 0.9f)

                        val corner = r * 0.28f
                        path.moveTo(pTop.x, pTop.y + corner)
                        path.lineTo(pRight.x - corner * 0.866f, pRight.y - corner * 0.5f)
                        path.quadraticTo(pRight.x, pRight.y, pRight.x - corner, pRight.y)
                        path.lineTo(pLeft.x + corner, pLeft.y)
                        path.quadraticTo(pLeft.x, pLeft.y, pLeft.x + corner * 0.866f, pLeft.y - corner * 0.5f)
                        path.lineTo(pTop.x - corner * 0.5f, pTop.y + corner * 0.866f)
                        path.quadraticTo(pTop.x, pTop.y, pTop.x, pTop.y + corner)
                        path.close()

                        if (isDark) {
                            drawPath(path = path, color = Color(0x28FFFFFF), style = Stroke(width = rimWidth * 2.2f))
                        } else {
                            drawPath(path = path, color = Color(0x12000000))
                        }
                        drawPath(path = path, brush = bodyBrush)
                        drawPath(path = path, brush = rimBrush, style = Stroke(width = rimWidth))
                    }

                    GrokBotShape.Square -> {
                        val side = radius * 1.85f
                        val topLeft = Offset(center.x - side / 2f, center.y - side / 2f)
                        val cornerRadius = CornerRadius(side * 0.32f, side * 0.32f)

                        if (isDark) {
                            drawRoundRect(
                                color = Color(0x28FFFFFF),
                                topLeft = topLeft,
                                size = Size(side, side),
                                cornerRadius = cornerRadius,
                                style = Stroke(width = rimWidth * 2.2f)
                            )
                        } else {
                            drawRoundRect(
                                color = Color(0x14000000),
                                topLeft = topLeft + Offset(0f, radius * 0.08f),
                                size = Size(side, side),
                                cornerRadius = cornerRadius
                            )
                        }
                        drawRoundRect(brush = bodyBrush, topLeft = topLeft, size = Size(side, side), cornerRadius = cornerRadius)
                        drawRoundRect(brush = rimBrush, topLeft = topLeft, size = Size(side, side), cornerRadius = cornerRadius, style = Stroke(width = rimWidth))
                    }

                    GrokBotShape.Diamond -> {
                        rotate(degrees = 45f, pivot = center) {
                            val side = radius * 1.6f
                            val topLeft = Offset(center.x - side / 2f, center.y - side / 2f)
                            val cornerRadius = CornerRadius(side * 0.28f, side * 0.28f)

                            if (isDark) {
                                drawRoundRect(
                                    color = Color(0x28FFFFFF),
                                    topLeft = topLeft,
                                    size = Size(side, side),
                                    cornerRadius = cornerRadius,
                                    style = Stroke(width = rimWidth * 2.2f)
                                )
                            } else {
                                drawRoundRect(
                                    color = Color(0x14000000),
                                    topLeft = topLeft + Offset(0f, radius * 0.08f),
                                    size = Size(side, side),
                                    cornerRadius = cornerRadius
                                )
                            }
                            drawRoundRect(brush = bodyBrush, topLeft = topLeft, size = Size(side, side), cornerRadius = cornerRadius)
                            drawRoundRect(brush = rimBrush, topLeft = topLeft, size = Size(side, side), cornerRadius = cornerRadius, style = Stroke(width = rimWidth))
                        }
                    }
                }

                // === C. 眼睛表情系统: 支持笑眼、眨眼、休眠、惊讶、好奇等 ===
                val eyeW = radius * 0.22f
                val eyeH = radius * 0.46f * eyeOpenScale
                val eyeSpacing = radius * 0.30f
                val eyeCorner = CornerRadius(eyeW / 2f, eyeW / 2f)

                val eyesBaseCenter = when (activeShape) {
                    GrokBotShape.Circle -> center + Offset(radius * 0.32f, -radius * 0.30f)
                    GrokBotShape.Triangle -> center + Offset(0f, -radius * 0.08f)
                    GrokBotShape.Square -> center + Offset(radius * 0.25f, -radius * 0.22f)
                    GrokBotShape.Diamond -> center + Offset(0f, -radius * 0.12f)
                }

                val baseAngle = when (activeShape) {
                    GrokBotShape.Circle -> 28f
                    GrokBotShape.Triangle -> 0f
                    GrokBotShape.Square -> 22f
                    GrokBotShape.Diamond -> 0f
                }
                val moodTilt = when (activeMood) {
                    GrokBotMood.Curious -> 7f
                    GrokBotMood.Wink -> -5f
                    else -> 0f
                }

                rotate(degrees = baseAngle + moodTilt, pivot = eyesBaseCenter) {
                    when (activeMood) {
                        GrokBotMood.Happy -> {
                            // 开心笑眼 (^^)
                            val arcW = eyeW * 1.3f
                            val arcH = eyeW * 0.8f
                            val strokeW = (eyeW * 0.7f).coerceAtLeast(2.2f)

                            val leftArc = Path().apply {
                                val lx = eyesBaseCenter.x - eyeSpacing / 2f
                                val ly = eyesBaseCenter.y
                                moveTo(lx - arcW / 2f, ly + arcH * 0.3f)
                                quadraticTo(lx, ly - arcH * 0.6f, lx + arcW / 2f, ly + arcH * 0.3f)
                            }
                            val rightArc = Path().apply {
                                val rx = eyesBaseCenter.x + eyeSpacing / 2f
                                val ry = eyesBaseCenter.y
                                moveTo(rx - arcW / 2f, ry + arcH * 0.3f)
                                quadraticTo(rx, ry - arcH * 0.6f, rx + arcW / 2f, ry + arcH * 0.3f)
                            }
                            drawPath(leftArc, color = Color.White, style = Stroke(width = strokeW, cap = StrokeCap.Round))
                            drawPath(rightArc, color = Color.White, style = Stroke(width = strokeW, cap = StrokeCap.Round))
                        }

                        GrokBotMood.Wink -> {
                            // 调皮单眨眼 (^_-)
                            val leftEyePos = Offset(eyesBaseCenter.x - eyeSpacing / 2f - eyeW / 2f, eyesBaseCenter.y - eyeH / 2f)
                            drawRoundRect(color = Color.White, topLeft = leftEyePos, size = Size(eyeW, eyeH), cornerRadius = eyeCorner)

                            // 右眼眨成横弧
                            val winkPath = Path().apply {
                                val rx = eyesBaseCenter.x + eyeSpacing / 2f
                                val ry = eyesBaseCenter.y
                                moveTo(rx - eyeW * 0.6f, ry + eyeH * 0.1f)
                                quadraticTo(rx, ry - eyeH * 0.3f, rx + eyeW * 0.6f, ry + eyeH * 0.1f)
                            }
                            drawPath(winkPath, color = Color.White, style = Stroke(width = (eyeW * 0.7f).coerceAtLeast(2.2f), cap = StrokeCap.Round))
                        }

                        GrokBotMood.Sleepy -> {
                            // 打盹休眠 (- -)
                            val sleepW = eyeW * 1.15f
                            val strokeW = (eyeW * 0.65f).coerceAtLeast(2.2f)
                            drawLine(
                                color = Color.White,
                                start = Offset(eyesBaseCenter.x - eyeSpacing / 2f - sleepW / 2f, eyesBaseCenter.y),
                                end = Offset(eyesBaseCenter.x - eyeSpacing / 2f + sleepW / 2f, eyesBaseCenter.y),
                                strokeWidth = strokeW,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = Color.White,
                                start = Offset(eyesBaseCenter.x + eyeSpacing / 2f - sleepW / 2f, eyesBaseCenter.y),
                                end = Offset(eyesBaseCenter.x + eyeSpacing / 2f + sleepW / 2f, eyesBaseCenter.y),
                                strokeWidth = strokeW,
                                cap = StrokeCap.Round
                            )
                        }

                        GrokBotMood.Shocked -> {
                            // 惊喜目瞪 (O.O)
                            val shockR = eyeW * 0.8f
                            drawCircle(color = Color.White, radius = shockR, center = Offset(eyesBaseCenter.x - eyeSpacing / 2f, eyesBaseCenter.y))
                            drawCircle(color = Color.White, radius = shockR, center = Offset(eyesBaseCenter.x + eyeSpacing / 2f, eyesBaseCenter.y))
                        }

                        else -> {
                            // Idle, Thinking, Curious: 经典圆润发光双眼
                            val gazeOffset = if (activeMood == GrokBotMood.Curious) Offset(radius * 0.12f, 0f) else Offset.Zero
                            val leftEyePos = Offset(eyesBaseCenter.x - eyeSpacing / 2f - eyeW / 2f + gazeOffset.x, eyesBaseCenter.y - eyeH / 2f + gazeOffset.y)
                            val rightEyePos = Offset(eyesBaseCenter.x + eyeSpacing / 2f - eyeW / 2f + gazeOffset.x, eyesBaseCenter.y - eyeH / 2f + gazeOffset.y)

                            if (isDark) {
                                drawRoundRect(
                                    color = Color(0x40FFFFFF),
                                    topLeft = leftEyePos - Offset(eyeW * 0.15f, eyeH * 0.15f),
                                    size = Size(eyeW * 1.3f, eyeH * 1.3f),
                                    cornerRadius = CornerRadius((eyeW * 1.3f) / 2f, (eyeW * 1.3f) / 2f)
                                )
                                drawRoundRect(
                                    color = Color(0x40FFFFFF),
                                    topLeft = rightEyePos - Offset(eyeW * 0.15f, eyeH * 0.15f),
                                    size = Size(eyeW * 1.3f, eyeH * 1.3f),
                                    cornerRadius = CornerRadius((eyeW * 1.3f) / 2f, (eyeW * 1.3f) / 2f)
                                )
                            }

                            drawRoundRect(color = Color.White, topLeft = leftEyePos, size = Size(eyeW, eyeH), cornerRadius = eyeCorner)
                            drawRoundRect(color = Color.White, topLeft = rightEyePos, size = Size(eyeW, eyeH), cornerRadius = eyeCorner)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 屏幕顶端常驻固定灵动小精灵头部导航栏 (Pixel-perfect matching Hana/Grok Mascot Header)
 * - 永久固定置顶于屏幕正上方，即使聊天记录与任务清单上下滑动也绝不消失
 * - 正中高光灵动小精灵 (54dp) + 紧凑状态药丸胶囊 (复刻截图中深色圆角药丸)
 * - 根据不同工作状态自适应展现对应表情与动作：
 *   - 手机操作中: 专注眼神 + 60fps 彩色 Orbit 霓虹旋转彩带 + "📱 手机操作中"
 *   - 推演思考中: 灵动思考眼神 + 呼吸浮动 + "💭 推演思考中"
 *   - 倾听语音中: 好奇歪头 + 关切注视 + "🎙️ 正在倾听..."
 *   - 任务阻塞接管: 惊讶圆眼 (O.O) + 警觉微动 + "🙋 等待接管"
 *   - 任务刚搞定: 开心笑眼 (^^) + 欢快弹跳 + "✨ 搞定啦！"
 *   - 默认待命: 自然呼吸眨眼 + 经典胶囊 "Hana"
 * - 情绪交互:
 *   - 点击小精灵: Q 弹果冻挤压回弹 (Squash & Stretch) + 调皮眨眼 + 药丸浮现暖心互动寄语
 *   - 点击药丸: 呼出造型切换弹窗 (圆球、三角、方块、菱星)
 */
@Composable
fun GrokBotFixedHeader(
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit,
    hasActiveTasksElsewhere: Boolean = false,
    onNewConversation: () -> Unit,
    canSwitchConversation: Boolean = true,
    isOperatingPhone: Boolean = false,
    isBusy: Boolean = false,
    isListening: Boolean = false,
    isBlocked: Boolean = false,
    isRecentlyDone: Boolean = false,
    customSubtitle: String? = null,
    onOpenShapeDialog: () -> Unit = {},
) {
    val c = MikaTheme.colors
    val coroutineScope = rememberCoroutineScope()
    var interactiveMood by remember { mutableStateOf<GrokBotMood?>(null) }
    var interactiveQuote by remember { mutableStateOf<String?>(null) }

    val cuteQuotes = remember {
        listOf(
            "摸摸头，在呢~ 💖",
            "今天也是元气满满！☀️",
            "交给我跑，放一百个心 🚀",
            "戳我一下，心情会变好吗 (◕‿◕)",
            "认真守护中... ✨",
            "你专注生活，琐事交给我 🌟",
            "随时听候差遣~ 🌸"
        )
    }

    // 确定当前的表情状态 (不同工作状态展现不同表情)
    val activeMood = interactiveMood ?: when {
        isOperatingPhone -> GrokBotMood.Thinking
        isBusy -> GrokBotMood.Thinking
        isListening -> GrokBotMood.Curious
        isBlocked -> GrokBotMood.Shocked
        isRecentlyDone -> GrokBotMood.Happy
        else -> GrokBotMood.Idle
    }

    // 药丸文案动态匹配工作状态 (与截图中的 Hana 药丸保持一致视觉质感，支持实时思考流投射)
    val pillText = interactiveQuote ?: when {
        isRecentlyDone -> "✨ 搞定啦！"
        isBlocked -> "🙋 等待接管"
        isListening -> "🎙️ 正在倾听..."
        !customSubtitle.isNullOrBlank() -> customSubtitle
        isOperatingPhone -> "📱 手机操作中"
        isBusy -> "💭 推演思考中"
        else -> "Hana"
    }

    val isWorking = isOperatingPhone || isBusy
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    androidx.compose.runtime.LaunchedEffect(isRecentlyDone) {
        if (isRecentlyDone) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
    }

    androidx.compose.runtime.LaunchedEffect(isBlocked) {
        if (isBlocked) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
    }

    fun triggerMascotTap() {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        coroutineScope.launch {
            val playfulMoods = listOf(GrokBotMood.Happy, GrokBotMood.Wink, GrokBotMood.Curious)
            interactiveMood = playfulMoods.random()
            interactiveQuote = cuteQuotes.random()
            kotlinx.coroutines.delay(3500)
            interactiveMood = null
            interactiveQuote = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bg)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左侧汉堡菜单按钮 (圆钮)
            BarIconButton(
                icon = MikaIcons.Menu,
                label = "菜单",
                onClick = onOpenDrawer,
                badge = hasActiveTasksElsewhere
            )

            // 正中居中大精灵与胶囊药丸 (永久固定置顶)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GrokBotAvatar(
                    size = 54.dp,
                    mood = activeMood,
                    isWorking = isWorking,
                    enableClickToggle = false,
                    onClick = { triggerMascotTap() }
                )

                // 悬挂在小精灵正下方的圆角药丸胶囊 (复刻截图像素细节)
                val pillBg = if (c.isDark) Color(0x4D2A2B36) else Color(0x0F000000)
                val pillBorder = if (isWorking) {
                    c.accent.copy(alpha = 0.5f)
                } else if (c.isDark) {
                    Color(0x33FFFFFF)
                } else {
                    Color(0x18000000)
                }

                Row(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(pillBg)
                        .border(1.dp, pillBorder, RoundedCornerShape(12.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                            onOpenShapeDialog()
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (!isWorking && interactiveQuote == null && customSubtitle == null) {
                        Box(Modifier.size(5.5.dp).background(c.ok, CircleShape))
                    }
                    Text(
                        text = pillText,
                        style = MikaType.Hint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (c.isDark) Color(0xFFF2F2F7) else Color(0xFF1E1F26),
                        maxLines = 1
                    )
                }
            }

            // 右侧新建对话按钮 (圆钮)
            BarIconButton(
                icon = MikaIcons.New,
                label = "新建对话",
                onClick = onNewConversation,
                enabled = canSwitchConversation
            )
        }
    }
}

/**
 * 灵动小精灵造型切换弹窗 (在左侧抽屉菜单点击小精灵头像或标题即可唤起)
 */
@Composable
fun GrokBotShapeDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentShape by GrokBotTheme.currentShape.collectAsState()
    val c = MikaTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GrokBotAvatar(size = 26.dp, enableClickToggle = false)
                Text(
                    "灵动小精灵造型",
                    style = MikaType.Title,
                    color = c.ink
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "选择你喜爱的小精灵造型，主界面与顶栏将实时同步：",
                    style = MikaType.Hint,
                    color = c.ink2
                )
                GrokBotShape.entries.forEach { shape ->
                    val isSelected = shape == currentShape
                    val cardBg = if (isSelected) c.accentSoft else (if (c.isDark) Color(0x14FFFFFF) else Color(0x0A000000))
                    val borderColor = if (isSelected) c.accent else Color.Transparent

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(cardBg)
                            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
                            .clickable {
                                GrokBotTheme.setShape(context, shape)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GrokBotAvatar(
                            size = 36.dp,
                            shape = shape,
                            isWorking = isSelected,
                            enableClickToggle = false
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                shape.title,
                                style = MikaType.Body,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = c.ink
                            )
                            Text(
                                when (shape) {
                                    GrokBotShape.Triangle -> "圆润三角机甲 · 专注灵动"
                                    GrokBotShape.Circle -> "经典球体 · 温润悬浮"
                                    GrokBotShape.Square -> "超椭圆方块 · 极客现代"
                                    GrokBotShape.Diamond -> "璀璨菱星 · 棱角分明"
                                },
                                style = MikaType.Hint,
                                fontSize = 12.sp,
                                color = c.ink3
                            )
                        }
                        if (isSelected) {
                            Icon(
                                MikaIcons.Check,
                                contentDescription = "已选",
                                tint = c.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = c.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

/**
 * 独立的小精灵形态切换控制胶囊条 (供需要行内切换的地方使用)
 */
@Composable
fun GrokBotShapeSelector(
    modifier: Modifier = Modifier,
    activeShape: GrokBotShape = GrokBotTheme.currentShape.collectAsState().value,
    onSelect: (GrokBotShape) -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1E1E24))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GrokBotShape.entries.forEach { shape ->
            val isSelected = shape == activeShape
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) Color(0xFF33333F) else Color.Transparent)
                    .clickable { onSelect(shape) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = shape.iconText, fontSize = 14.sp)
                    Text(
                        text = shape.title,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color.White else Color(0xFF9E9EA8)
                    )
                }
            }
        }
    }
}


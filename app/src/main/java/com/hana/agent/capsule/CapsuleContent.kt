package com.hana.agent.capsule

import com.hana.agent.i18n.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.hana.agent.ui.components.GrokBotShape
import com.hana.agent.ui.components.GrokBotTheme
import com.hana.agent.ui.components.mascot.shapeKeyFor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.agent.R
import com.hana.agent.ui.ScreenCopy
import com.hana.agent.ui.components.ButtonSize
import com.hana.agent.ui.components.HanaButton
import com.hana.agent.ui.components.HanaLogo
import com.hana.agent.ui.theme.HanaColors
import com.hana.agent.ui.theme.HanaIcons
import com.hana.agent.ui.theme.HanaShapes
import com.hana.agent.ui.theme.HanaTheme
import kotlin.math.ceil

/** 胶囊外面留给阴影的边:浮层窗口按内容大小,阴影画在窗口里面;留得越少,窗口挡住下面 App 的透明边越窄。 */
internal val CapsuleShadowRoom = 8.dp
private val CapsuleHeight = 40.dp
private val BarHeight = 48.dp
private val LogoSize = 30.dp
private const val FADE_MS = 400
/** 截到屏幕时一颗上那一闪:多亮、多久退掉。 */
private const val FLASH_ALPHA = 0.8f
private const val FLASH_MS = 320
/** 一颗展开成胶囊、缩回一颗的时长。 */
private const val MORPH_MS = 260
/** 长按说话时放大多少、外圈多粗、变过去多久。 */
private const val HOLD_SCALE = 0.06f
private val HoldRing = 3.dp
private const val HOLD_MS = 180
/** 干活中的圈转一周的时长。 */
private const val SPIN_MS = 1_200
private val CapsuleText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val BarDetail = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val CountText = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)

/** 胶囊上的固定说法。 */
object CapsuleCopy {
    val STOP: String get() = tr(R.string.capsule_stop)
    val EXPAND: String get() = tr(R.string.capsule_expand)
    val TALK: String get() = tr(R.string.capsule_talk)
    val SWAP_SIDE: String get() = tr(R.string.capsule_swap_side)
    const val IDLE = "Hana"

    /** 读屏时一颗图标的说法:在做什么、要不要你处理、结果;好几件时的件数已在 [CapsuleView.Shown.text] 里。 */
    fun describe(view: CapsuleView.Shown): String = when {
        view.kind == CapsuleKind.Idle -> IDLE
        view.kind.needsYou -> tr(R.string.capsule_desc_needs, view.text)
        view.kind == CapsuleKind.Running -> tr(R.string.capsule_desc_running, view.text)
        view.kind == CapsuleKind.Held || view.kind == CapsuleKind.Cancelled -> tr(R.string.capsule_desc_other, view.text)
        else -> "Hana ${view.text}"
    }
}

/**
 * 悬浮胶囊,不带状态:平时是一颗 Hana 图标,状态画在外圈和角标上(干活中蓝弧绕着转、等条件灰圈、要你处理整颗变橙、
 * 外圈一呼一吸;好几件时角上写件数;办好了绿勾,没办成、已停止灰色角标;空闲时什么都没有);
 * [CapsuleView.Shown.pill] 时展开成一行字的胶囊(要你处理、刚办完),宽度慢慢变,图标贴着停靠的那一边([side])不动;
 * App 交到你手上时是顶部那一条「你来处理 · 不做了 · 处理好了」。
 * 胶囊只一行字,最宽到屏幕的 60%,超出截断;同一个 [CapsuleView.Shown.widthEpoch] 里只变宽不变窄。
 * 点一颗或胶囊交给 [onTap](展开卡片),读屏时这一下的说法是 [tapLabel];那一条上的两个按钮交给 [onStop]、[onHandBack]。
 * [onSwapSide] 给了时读屏软件多一个「移到屏幕另一边」,代替拖动。[alpha] 只淡底和图标,外圈和角标保持实色。
 * 双击由调用方从两次 [onTap] 认出来;读屏软件的双击就是点按,所以 [onSendScreen] 给了时另有一个「把此刻的屏幕发给 Hana」。
 * [flash] 每加一,一颗上闪一下(截到了屏幕)。
 * [holding] 时是长按说话中:胶囊稍微放大、外面一圈蓝边。[onTalk] 给了时读屏软件多一个说话的操作([talkLabel]),代替长按:
 * 点一下开始、再点一下发出。
 */
/** 状态映射到吉祥物情绪表情编号，接入 4 阶段动力学与自愈表情。 */
fun mascotEmotionIdForKind(kind: CapsuleKind, phase: AgentTaskPhase = AgentTaskPhase.Idle): String = when {
    kind == CapsuleKind.Done -> "33" // 撒花完成
    kind == CapsuleKind.Failed || kind == CapsuleKind.Cancelled -> "34" // 警惕/错误
    kind.needsYou -> "13"               // 惊讶/需要处理
    kind == CapsuleKind.Held -> "03"    // 挂起好奇
    kind == CapsuleKind.Running -> when (phase) {
        AgentTaskPhase.Starting -> "31"    // 阶段1：活力自旋/接收任务
        AgentTaskPhase.Exploring -> "40"   // 阶段2：左右张望寻找控件（检索姿态）
        AgentTaskPhase.Operating -> "16"   // 阶段3：专注微表情
        AgentTaskPhase.Approaching -> "19" // 阶段4：期待笑脸/满意
        AgentTaskPhase.Soothing -> "20"    // 阻断自愈：困惑挠头/抱歉合十
        else -> "30"                       // 默认思考霓虹光环
    }
    else -> "02"                        // 待机放空
}

/**
 * 悬浮胶囊，支持三态灵动岛与拟人化吉祥物融合：
 * - [CapsuleState.MINI]：48dp 灵动拟人小球，贴边呼吸与微表情；
 * - [CapsuleState.PILL]：跨应用动态胶囊，实时动作文本 + [暂停/继续] + [急停] 控制；
 * - [CapsuleState.EXPANDED]：透视全息舱，小球思考脉冲 + 虚拟屏实时画面 + 一键紧急接管 + 提速模式。
 */
@Composable
fun CapsuleContent(
    view: CapsuleView.Shown,
    onTap: () -> Unit,
    onStop: () -> Unit,
    onHandBack: () -> Unit,
    modifier: Modifier = Modifier,
    side: DockSide = DockSide.Right,
    tapLabel: String = CapsuleCopy.EXPAND,
    onSwapSide: (() -> Unit)? = null,
    alpha: Float = 1f,
    onSendScreen: (() -> Unit)? = null,
    flash: Int = 0,
    holding: Boolean = false,
    onTalk: (() -> Unit)? = null,
    talkLabel: String = "",
    capsuleState: CapsuleState? = null,
    onPauseToggle: (() -> Unit)? = null,
    isPaused: Boolean = false,
    onTakeOver: (() -> Unit)? = null,
    onSpeedToggle: ((Boolean) -> Unit)? = null,
    speedEnabled: Boolean = false,
    virtualScreenFrame: android.graphics.Bitmap? = null,
    onStateChange: ((CapsuleState) -> Unit)? = null,
    pipInput: com.hana.agent.cap.phone.display.PipInput? = null,
    pipTouchEnabled: Boolean = false,
    audioAmplitude: Float = 0f,
    teleportState: com.hana.agent.ui.TeleportTakeoverState? = null,
    onTeleportTouch: (() -> Unit)? = null,
) {
    // 淡下去、恢复都慢慢来;关了动画时直接换
    val shown = if (HanaTheme.reduceMotion) alpha else animateFloatAsState(alpha, tween(FADE_MS), label = "fade").value
    val a11y = Modifier.semantics {
        customActions = listOfNotNull(
            onSwapSide?.let { f -> CustomAccessibilityAction(CapsuleCopy.SWAP_SIDE) { f(); true } },
            onSendScreen?.let { f -> CustomAccessibilityAction(ScreenCopy.SEND_SCREEN) { f(); true } },
            onTalk?.let { f -> CustomAccessibilityAction(talkLabel) { f(); true } },
        )
    }
    val effectiveState = when {
        teleportState?.active == true -> CapsuleState.EXPANDED
        capsuleState != null -> capsuleState
        view.pill -> CapsuleState.PILL
        else -> CapsuleState.MINI
    }
    val effectiveTouch = pipTouchEnabled || (teleportState?.active == true && teleportState.touchExempted)
    Box(modifier.padding(CapsuleShadowRoom)) {
        when {
            view.kind == CapsuleKind.HandedOver && teleportState?.active != true -> TakeoverBar(view, onStop, onHandBack)
            effectiveState == CapsuleState.EXPANDED -> ExpandedCockpit(
                view = view,
                onClose = { onStateChange?.invoke(if (view.pill) CapsuleState.PILL else CapsuleState.MINI) },
                onStop = onStop,
                onTakeOver = { onTakeOver?.invoke() ?: onHandBack() },
                onSpeedToggle = onSpeedToggle,
                speedEnabled = speedEnabled,
                virtualScreenFrame = virtualScreenFrame,
                pipInput = pipInput,
                pipTouchEnabled = effectiveTouch,
                audioAmplitude = audioAmplitude,
                teleportState = teleportState,
                onTeleportTouch = onTeleportTouch,
            )
            else -> Capsule(
                view = view,
                end = side == DockSide.Right,
                onTap = onTap,
                tapLabel = tapLabel,
                a11y = a11y,
                alpha = shown,
                flash = flash,
                holding = holding,
                state = effectiveState,
                onPauseToggle = onPauseToggle,
                isPaused = isPaused,
                onStop = onStop,
                audioAmplitude = audioAmplitude,
            )
        }
    }
}

/** 一颗或展开的胶囊:同一块玻璃底,看得见的宽度在两者之间慢慢变(只在画的时候变,见 [Morph]);[end] 时贴右边(图标在右)。 */
@Composable
private fun Capsule(
    view: CapsuleView.Shown,
    end: Boolean,
    onTap: () -> Unit,
    tapLabel: String,
    a11y: Modifier,
    alpha: Float,
    flash: Int,
    holding: Boolean,
    state: CapsuleState = if (view.pill) CapsuleState.PILL else CapsuleState.MINI,
    onPauseToggle: (() -> Unit)? = null,
    isPaused: Boolean = false,
    onStop: (() -> Unit)? = null,
    audioAmplitude: Float = 0f,
) {
    val isPill = state == CapsuleState.PILL || view.pill
    val c = HanaTheme.colors
    val activeBotShape by GrokBotTheme.currentShape.collectAsState()
    val botShapeKey = remember(activeBotShape) { shapeKeyFor(activeBotShape) }
    val isWorking = view.kind == CapsuleKind.Running
    // 长按说话中:放大一点、外面一圈蓝边(画在阴影边里,窗口大小不变)
    val press = if (HanaTheme.reduceMotion) (if (holding) 1f else 0f)
        else animateFloatAsState(if (holding) 1f else 0f, tween(HOLD_MS), label = "hold").value
    val need = view.kind.needsYou || view.needCount > 0
    // 截到屏幕:白光从亮到无,和截图时屏幕闪一下一个意思;关了动画时不闪
    val glow = remember { Animatable(0f) }
    val still = HanaTheme.reduceMotion
    val morph = rememberMorph(animate = !still)
    LaunchedEffect(flash) {
        if (flash == 0 || still) return@LaunchedEffect
        glow.snapTo(FLASH_ALPHA)
        glow.animateTo(0f, tween(FLASH_MS))
    }
    Box(
        Modifier.graphicsLayer {
            scaleX = 1f + HOLD_SCALE * press
            scaleY = 1f + HOLD_SCALE * press
        }.drawBehind {
            if (press <= 0f) return@drawBehind
            val ring = HoldRing.toPx()
            val r = size.height / 2 + ring / 2
            drawRoundRect(
                c.accent.copy(alpha = press), Offset(-ring / 2, -ring / 2), Size(size.width + ring, size.height + ring),
                CornerRadius(r, r), style = Stroke(ring),
            )
        }.then(if (holding) Modifier.testTag("capsule-holding") else Modifier),
    ) {
        Box(
            Modifier.testTag(if (isPill) "capsule" else "capsule-ball")
                .morphGlass(morph, end, c, need, alpha, isPill = isPill)
                .clickable(onClickLabel = tapLabel, role = Role.Button, onClick = onTap)
                .then(if (isPill) Modifier else Modifier.semantics { contentDescription = CapsuleCopy.describe(view) })
                .then(a11y)
                .morphLayout(morph, end),
        ) {
            if (isPill) PillRow(view, end, need, onPauseToggle, isPaused, onStop, audioAmplitude)
            else Box(Modifier.size(CapsuleHeight), contentAlignment = Alignment.Center) {
                com.hana.agent.ui.components.mascot.EmotionBallMascot(
                    size = 32.dp,
                    emotionId = mascotEmotionIdForKind(view.kind, view.phase),
                    shapeKey = botShapeKey,
                    isWorking = isWorking,
                    audioAmplitude = audioAmplitude,
                )
            }
            if (glow.value > 0f) Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.White.copy(alpha = glow.value)))
        }
        // 外圈和角标不随底淡下去;缩回时贴着图标那一边,不跟着还没收完的宽度走
        if (!isPill) Box(Modifier.align(if (end) Alignment.CenterEnd else Alignment.CenterStart).size(CapsuleHeight)) {
            Ring(view.kind, need, Modifier.matchParentSize(), shape = activeBotShape)
            Badge(view, need, Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp))
        }
    }
}

/** 展开的胶囊里那一行:图标、状态标记、字;贴右边时图标在最右,和一颗时的位置一样。支持[暂停/继续]与[急停]按键。 */
@Composable
private fun PillRow(
    view: CapsuleView.Shown,
    end: Boolean,
    need: Boolean,
    onPauseToggle: (() -> Unit)? = null,
    isPaused: Boolean = false,
    onStop: (() -> Unit)? = null,
    audioAmplitude: Float = 0f,
) {
    val c = HanaTheme.colors
    val activeBotShape by GrokBotTheme.currentShape.collectAsState()
    val botShapeKey = remember(activeBotShape) { shapeKeyFor(activeBotShape) }
    val isWorking = view.kind == CapsuleKind.Running
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.72f).dp
    // 同一件事、同一个状态里换句只变宽:记下这一段里最宽的时候
    var widest by remember(view.widthEpoch) { mutableIntStateOf(0) }
    val minWidth = with(LocalDensity.current) { widest.toDp() }
    Row(
        Modifier.widthIn(min = minWidth.coerceAtMost(maxWidth), max = maxWidth)
            .onSizeChanged { if (it.width > widest) widest = it.width }
            .height(CapsuleHeight)
            .padding(start = if (end) 10.dp else 4.dp, end = if (end) 4.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (end) Alignment.End else Alignment.Start),
    ) {
        if (!end) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                com.hana.agent.ui.components.mascot.EmotionBallMascot(
                    size = 24.dp,
                    emotionId = mascotEmotionIdForKind(view.kind, view.phase),
                    shapeKey = botShapeKey,
                    isWorking = isWorking,
                    audioAmplitude = audioAmplitude,
                )
            }
        }
        Leading(view.kind)
        Text(
            view.text, Modifier.weight(1f, fill = false), style = CapsuleText,
            color = if (need) c.warn else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )

        // Pill 控制按键：暂停/继续 + 急停
        if (onPauseToggle != null) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(HanaShapes.Pill)
                    .background(c.fill)
                    .clickable(onClick = onPauseToggle)
                    .testTag(if (isPaused) "capsule-resume" else "capsule-pause"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isPaused) "▶" else "⏸",
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = c.ink2,
                )
            }
        }

        if (onStop != null) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(HanaShapes.Pill)
                    .background(c.fill)
                    .clickable(onClick = onStop)
                    .testTag("capsule-stop"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "⏹",
                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = c.warn,
                )
            }
        }

        if (end) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                com.hana.agent.ui.components.mascot.EmotionBallMascot(
                    size = 24.dp,
                    emotionId = mascotEmotionIdForKind(view.kind, view.phase),
                    shapeKey = botShapeKey,
                    isWorking = isWorking,
                    audioAmplitude = audioAmplitude,
                )
            }
        }
    }
}

/**
 * 透视全息舱 (Expanded Hologram Cockpit)：
 * - 小球思考脉冲光环
 * - 虚拟屏实时画面缩略图
 * - 一键紧急接管
 * - 提速模式开关与急停
 */
@Composable
fun ExpandedCockpit(
    view: CapsuleView.Shown,
    onClose: () -> Unit,
    onStop: () -> Unit,
    onTakeOver: () -> Unit,
    onSpeedToggle: ((Boolean) -> Unit)?,
    speedEnabled: Boolean,
    virtualScreenFrame: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
    pipInput: com.hana.agent.cap.phone.display.PipInput? = null,
    pipTouchEnabled: Boolean = false,
    audioAmplitude: Float = 0f,
    teleportState: com.hana.agent.ui.TeleportTakeoverState? = null,
    onTeleportTouch: (() -> Unit)? = null,
) {
    val c = HanaTheme.colors
    val scope = rememberCoroutineScope()
    val activeBotShape by GrokBotTheme.currentShape.collectAsState()
    val botShapeKey = remember(activeBotShape) { shapeKeyFor(activeBotShape) }
    val isWorking = view.kind == CapsuleKind.Running
    val teleporting = teleportState?.active == true
    val effectiveTouchEnabled = pipTouchEnabled || (teleporting && teleportState?.touchExempted == true)
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .testTag("capsule-expanded")
            .widthIn(min = 280.dp, max = 340.dp)
            .shadow(12.dp, shape)
            .background(c.surface, shape)
            .border(if (teleporting) 1.5.dp else 1.dp, if (teleporting) c.warn else c.edge, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部控制行：标题 + 关闭缩回按钮
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (teleporting) c.warn else c.accent, HanaShapes.Pill),
                )
                Text(
                    if (teleporting) "Hana 瞬移接管" else "透视全息舱",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = if (teleporting) c.warn else c.ink,
                )
                if (teleporting) {
                    Text(
                        "触控直通",
                        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier
                            .clip(HanaShapes.Pill)
                            .background(c.warn)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("capsule-teleport-badge"),
                    )
                }
            }
            Box(
                Modifier
                    .size(24.dp)
                    .clip(HanaShapes.Pill)
                    .background(c.fill)
                    .clickable(onClick = onClose)
                    .testTag("capsule-collapse"),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", style = TextStyle(fontSize = 11.sp), color = c.ink3)
            }
        }

        // 中间行：左侧拟人小球思考脉冲 + 右侧虚拟屏实时画面
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    Modifier.size(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    com.hana.agent.ui.components.mascot.EmotionBallMascot(
                        size = 56.dp,
                        emotionId = if (teleporting) "alert" else mascotEmotionIdForKind(view.kind, view.phase),
                        shapeKey = botShapeKey,
                        isWorking = isWorking || teleporting,
                        audioAmplitude = audioAmplitude,
                        pipDocked = teleporting,
                    )
                }
                Text(
                    view.text.ifEmpty { "智能体正在运行..." },
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                    color = if (view.kind.needsYou) c.warn else c.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 虚拟屏实时画面
            Box(
                Modifier
                    .size(width = 110.dp, height = 150.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color.Black)
                    .border(if (teleporting) 1.5.dp else 0.5.dp, if (teleporting) c.warn else c.edge, RoundedCornerShape(10.dp))
                    .testTag("capsule-virtual-screen")
                    .pointerInput(effectiveTouchEnabled, pipInput) {
                        if (!effectiveTouchEnabled || pipInput == null) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            val startY = down.position.y
                            val startTime = down.uptimeMillis
                            val boundsSize: IntSize = this@awaitEachGesture.size
                            val w = boundsSize.width.toFloat()
                            val h = boundsSize.height.toFloat()
                            if (w <= 0f || h <= 0f) return@awaitEachGesture

                            var moved = false
                            val points = mutableListOf(Pair((startX / w).coerceIn(0f, 1f), (startY / h).coerceIn(0f, 1f)))
                            var lastX = startX
                            var lastY = startY
                            var lastTime = startTime

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    lastTime = change.uptimeMillis
                                    break
                                }
                                val curX = change.position.x
                                val curY = change.position.y
                                val dx = curX - startX
                                val dy = curY - startY
                                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                if (dist > viewConfiguration.touchSlop) {
                                    moved = true
                                }
                                if (moved) {
                                    change.consume()
                                    val curTime = change.uptimeMillis
                                    if (curTime - lastTime >= 16L || dist > 10f) {
                                        points.add(Pair((curX / w).coerceIn(0f, 1f), (curY / h).coerceIn(0f, 1f)))
                                        lastX = curX
                                        lastY = curY
                                        lastTime = curTime
                                    }
                                }
                            }

                            val durationMs = maxOf(30L, lastTime - startTime)
                            val endPoint = Pair((lastX / w).coerceIn(0f, 1f), (lastY / h).coerceIn(0f, 1f))
                            if (points.lastOrNull() != endPoint) {
                                points.add(endPoint)
                            }

                            if (!moved) {
                                down.consume()
                                val fx = (startX / w).coerceIn(0f, 1f)
                                val fy = (startY / h).coerceIn(0f, 1f)
                                onTeleportTouch?.invoke()
                                scope.launch {
                                    pipInput.tap(fx, fy)
                                }
                            } else {
                                val totalDx = lastX - startX
                                val totalDy = kotlin.math.abs(lastY - startY)
                                val isLeftEdgeSwipe = (startX / w) <= 0.15f && totalDx > 25f && totalDx > totalDy * 1.2f
                                onTeleportTouch?.invoke()
                                if (isLeftEdgeSwipe) {
                                    scope.launch {
                                        pipInput.back()
                                    }
                                } else {
                                    scope.launch {
                                        pipInput.swipe(points, durationMs)
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (virtualScreenFrame != null) {
                    Image(
                        bitmap = virtualScreenFrame.asImageBitmap(),
                        contentDescription = "虚拟屏实时画面",
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Fit,
                    )
                    if (effectiveTouchEnabled && pipInput != null) {
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                                .clickable {
                                    onTeleportTouch?.invoke()
                                    scope.launch { pipInput.back() }
                                }
                                .testTag("capsule-screen-back"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("◀", style = TextStyle(fontSize = 10.sp), color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("📺", style = TextStyle(fontSize = 20.sp))
                        Text(
                            "虚拟屏就绪",
                            style = TextStyle(fontSize = 10.sp),
                            color = androidx.compose.ui.graphics.Color.LightGray,
                        )
                    }
                }
            }
        }

        // 底部动作行：一键紧急接管 + 提速模式 + 急停
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.warnSoft)
                    .clickable(onClick = onTakeOver)
                    .padding(vertical = 8.dp)
                    .testTag("capsule-takeover"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "一键紧急接管",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = c.warn,
                )
            }

            if (onSpeedToggle != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (speedEnabled) c.accentSoft else c.fill)
                        .clickable { onSpeedToggle(!speedEnabled) }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .testTag("capsule-speed-toggle"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (speedEnabled) "提速开" else "提速关",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = if (speedEnabled) c.accent else c.ink3,
                    )
                }
            }

            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.fill)
                    .clickable(onClick = onStop)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("capsule-expanded-stop"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "急停 ⏹",
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = c.ink2,
                )
            }
        }
    }
}

/** 文字前面的状态标记:要你处理是呼吸点,办好了是勾,没办成是叹号,已停止是停止,后台网页运行中是绿点。 */
@Composable
private fun Leading(kind: CapsuleKind) {
    val c = HanaTheme.colors
    when {
        kind.needsYou -> BreathingDot(c)
        kind == CapsuleKind.Done -> Icon(HanaIcons.Check, null, Modifier.size(16.dp), tint = c.ok)
        kind == CapsuleKind.Failed -> Icon(HanaIcons.Exclaim, null, Modifier.size(16.dp), tint = c.ink3)
        kind == CapsuleKind.Cancelled -> Icon(HanaIcons.Stop, null, Modifier.size(16.dp), tint = c.ink3)
        kind == CapsuleKind.KeptPage -> LiveDot(c)
        else -> Unit
    }
}

/** 后台网页运行中:绿色圆点,外面一圈淡绿。 */
@Composable
private fun LiveDot(c: HanaColors) {
    Box(Modifier.size(14.dp).testTag("capsule-live-dot"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).background(c.okSoft, HanaShapes.Pill))
        Box(Modifier.size(8.dp).background(c.ok, HanaShapes.Pill))
    }
}

/** 需要你:橙色圆点,外面一圈淡橙一呼一吸;关了动画时不动。 */
@Composable
private fun BreathingDot(c: HanaColors) {
    val halo = breathe(still = 0f)
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).graphicsLayer { alpha = halo }.background(c.warnSoft, HanaShapes.Pill))
        Box(Modifier.size(8.dp).background(c.warnSolid, HanaShapes.Pill))
    }
}

/** 一呼一吸(0..1 来回);关了动画时停在 [still]。 */
@Composable
private fun breathe(still: Float): Float = if (HanaTheme.reduceMotion) still else rememberInfiniteTransition(label = "breathe").animateFloat(
    0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breathe",
).value

/** 一颗图标的外圈:干活中一段蓝弧绕着转(关了动画时是一整圈不动),等条件灰色一整圈,要你处理橙色一整圈一呼一吸;其余没有。 */
@Composable
private fun Ring(
    kind: CapsuleKind,
    need: Boolean,
    modifier: Modifier,
    shape: GrokBotShape = GrokBotShape.Circle,
) {
    val c = HanaTheme.colors
    val color = when {
        need -> c.warnSolid
        kind == CapsuleKind.Running -> c.accent
        kind == CapsuleKind.Held -> c.ink3
        else -> return
    }
    val spin = kind == CapsuleKind.Running && !need && !HanaTheme.reduceMotion
    val angle = if (!spin) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "spin",
    ).value
    val strength = if (need) 0.45f + 0.55f * breathe(still = 1f) else 1f
    Canvas(modifier.testTag("capsule-ring")) {
        // 当处于非圆球形态（三角/方块/菱星）或任务正在运行中时，小精灵自身已具备贴合几何轮廓的专属动态流光环；
        // 此处不再重复绘制硬编码的外层圆形光弧，杜绝“几何体+圆形外环”双重光圈冲突
        if (shape != GrokBotShape.Circle || kind == CapsuleKind.Running) {
            return@Canvas
        }
        val stroke = 2.dp.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val box = Size(size.width - stroke, size.height - stroke)
        if (spin) {
            drawArc(c.accentSoft, 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
            drawArc(color, angle - 90f, 100f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            drawArc(color.copy(alpha = strength), 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
        }
    }
}

/** 一颗图标角上的标:好几件时写件数(要你处理橙、等条件灰、其余蓝);只一件时办好了绿勾,没办成、已停止灰色。 */
@Composable
private fun Badge(view: CapsuleView.Shown, need: Boolean, modifier: Modifier) {
    val c = HanaTheme.colors
    if (view.count >= 2) {
        val fill = when {
            need -> c.warnSolid
            view.kind == CapsuleKind.Held -> c.ink3
            else -> c.accent
        }
        Box(
            modifier.testTag("capsule-count").background(c.surface, HanaShapes.Pill).padding(1.5.dp)
                .sizeIn(minWidth = 15.dp, minHeight = 15.dp).background(fill, HanaShapes.Pill).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("${view.count}", style = CountText, color = c.onAccent, maxLines = 1)
        }
        return
    }
    if (need) {
        Box(
            modifier.testTag("capsule-badge").size(16.dp).background(c.surface, HanaShapes.Pill).padding(1.5.dp)
                .background(c.warnSolid, HanaShapes.Pill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HanaIcons.Exclaim, null, Modifier.size(9.dp), tint = c.surface)
        }
        return
    }
    val (icon, tint) = when (view.kind) {
        CapsuleKind.Done -> HanaIcons.Check to c.ok
        CapsuleKind.Failed -> HanaIcons.Exclaim to c.ink3
        CapsuleKind.Cancelled -> HanaIcons.Stop to c.ink3
        else -> return
    }
    Box(
        modifier.testTag("capsule-badge").size(16.dp).background(c.surface, HanaShapes.Pill).padding(1.5.dp)
            .background(tint, HanaShapes.Pill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(9.dp), tint = c.surface)
    }
}

/**
 * 一颗和胶囊之间宽度慢慢变,只变画出来的那块玻璃,不逐帧改布局:浮层窗口跟着内容大小,布局一变窗口就要跟着重排、换画布,
 * 逐帧变就一卡一卡。[target] 是内容此刻的宽,[width] 是看得见的宽(动画),[hold] 是动画期间布局占的宽——起点和终点里宽的那个,
 * 所以变宽时窗口一下到最宽、变窄时等画完才缩,一次变形窗口至多变一次。第一次出现和关了动画时直接是内容的宽度。
 */
private class Morph {
    val width = Animatable(0f)
    var target by mutableIntStateOf(0)
    var hold by mutableIntStateOf(0)
}

private val FluidSpringEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)

@Composable
private fun rememberMorph(animate: Boolean): Morph {
    val m = remember { Morph() }
    LaunchedEffect(m.target, animate) {
        val t = m.target
        if (t == 0) return@LaunchedEffect
        if (!animate || m.width.value == 0f) m.width.snapTo(t.toFloat())
        else {
            m.hold = maxOf(ceil(m.width.value).toInt(), t)
            m.width.animateTo(t.toFloat(), tween(MORPH_MS, easing = FluidSpringEasing))
        }
        m.hold = t
    }
    return m
}

/** 布局占 [Morph.hold] 和内容里宽的那个,内容贴着停靠的那一边摆([end] 时贴右边):向里长、向边收,图标不动。 */
private fun Modifier.morphLayout(m: Morph, end: Boolean): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0))
    if (p.width != m.target) m.target = p.width
    val w = maxOf(m.hold, p.width)
    layout(w, p.height) { p.place(if (end) w - p.width else 0, 0) }
}

/**
 * 三次贝塞尔流体拉伸水滴 Shape (Fluid Metaball Morphing)：
 * 从小球（半径 R）展开为药丸时，在腰部两侧引入三次贝塞尔收腰与液体表面张力拉伸，
 * 并配合 CubicBezierEasing 弹性超调，消除普通矩形展开的生硬感。
 */
class FluidMetaballShape(
    private val width: Float,
    private val end: Boolean,
    private val inset: Float = 0f,
    private val targetWidth: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = if (width > 0f) width.coerceAtMost(size.width) else size.width
        val h = size.height
        val r = (h / 2f) - inset
        val left = if (end) size.width - w else 0f
        val right = left + w

        val initialDiameter = h
        if (w <= initialDiameter + 2f || targetWidth <= initialDiameter) {
            return Outline.Rounded(RoundRect(left + inset, inset, right - inset, h - inset, CornerRadius(r, r)))
        }

        val span = (targetWidth - initialDiameter).coerceAtLeast(1f)
        val progress = ((w - initialDiameter) / span).coerceIn(0f, 1f)
        val pinchRatio = 0.12f * kotlin.math.sin(kotlin.math.PI.toFloat() * progress)
        val waistYOffset = r * pinchRatio

        val path = Path().apply {
            val top = inset
            val bottom = h - inset

            if (!end) {
                val leftCenterX = left + inset + r
                val rightCenterX = (right - inset - r).coerceAtLeast(leftCenterX)

                moveTo(leftCenterX, top)
                val cp1X = leftCenterX + (rightCenterX - leftCenterX) * 0.35f
                val cp1Y = top + waistYOffset
                val cp2X = leftCenterX + (rightCenterX - leftCenterX) * 0.65f
                val cp2Y = top + waistYOffset
                cubicTo(cp1X, cp1Y, cp2X, cp2Y, rightCenterX, top)

                arcTo(
                    rect = Rect(rightCenterX - r, top, rightCenterX + r, bottom),
                    startAngleDegrees = -90f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )

                val bcp1X = leftCenterX + (rightCenterX - leftCenterX) * 0.65f
                val bcp1Y = bottom - waistYOffset
                val bcp2X = leftCenterX + (rightCenterX - leftCenterX) * 0.35f
                val bcp2Y = bottom - waistYOffset
                cubicTo(bcp1X, bcp1Y, bcp2X, bcp2Y, leftCenterX, bottom)

                arcTo(
                    rect = Rect(leftCenterX - r, top, leftCenterX + r, bottom),
                    startAngleDegrees = 90f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )
                close()
            } else {
                val leftCenterX = left + inset + r
                val rightCenterX = (right - inset - r).coerceAtLeast(leftCenterX)

                moveTo(rightCenterX, top)
                val cp1X = rightCenterX - (rightCenterX - leftCenterX) * 0.35f
                val cp1Y = top + waistYOffset
                val cp2X = rightCenterX - (rightCenterX - leftCenterX) * 0.65f
                val cp2Y = top + waistYOffset
                cubicTo(cp1X, cp1Y, cp2X, cp2Y, leftCenterX, top)

                arcTo(
                    rect = Rect(leftCenterX - r, top, leftCenterX + r, bottom),
                    startAngleDegrees = -90f,
                    sweepAngleDegrees = -180f,
                    forceMoveTo = false,
                )

                val bcp1X = rightCenterX - (rightCenterX - leftCenterX) * 0.65f
                val bcp1Y = bottom - waistYOffset
                val bcp2X = rightCenterX - (rightCenterX - leftCenterX) * 0.35f
                val bcp2Y = bottom - waistYOffset
                cubicTo(bcp1X, bcp1Y, bcp2X, bcp2Y, rightCenterX, bottom)

                arcTo(
                    rect = Rect(rightCenterX - r, top, rightCenterX + r, bottom),
                    startAngleDegrees = 90f,
                    sweepAngleDegrees = -180f,
                    forceMoveTo = false,
                )
                close()
            }
        }
        return Outline.Generic(path)
    }
}

/**
 * 玻璃底(同 [glass]),只画看得见的那一段宽、贴着停靠的那一边:阴影、裁切、底色和描边都按它,宽度变时只重画图层。
 * [alpha] 淡底和里面的图标。[isPill] 标识是否处于展开药丸胶囊态。
 * 当处于单球态(mini 态)时，小精灵自身已具备完整的立体感、独立阴影与高光几何流光，
 * 不绘制生硬的圆形死底盘与边框，杜绝“圆壳包三角/圆壳包方块”的几何冲突。
 */
private fun Modifier.morphGlass(
    m: Morph,
    end: Boolean,
    c: HanaColors,
    need: Boolean,
    alpha: Float,
    isPill: Boolean = false,
): Modifier =
    graphicsLayer {
        this.alpha = alpha
        val isExpanding = isPill || m.width.value > size.height + 2f
        if (isExpanding) {
            shadowElevation = 8.dp.toPx()
            ambientShadowColor = c.floatShadow.copy(alpha = 0.25f)
            spotShadowColor = c.floatShadow.copy(alpha = 0.3f)
            shape = FluidMetaballShape(m.width.value, end, targetWidth = m.target.toFloat())
            clip = true
        } else {
            shadowElevation = 0f
            clip = false
        }
    }.drawWithContent {
        val isExpanding = isPill || m.width.value > size.height + 2f
        if (isExpanding) {
            val fluidShape = FluidMetaballShape(m.width.value, end, targetWidth = m.target.toFloat())
            val o = fluidShape.createOutline(size, layoutDirection, this)
            drawOutline(o, c.surface.copy(alpha = 0.95f))
            if (need) drawOutline(o, c.warnSoft)
            drawContent()
            val stroke = (if (need) 1.dp else 0.5.dp).toPx()
            val borderShape = FluidMetaballShape(m.width.value, end, inset = stroke / 2, targetWidth = m.target.toFloat())
            drawOutline(
                borderShape.createOutline(size, layoutDirection, this),
                if (need) c.warnSolid.copy(alpha = 0.5f) else c.glassEdge,
                style = Stroke(stroke)
            )
        } else {
            drawContent()
        }
    }

/** 宽 [width](0 或超出时按整块)、贴着一边([end] 时右边)的胶囊形;[inset] 往里收(画描边用)。 */
private class EdgePill(private val width: Float, private val end: Boolean, private val inset: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = if (width > 0f) width.coerceAtMost(size.width) else size.width
        val left = if (end) size.width - w else 0f
        val r = size.height / 2 - inset
        return Outline.Rounded(RoundRect(left + inset, inset, left + w - inset, size.height - inset, CornerRadius(r, r)))
    }
}

/** App 在你手上时顶部那一条:「你来处理」和下面一行小字,右边「不做了」「处理好了」。 */
@Composable
private fun TakeoverBar(view: CapsuleView.Shown, onStop: () -> Unit, onHandBack: () -> Unit) {
    val c = HanaTheme.colors
    val maxWidth = (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(200).dp
    Row(
        Modifier.testTag("capsule-bar")
            .glass(c, HanaShapes.Pill, need = false)
            .widthIn(max = maxWidth)
            .height(BarHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CapsuleLogo()
        Column(Modifier.weight(1f, fill = false).semantics(mergeDescendants = true) {}) {
            Text(view.text, style = CapsuleText, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            view.detail?.let { Text(it, style = BarDetail, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Box(
            Modifier.clip(HanaShapes.Pill).clickable(role = Role.Button, onClick = onStop)
                .heightIn(min = 36.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(CapsuleCopy.STOP, style = CapsuleText.copy(fontWeight = FontWeight.Normal), color = c.ink2, maxLines = 1)
        }
        HanaButton(stringResource(R.string.action_hand_back), onHandBack, size = ButtonSize.Small)
    }
}

/** 胶囊上的 Hana 图标(圆形)。 */
@Composable
private fun CapsuleLogo() {
    HanaLogo(LogoSize, corner = LogoSize / 2)
}

/**
 * 浮在别的 App 上的玻璃底:浮层窗口里拿不到下面 App 的画面做模糊,用接近不透明的卡片底、细描边和悬浮阴影;
 * 需要你时叠一层淡橙、描边变橙。
 */
internal fun Modifier.glass(c: HanaColors, shape: Shape, need: Boolean): Modifier =
    shadow(8.dp, shape, clip = false, ambientColor = c.floatShadow.copy(alpha = 0.25f), spotColor = c.floatShadow.copy(alpha = 0.3f))
        .clip(shape)
        .background(c.surface.copy(alpha = 0.95f))
        .then(if (need) Modifier.background(c.warnSoft).border(1.dp, c.warnSolid.copy(alpha = 0.5f), shape) else Modifier.border(0.5.dp, c.glassEdge, shape))

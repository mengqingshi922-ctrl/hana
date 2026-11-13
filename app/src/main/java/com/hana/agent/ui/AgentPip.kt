package com.hana.agent.ui

import com.hana.agent.R
import com.hana.agent.cap.phone.display.PipInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.view.Display
import android.util.DisplayMetrics
import android.hardware.display.DisplayManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.hana.agent.ui.theme.HanaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.agent.ui.PipPhysics.Corner
import com.hana.agent.ui.components.GrokBotTheme
import com.hana.agent.ui.components.VisualTouchOverlay
import com.hana.agent.ui.components.floatShadow
import com.hana.agent.ui.components.mascot.EmotionBallMascot
import com.hana.agent.ui.components.mascot.shapeKeyFor
import com.hana.agent.ui.theme.HanaIcons
import com.hana.agent.ui.theme.HanaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Hana Teleport 瞬移接管状态：
 * 当任务遇到滑块、图形验证码或 2FA 拦截时，小窗自动高亮弹出并临时豁免 PipInput 触摸穿透，
 * 用户动手完成后监测到虚拟屏画面发生变化即自动恢复常规小窗状态。
 */
data class TeleportTakeoverState(
    val active: Boolean = false,
    val reason: String = "",
    val message: String = "",
    val touchExempted: Boolean = false,
    val userInteracted: Boolean = false,
    val anchorFrameHash: Int = 0,
    val lastFrameHash: Int = 0,
    val autoRestored: Boolean = false,
)

/**
 * Hana Teleport 接管状态协调器（可在 Compose 与 CapsuleController 间共享，也支持纯 JVM 单元测试）。
 */
class PipTeleportCoordinator {
    private val _state = MutableStateFlow(TeleportTakeoverState())
    val state: StateFlow<TeleportTakeoverState> = _state.asStateFlow()

    /**
     * 触发小窗瞬移接管：强制展开高亮，并临时豁免放行 PipInput 触摸穿透（无视设置总开关关闭状态）。
     */
    fun teleportForTakeover(
        reason: String,
        message: String = "",
        currentFrame: Bitmap? = null,
    ): TeleportTakeoverState {
        val current = _state.value
        val hash = computeFrameSignature(currentFrame)
        if (current.active && current.reason == reason && !current.autoRestored) {
            val next = if (current.anchorFrameHash == 0 && hash != 0) {
                current.copy(anchorFrameHash = hash, lastFrameHash = hash, message = message.ifBlank { current.message })
            } else if (message.isNotBlank() && current.message != message) {
                current.copy(message = message)
            } else {
                current
            }
            _state.value = next
            return next
        }
        val next = TeleportTakeoverState(
            active = true,
            reason = reason,
            message = message,
            touchExempted = true,
            userInteracted = false,
            anchorFrameHash = hash,
            lastFrameHash = hash,
            autoRestored = false,
        )
        _state.value = next
        return next
    }

    /**
     * 决定当前是否放行 PipInput 触摸：设置已打开，或处于 Teleport 临时豁免期。
     */
    fun isEffectiveTouchEnabled(settingEnabled: Boolean): Boolean {
        val s = _state.value
        return settingEnabled || (s.active && s.touchExempted)
    }

    /**
     * 标记用户已在小窗虚拟屏上执行过点击/滑动/返回操作，开始监听画面变动以自动恢复。
     */
    fun onUserTouchInjected() {
        val s = _state.value
        if (!s.active) return
        if (!s.userInteracted) {
            _state.value = s.copy(userInteracted = true)
        }
    }

    /**
     * 喂入新帧画面，若用户已介入且画面签名发生变化，自动退出瞬移接管与触摸豁免。
     * @return true 表示本次帧变化触发了自动恢复
     */
    fun onFrame(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        return onFrameHash(computeFrameSignature(bitmap))
    }

    /**
     * 基于帧哈希判断画面是否变化，便于无 Android Bitmap 的单元测试直接驱动。
     */
    fun onFrameHash(frameHash: Int): Boolean {
        val s = _state.value
        if (!s.active || frameHash == 0) return false
        if (s.anchorFrameHash == 0) {
            _state.value = s.copy(anchorFrameHash = frameHash, lastFrameHash = frameHash)
            return false
        }
        if (s.userInteracted && frameHash != s.anchorFrameHash) {
            _state.value = s.copy(
                active = false,
                touchExempted = false,
                lastFrameHash = frameHash,
                autoRestored = true,
            )
            return true
        }
        _state.value = s.copy(lastFrameHash = frameHash)
        return false
    }

    fun onTakeoverResolved() {
        restore(autoRestored = false)
    }

    fun restore(autoRestored: Boolean = false) {
        _state.value = TeleportTakeoverState(autoRestored = autoRestored)
    }

    companion object {
        private val ELIGIBLE_KEYWORDS = listOf(
            "captcha", "slider", "2fa", "otp", "mfa", "verify", "verification", "login", "auth",
            "验证码", "滑块", "二次验证", "短信验证", "安全验证", "人脸", "人机",
        )

        fun isTeleportEligibleReason(reason: String?): Boolean {
            if (reason.isNullOrBlank()) return false
            val lower = reason.lowercase()
            return ELIGIBLE_KEYWORDS.any { lower.contains(it) }
        }

        /**
         * 采样 4x4 亮度网格生成紧凑画面签名，识别验证码通过后的页面切换，零位返回 0。
         */
        fun computeFrameSignature(bitmap: Bitmap?): Int {
            if (bitmap == null) return 0
            val w = runCatching { bitmap.width }.getOrDefault(0)
            val h = runCatching { bitmap.height }.getOrDefault(0)
            if (w <= 0 || h <= 0) return 0
            return runCatching {
                var acc = 17
                for (gy in 0 until 4) {
                    val y = ((gy + 0.5f) * h / 4f).toInt().coerceIn(0, h - 1)
                    for (gx in 0 until 4) {
                        val x = ((gx + 0.5f) * w / 4f).toInt().coerceIn(0, w - 1)
                        val px = bitmap.getPixel(x, y)
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        val luma = ((r * 30 + g * 59 + b * 11) / 100) shr 4
                        acc = acc * 31 + luma
                    }
                }
                if (acc == 0) 1 else acc
            }.getOrDefault(0)
        }
    }
}

/** 小窗宽度:收起 / 展开。高度按画面(没有画面时按主屏)的长宽比算,整屏完整显示、不裁底部。 */
private val CollapsedWidth = 96.dp
private val ExpandedWidth = 220.dp

/** 主屏的高宽比(按真实像素,含系统栏):虚拟屏按主屏尺寸建,还没有画面时小窗先按它定大小。 */
private fun mainScreenAspect(context: Context): Float {
    val m = DisplayMetrics()
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(m)
    return if (m.widthPixels > 0 && m.heightPixels > 0) m.heightPixels.toFloat() / m.widthPixels else DEFAULT_ASPECT
}

private const val DEFAULT_ASPECT = 2.2f
private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * 悬浮画中画:盖在对话上,只显示 Agent 虚拟屏的实时画面,只看不点;上面不放文字和按钮,要你做的事都在对话卡片里。
 * 状态只靠边框光:干活中细绿边,需要你时橙色呼吸光晕(接管开始时由 [ChatViewModel] 自动展开)。
 * 平时停在右下角、输入栏上方,不挡顶栏和最新的消息。
 * 拖动 1:1 跟手;松手按速度投射落点吸到最近的角(临界阻尼弹簧,见 [PipPhysics]);
 * 点一下展开,点 × 或外面收起;需要你时点一下交给 [onAttention](收起并滚到那张卡片)。
 * 展开时左上角「关掉小画面」([onClose]):这个任务结束前只剩右下角一个小按钮([PipUi.closed]),
 * 点它再打开([onReopen]);要你处理时小按钮变橙色、呼吸,点它同样交给 [onAttention]。
 * [reduceMotion] 时(系统关了动画)不做弹簧、缩放和呼吸,直接到位。
 * [topInset]/[bottomInset] 是顶栏和输入框占的像素高度,小窗不会停在它们下面。
 */
@Composable
fun AgentPip(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    reduceMotion: Boolean,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
    onClose: () -> Unit = {},
    onReopen: () -> Unit = {},
    pipInput: PipInput? = null,
    pipTouchEnabled: Boolean = false,
    teleportReason: String? = null,
    teleportCoordinator: PipTeleportCoordinator? = null,
    audioAmplitude: Float = 0f,
    mascotEmotionId: String? = null,
) {
    val activeCoordinator = teleportCoordinator ?: remember { PipTeleportCoordinator() }
    val teleportState by activeCoordinator.state.collectAsState()

    LaunchedEffect(teleportReason) {
        if (!teleportReason.isNullOrBlank()) {
            activeCoordinator.teleportForTakeover(teleportReason, currentFrame = frame)
        } else if (teleportCoordinator == null) {
            activeCoordinator.onTakeoverResolved()
        }
    }
    LaunchedEffect(frame) {
        if (frame != null) {
            activeCoordinator.onFrame(frame)
        }
    }

    val isTeleporting = teleportState.active
    val effectivePip = if (isTeleporting) {
        pip.copy(visible = true, closed = false, expanded = true, mode = PipMode.NeedsYou)
    } else {
        pip
    }
    val effectiveTouchEnabled = pipTouchEnabled || (isTeleporting && teleportState.touchExempted)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fade = if (reduceMotion) tween<Float>(150) else tween(300)
        // 关掉了:只留右下角的小按钮(任务做完那几秒不留)
        AnimatedVisibility(
            effectivePip.visible && effectivePip.closed && effectivePip.mode != PipMode.Done,
            Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(fade),
            exit = fadeOut(fade),
        ) {
            val bottom = with(LocalDensity.current) { bottomInset.toDp() }
            ClosedPip(effectivePip.mode == PipMode.NeedsYou, reduceMotion, Modifier.padding(end = 12.dp, bottom = bottom + 12.dp), onReopen, onAttention)
        }
        AnimatedVisibility(effectivePip.visible && !effectivePip.closed && effectivePip.expanded, enter = fadeIn(fade), exit = fadeOut(fade)) {
            Box(Modifier.fillMaxSize().background(HanaTheme.colors.floatShadow.copy(alpha = 0.12f)).pointerInput(Unit) { detectTapGestures { onCollapse() } })
        }
        AnimatedVisibility(effectivePip.visible && !effectivePip.closed, enter = fadeIn(fade), exit = fadeOut(if (reduceMotion) tween(150) else tween(500))) {
            PipWindow(
                effectivePip, frame, webContent, reduceMotion,
                areaWidth = constraints.maxWidth.toFloat(), areaHeight = constraints.maxHeight.toFloat(),
                topInset = topInset, bottomInset = bottomInset, onExpand = onExpand, onCollapse = onCollapse, onAttention = onAttention,
                onClose = onClose,
                pipInput = pipInput,
                pipTouchEnabled = effectiveTouchEnabled,
                teleportState = teleportState,
                onTeleportTouch = { activeCoordinator.onUserTouchInjected() },
                audioAmplitude = audioAmplitude,
                mascotEmotionId = mascotEmotionId,
            )
        }
    }
}

/**
 * 小画面关掉后剩下的小按钮:平时写「显示画面」,点了再打开;要你处理时橙色呼吸、写「等你处理」,
 * 点了滚到要你处理的那张卡片(小画面关着也不漏掉要你做的事)。
 */
@Composable
private fun ClosedPip(needsYou: Boolean, reduceMotion: Boolean, modifier: Modifier, onReopen: () -> Unit, onAttention: () -> Unit) {
    val c = HanaTheme.colors
    val shape = RoundedCornerShape(50)
    val breath = if (needsYou && !reduceMotion) {
        rememberInfiniteTransition(label = "closedBreathe").animateFloat(
            1f, 0.45f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "closedBreathe",
        ).value
    } else 1f
    val label = stringResource(if (needsYou) R.string.pip_closed_needs_you else R.string.pip_reopen)
    Row(
        modifier.floatShadow(c, shape).background(c.glass, shape)
            .border(if (needsYou) 2.dp else 0.5.dp, if (needsYou) c.warnSolid.copy(alpha = breath) else c.glassEdge, shape)
            .clip(shape)
            .clickable(onClickLabel = label, role = Role.Button) { if (needsYou) onAttention() else onReopen() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("pip-closed"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (needsYou) Box(Modifier.size(7.dp).alpha(breath).background(c.warnSolid, CircleShape))
        else Icon(HanaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink2)
        Text(label, style = HanaType.Hint, color = if (needsYou) c.warn else c.ink2)
    }
}

@Composable
private fun PipWindow(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    reduceMotion: Boolean,
    areaWidth: Float,
    areaHeight: Float,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
    onClose: () -> Unit,
    pipInput: PipInput? = null,
    pipTouchEnabled: Boolean = false,
    teleportState: TeleportTakeoverState = TeleportTakeoverState(),
    onTeleportTouch: () -> Unit = {},
    audioAmplitude: Float = 0f,
    mascotEmotionId: String? = null,
) {
    val density = LocalDensity.current
    val sizeSpec = if (reduceMotion) snap() else tween<androidx.compose.ui.unit.Dp>(350, easing = Ease)
    // 大小跟着画面的长宽比走:手机画面是主屏的比例(不同手机略有长短),桌面网页是横向的,按固定的宽度定高度,
    // 画面整张放得下;放不下时按比例整体缩小
    val context = LocalContext.current
    val screenAspect = remember { mainScreenAspect(context) }
    val landscapeWeb = frame != null && webContent && frame.width > frame.height
    val aspect = frame?.let { it.height.toFloat() / it.width } ?: screenAspect
    val target = run {
        val margin = with(density) { 24.dp.toPx() }
        val availableWidth = with(density) { (areaWidth - margin).coerceAtLeast(1f).toDp() }
        val availableHeight = with(density) { (areaHeight - topInset - bottomInset - margin).coerceAtLeast(1f).toDp() }
        val base = when {
            landscapeWeb -> if (pip.expanded) 320.dp else 220.dp
            else -> if (pip.expanded) ExpandedWidth else CollapsedWidth
        }
        val width = minOf(base, availableWidth, availableHeight / aspect)
        width to width * aspect
    }
    val w by animateDpAsState(target.first, sizeSpec, label = "pipW")
    val h by animateDpAsState(target.second, sizeSpec, label = "pipH")
    val wPx = with(density) { w.toPx() }
    val hPx = with(density) { h.toPx() }
    val bounds = PipPhysics.Bounds(areaWidth, areaHeight, wPx, hPx, with(density) { 12.dp.toPx() }, topInset, bottomInset)
    val currentBounds by rememberUpdatedState(bounds)
    val motionOff by rememberUpdatedState(reduceMotion)
    val expanded by rememberUpdatedState(pip.expanded)
    val expand by rememberUpdatedState(onExpand)
    val needsYou by rememberUpdatedState(pip.mode == PipMode.NeedsYou)
    val attend by rememberUpdatedState(onAttention)

    var corner by rememberSaveable { mutableStateOf(Corner.BottomEnd) }
    // 拖动或弹簧途中的位置;为空表示贴在 corner 上(展开收起时跟着大小一起贴角)
    var free by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }

    /** 从 [start] 以速度 (vx, vy) 弹到角 [c];关了动画时直接到位。 */
    fun springTo(c: Corner, start: Offset, vx: Float, vy: Float) {
        settle?.cancel()
        corner = c
        if (motionOff) {
            free = null
            return
        }
        settle = scope.launch {
            val spring = PipPhysics.Spring()
            var mx = PipPhysics.Motion(start.x, vx)
            var my = PipPhysics.Motion(start.y, vy)
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9f
                last = now
                val (tx, ty) = currentBounds.position(c)
                mx = spring.step(mx, tx, dt)
                my = spring.step(my, ty, dt)
                free = Offset(mx.position, my.position)
                if (spring.settled(mx, tx) && spring.settled(my, ty)) break
            }
            free = null
        }
    }

    fun release(vx: Float, vy: Float) {
        val start = free ?: return
        springTo(PipPhysics.snapCorner(start.x, start.y, vx, vy, currentBounds), start, vx, vy)
    }

    // 完成时如果停在下方两个角,先挪到同侧上方,免得盖住刚出来的结果卡片
    LaunchedEffect(pip.mode) {
        if (pip.mode != PipMode.Done) return@LaunchedEffect
        val up = when (corner) {
            Corner.BottomStart -> Corner.TopStart
            Corner.BottomEnd -> Corner.TopEnd
            else -> return@LaunchedEffect
        }
        springTo(up, free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }, 0f, 0f)
    }

    val pos = free ?: bounds.position(corner).let { (x, y) -> Offset(x, y) }
    val outer = RoundedCornerShape(if (pip.expanded) 26.dp else 16.dp)
    val inner = RoundedCornerShape(if (pip.expanded) 22.dp else 13.dp)
    val breath = if ((pip.mode == PipMode.NeedsYou || teleportState.active) && !reduceMotion) {
        rememberInfiniteTransition(label = "breathe").animateFloat(
            1f, 0.55f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "breathe",
        ).value
    } else 1f
    val activeBotShape by GrokBotTheme.currentShape.collectAsState()
    val botShapeKey = remember(activeBotShape) { shapeKeyFor(activeBotShape) }
    val resolvedEmotionId = mascotEmotionId ?: when {
        teleportState.active -> "alert"
        pip.mode == PipMode.NeedsYou -> "curious"
        pip.mode == PipMode.Done -> "happy"
        else -> "working"
    }
    val screenInsetPx = with(density) { (if (pip.expanded) 4.dp else 3.dp).toPx() }
    val currentTouchPass by rememberUpdatedState(pipTouchEnabled && pipInput != null && pip.expanded)

    Box(Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }) {
        if (pip.mode == PipMode.NeedsYou || teleportState.active) {
            // 橙色光晕:比窗口大一圈,模糊后透出边缘(安卓 12 以下不模糊,只是一圈淡橙色)
            Box(
                Modifier.offset((-5).dp, (-5).dp).size(w + 10.dp, h + 10.dp).alpha(breath)
                    .blur(10.dp).background(HanaTheme.colors.warnSolid.copy(alpha = if (teleportState.active) 0.72f else 0.55f), outer),
            )
        }
        Box(
            Modifier.size(w, h)
                .alpha(if (pip.mode == PipMode.Done) 0.85f else 1f)
                .floatShadow(HanaTheme.colors, outer)
                .background(HanaTheme.colors.glass, outer)
                .border(0.5.dp, HanaTheme.colors.glassEdge, outer)
                .pointerInput(currentTouchPass) {
                    val tracker = VelocityTracker()
                    // 自己数位移而不用 detectDragGestures:越过触摸阈值时把阈值内的那段也补上,
                    // 窗口始终保持按下时的抓取位置,真正 1:1 跟手
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 触摸穿透激活时，落在内屏区域的手势优先给虚拟屏滑块/点击，不拖拽小窗本身
                        if (currentTouchPass &&
                            down.position.x in screenInsetPx..(size.width - screenInsetPx) &&
                            down.position.y in screenInsetPx..(size.height - screenInsetPx)
                        ) {
                            return@awaitEachGesture
                        }
                        val origin = free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }
                        var total = Offset.Zero
                        var dragging = false
                        tracker.resetTracking()
                        tracker.addPosition(down.uptimeMillis, origin + down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed || change.isConsumed) break
                            total += change.positionChange()
                            if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                                settle?.cancel()
                            }
                            if (dragging) {
                                change.consume()
                                free = origin + total
                                tracker.addPosition(change.uptimeMillis, origin + down.position + total)
                            }
                        }
                        if (dragging) tracker.calculateVelocity().let { v -> release(v.x, v.y) }
                    }
                }
                .pointerInput(currentTouchPass) {
                    if (currentTouchPass) return@pointerInput
                    detectTapGestures { if (needsYou) attend() else if (!expanded) expand() }
                }
                .padding(if (pip.expanded) 4.dp else 3.dp),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(inner)
                    .background(HanaTheme.colors.surface)
                    .testTag("pip-screen-box")
                    .pointerInput(pipTouchEnabled, pipInput) {
                        if (!pipTouchEnabled || pipInput == null) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            val startY = down.position.y
                            val startTime = down.uptimeMillis
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
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
                                onTeleportTouch()
                                scope.launch {
                                    pipInput.tap(fx, fy)
                                }
                            } else {
                                val totalDx = lastX - startX
                                val totalDy = kotlin.math.abs(lastY - startY)
                                val isLeftEdgeSwipe = (startX / w) <= 0.15f && totalDx > 30f && totalDx > totalDy * 1.2f
                                onTeleportTouch()
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
                    }
            ) {
                if (frame != null) {
                    val image = remember(frame) { frame.asImageBitmap() }
                    Image(image, stringResource(R.string.pip_screen), Modifier.fillMaxSize(), alignment = Alignment.TopCenter,
                        contentScale = if (webContent) ContentScale.Fit else ContentScale.Crop)
                }
                VisualTouchOverlay(
                    modifier = Modifier.matchParentSize(),
                    reduceMotion = reduceMotion || HanaTheme.reduceMotion,
                )
            }
            if (pip.expanded) {
                // 左上角:关掉这个任务的小画面(只剩右下角的小按钮);右上角 × 只是收起
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(26.dp)
                        .floatShadow(HanaTheme.colors, CircleShape).background(HanaTheme.colors.glass, CircleShape).clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.pip_close), onClick = onClose)
                        .testTag("pip-close"),
                    contentAlignment = Alignment.Center,
                ) { Icon(HanaIcons.EyeOff, stringResource(R.string.pip_close), Modifier.size(13.dp), tint = HanaTheme.colors.ink) }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                        .floatShadow(HanaTheme.colors, CircleShape).background(HanaTheme.colors.glass, CircleShape).clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.collapse), onClick = onCollapse),
                    contentAlignment = Alignment.Center,
                ) { Icon(HanaIcons.Close, stringResource(R.string.collapse), Modifier.size(12.dp), tint = HanaTheme.colors.ink) }

                if (pipTouchEnabled && pipInput != null) {
                    // 返回按键：向虚拟屏注入 KEYCODE_BACK
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(6.dp).size(26.dp)
                            .floatShadow(HanaTheme.colors, CircleShape).background(HanaTheme.colors.glass, CircleShape).clip(CircleShape)
                            .clickable(onClickLabel = stringResource(R.string.back)) {
                                onTeleportTouch()
                                scope.launch { pipInput.back() }
                            }
                            .testTag("pip-back"),
                        contentAlignment = Alignment.Center,
                    ) { Icon(HanaIcons.Back, stringResource(R.string.back), Modifier.size(13.dp), tint = HanaTheme.colors.ink) }
                }
            }
        }
        // 边框微光画在玻璃框外沿:运行中为轻盈细致的科技微蓝描边，需要接管时为呼吸暖橙
        val glow = when {
            teleportState.active -> HanaTheme.colors.warnSolid.copy(alpha = breath) to 2.dp
            pip.mode == PipMode.Running -> HanaTheme.colors.accent.copy(alpha = 0.45f) to 1.dp
            pip.mode == PipMode.NeedsYou -> HanaTheme.colors.warnSolid.copy(alpha = breath) to 1.5.dp
            else -> null
        }
        if (glow != null) Box(Modifier.size(w, h).border(glow.second, glow.first, outer))

        // 小窗外缘右下角吸附 Hana 原创星灵花瓣精灵（不遮挡虚拟屏像素）
        EmotionBallMascot(
            size = if (pip.expanded) 34.dp else 26.dp,
            emotionId = resolvedEmotionId,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 8.dp, y = 8.dp)
                .testTag("pip-docked-mascot"),
            shapeKey = botShapeKey,
            isWorking = pip.mode == PipMode.Running || teleportState.active,
            audioAmplitude = audioAmplitude,
            pipDocked = true,
        )

        if (teleportState.active) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-24).dp)
                    .clip(RoundedCornerShape(50))
                    .background(HanaTheme.colors.warnSolid.copy(alpha = 0.94f))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .testTag("pip-teleport-badge"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (teleportState.userInteracted) "已接管 · 画面更新后自动恢复" else "Hana 瞬移接管 · 触控已放行",
                    style = HanaType.Label.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        }
    }
}

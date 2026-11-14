package com.hana.agent.cap.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.app.Notification
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.hana.agent.core.uistate.ScreenEvent
import com.hana.agent.core.uistate.normalize
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Hana 的无障碍服务:操作和事件转给当前已连接实例,任务持有旧实例时也能在重连后恢复。 */
open class ScreenAccessibility : AccessibilityService() {

    private class DisplayEvent(val displayId: Int, val event: AccessibilityEvent)

    private data class PendingTextInput(
        val packageName: String,
        val targetLabel: String,
        val inputSample: String,
        val resourceId: String?,
        val normalizedBounds: List<Int>?,
        val timestampMs: Long,
    )

    private val teachLock = Any()
    private val teachHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingTextInput: PendingTextInput? = null
    private var pendingTextRunnable: Runnable? = null
    private val lastScrollState = HashMap<String, Pair<Int, Int>>()
    private var lastScrollEmitKey: String? = null
    private var lastScrollEmitUptimeMs: Long = 0L
    private val teachFlushHook: () -> Unit = { flushPendingTextInput() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 不少 App 把显示结果的文字控件标成「对无障碍不重要」,不读它们就看不到结果;配置文件里已声明,
        // 这里再按运行时补一次,防止系统沿用旧的服务配置
        serviceInfo?.let { info ->
            if (info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS == 0) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
        }
        com.hana.agent.teach.TeachRuntime.instance.registerFlushHook(teachFlushHook)
        _instance.value = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        _instance.compareAndSet(this, null)
        com.hana.agent.teach.TeachRuntime.instance.unregisterFlushHook(teachFlushHook)
        synchronized(teachLock) {
            pendingTextRunnable?.let { teachHandler.removeCallbacks(it) }
            pendingTextRunnable = null
            pendingTextInput = null
            lastScrollState.clear()
            lastScrollEmitKey = null
        }
        overlayHost?.closeAll()
        overlayHost = null
    }

    private var overlayHost: OverlayHost? = null

    /** 在用户屏幕上画浮层用的宿主(只在主线程用);服务断开时它加的窗口一起拆掉。 */
    fun overlays(): OverlayHost = overlayHost ?: OverlayHost(this).also { overlayHost = it }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (instance.value !== this) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowChanges.tryEmit(Unit)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            windowGeneration.incrementAndGet()
        }
        if (event.eventType and IGNORED_CONTENT_EVENTS == 0) {
            contentGeneration.incrementAndGet()
        }
        val access = (application as com.hana.agent.HanaApp).appAccess.current()
        if (access.denial(event.packageName?.toString()) != null) return

        // 「教 Hana」示范录制 2.0 捕获：支持点击/长按/滚动方向捕获、控件 resourceId 锚点提取及 800ms 输入防抖合并
        runCatching {
            captureTeachEvent(event)
        }
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && notifications.subscriptionCount.value > 0) {
            val notification = event.parcelableData as? Notification
            val title = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val body = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val text = listOfNotNull(title, body).joinToString(" ").ifBlank { event.text.joinToString(" ") }
            notifications.tryEmit(ScreenEvent("notification", event.packageName?.toString(), text.take(2_000)))
        }
        // 没人订阅就不复制、不查屏号,事件很密
        if (events.subscriptionCount.value == 0) return
        val displayId = displayOf(event)
        if (displayId == Display.INVALID_DISPLAY) return
        // API 33 以下系统会在回调返回后回收事件对象,订阅方异步读取前先复制一份
        events.tryEmit(DisplayEvent(displayId, AccessibilityEvent(event)))
    }

    override fun onInterrupt() = Unit

    /** 指定屏上的窗口;该屏没有窗口(或不存在)时为空列表。 */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> =
        instance.value?.windowsOnAllDisplays?.get(displayId).orEmpty()

    /** 在指定屏上沿 [path] 做一次单指手势;完成返回 true,被取消或无法下发返回 false。 */
    suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean {
        return gesture(displayId, listOf(path), durationMs)
    }

    suspend fun gesture(displayId: Int, paths: List<Path>, durationMs: Long): Boolean =
        dispatch(displayId, paths.map { GestureDescription.StrokeDescription(it, 0, durationMs.coerceAtLeast(1)) })

    /** 在同一点上连点 [count] 下(一次手势里先后几笔):每下按住 [holdMs],两下之间隔 [gapMs]。 */
    suspend fun taps(displayId: Int, x: Float, y: Float, count: Int, holdMs: Long, gapMs: Long): Boolean {
        val at = Path().apply { moveTo(x, y) }
        return dispatch(displayId, (0 until count).map { i -> GestureDescription.StrokeDescription(at, i * (holdMs + gapMs), holdMs.coerceAtLeast(1)) })
    }

    private suspend fun dispatch(displayId: Int, strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val service = instance.value ?: return false
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .apply { strokes.forEach(::addStroke) }
            .build()
        return suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
        }
    }

    /** 截一张用户屏幕(主屏)的结果:画面,或屏上有防截屏窗口,或截不到(错误码)。 */
    sealed interface MainShot {
        class Ok(val bitmap: Bitmap) : MainShot
        data object Secure : MainShot
        data class Failed(val code: Int) : MainShot
    }

    /**
     * 截一张主屏(用户看着的那块屏,包括 Hana 自己的浮层:要不要藏起来由调用方定)。系统限制两次截图至少隔一会儿,
     * 太快时采用自适应快速退避重试（系统底层限制约为 250ms），避免死等 1000ms 造成严重卡顿。
     */
    suspend fun takeMainScreenshot(): MainShot {
        val first = screenshotOnce()
        if (first !is MainShot.Failed || first.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return first
        // 快速自适应重试：先等待 280ms 快速重试
        kotlinx.coroutines.delay(280L)
        val second = screenshotOnce()
        if (second !is MainShot.Failed || second.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return second
        // 偶发慢速窗口时二次退避 350ms
        kotlinx.coroutines.delay(350L)
        return screenshotOnce()
    }

    private suspend fun screenshotOnce(): MainShot = suspendCancellableCoroutine { cont ->
        val service = instance.value
        if (service == null) {
            cont.resume(MainShot.Failed(ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS))
            return@suspendCancellableCoroutine
        }
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = runCatching {
                    result.hardwareBuffer.use { buffer ->
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    }
                }.getOrNull()
                if (cont.isActive) cont.resume(shot?.let(MainShot::Ok) ?: MainShot.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }

            override fun onFailure(errorCode: Int) {
                // 屏上有防截屏窗口:安卓 14 起有专门的错误码;更早的系统截出来是黑的,由调用方看画面判断
                val secure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                if (cont.isActive) cont.resume(if (secure) MainShot.Secure else MainShot.Failed(errorCode))
            }
        })
    }

    /**
     * 发生在指定屏上的无障碍事件。通道跨实例保留,只有当前连接的服务发布事件。
     */
    fun eventsFor(displayId: Int): Flow<AccessibilityEvent> =
        events.filter { it.displayId == displayId }.map { it.event }

    fun notificationEvents(): Flow<ScreenEvent> = notifications

    /** 窗口变了的信号(见 [windowChanges]);通道跨服务实例保留。 */
    fun windowChanges(): Flow<Unit> = windowChanges

    /**
     * 事件所在的屏。API 33 起事件自带屏号;更早的系统按事件的窗口号去各屏窗口里找,
     * 找不到(如没有窗口号的通知类事件)就丢弃。
     */
    /**
     * 捕获「教 Hana」示范录制动作：
     * - 支持点击（tap）、长按（long_press）、输入（type_text）、滚动方向（scroll_down / scroll_up）；
     * - 提取控件 `viewIdResourceName` 作为稳定锚点，并附带归一化屏幕坐标；
     * - 对 `TYPE_VIEW_TEXT_CHANGED` 采用 800ms 防抖合并，并在收到其它交互或切换控件时立即刷入，确保步骤顺序严谨。
     */
    private fun captureTeachEvent(event: AccessibilityEvent) {
        val teach = com.hana.agent.teach.TeachRuntime.instance
        if (teach.state.value != com.hana.agent.teach.TeachState.RECORDING) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName) return

        val isRelevant = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (!isRelevant) return

        val source = runCatching { event.source }.getOrNull()
        var resourceId: String? = null
        var normalizedBounds: List<Int>? = null
        var sourceFallbackLabel = ""
        if (source != null) {
            try {
                resourceId = source.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() }
                sourceFallbackLabel = source.hintText?.toString().orEmpty()
                    .ifBlank { source.contentDescription?.toString().orEmpty() }
                    .ifBlank { source.text?.toString().orEmpty() }
                    .trim()
                val dm = resources.displayMetrics
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    val rect = Rect()
                    source.getBoundsInScreen(rect)
                    if (!rect.isEmpty) {
                        normalizedBounds = listOf(
                            normalize(rect.left.coerceIn(0, dm.widthPixels), dm.widthPixels),
                            normalize(rect.top.coerceIn(0, dm.heightPixels), dm.heightPixels),
                            normalize(rect.right.coerceIn(0, dm.widthPixels), dm.widthPixels),
                            normalize(rect.bottom.coerceIn(0, dm.heightPixels), dm.heightPixels),
                        )
                    }
                }
            } finally {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    runCatching { source.recycle() }
                }
            }
        }

        val actionType = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "tap"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "long_press"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "type_text"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> resolveScrollAction(event, pkg, resourceId)
            else -> null
        } ?: return

        val rawEventText = event.text.joinToString(" ").trim()
        val label = rawEventText
            .ifBlank { event.contentDescription?.toString().orEmpty().trim() }
            .ifBlank { sourceFallbackLabel }
            .take(80)
            .ifBlank { "控件" }

        val nowMs = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val inputText = rawEventText.take(80)
            val candidate = PendingTextInput(
                packageName = pkg,
                targetLabel = if (sourceFallbackLabel.isNotBlank() && sourceFallbackLabel != inputText) sourceFallbackLabel.take(80) else label,
                inputSample = inputText,
                resourceId = resourceId,
                normalizedBounds = normalizedBounds,
                timestampMs = nowMs,
            )
            scheduleDebouncedTextInput(candidate)
            return
        }

        // 收到点击/长按/滚动等新动作时，先把防抖队列里未落盘的输入刷入，防止步骤乱序
        flushPendingTextInput()

        if (actionType == "scroll_down" || actionType == "scroll_up") {
            val uptime = SystemClock.uptimeMillis()
            val emitKey = "$pkg|$actionType|${resourceId.orEmpty()}"
            synchronized(teachLock) {
                if (emitKey == lastScrollEmitKey && uptime - lastScrollEmitUptimeMs < TEACH_SCROLL_THROTTLE_MS) {
                    return
                }
                lastScrollEmitKey = emitKey
                lastScrollEmitUptimeMs = uptime
            }
        }

        teach.recordStep(
            packageName = pkg,
            actionType = actionType,
            targetLabel = label,
            inputSample = null,
            resourceId = resourceId,
            normalizedBounds = normalizedBounds,
            timestampMs = nowMs,
        )
    }

    private fun resolveScrollAction(event: AccessibilityEvent, pkg: String, resourceId: String?): String? {
        val dy = event.scrollDeltaY
        val key = "$pkg|${resourceId.orEmpty()}"
        val curFrom = event.fromIndex
        val curScrollY = event.scrollY
        val prev = synchronized(teachLock) {
            lastScrollState.put(key, curFrom to curScrollY)
        }
        return when {
            dy > 0 -> "scroll_down"
            dy < 0 -> "scroll_up"
            prev != null && curScrollY >= 0 && prev.second >= 0 && curScrollY != prev.second ->
                if (curScrollY > prev.second) "scroll_down" else "scroll_up"
            prev != null && curFrom >= 0 && prev.first >= 0 && curFrom != prev.first ->
                if (curFrom > prev.first) "scroll_down" else "scroll_up"
            prev == null && (curScrollY > 0 || curFrom > 0) -> "scroll_down"
            else -> null
        }
    }

    private fun scheduleDebouncedTextInput(candidate: PendingTextInput) {
        var toFlushFirst: PendingTextInput? = null
        synchronized(teachLock) {
            val prev = pendingTextInput
            if (prev != null && !isSamePendingTarget(prev, candidate)) {
                toFlushFirst = prev
            }
            pendingTextRunnable?.let { teachHandler.removeCallbacks(it) }
            pendingTextInput = candidate
            val runnable = Runnable { flushPendingTextInput() }
            pendingTextRunnable = runnable
            teachHandler.postDelayed(runnable, TEACH_TEXT_DEBOUNCE_MS)
        }
        toFlushFirst?.let { emitPendingTextInput(it) }
    }

    private fun flushPendingTextInput() {
        val pending = synchronized(teachLock) {
            pendingTextRunnable?.let { teachHandler.removeCallbacks(it) }
            pendingTextRunnable = null
            pendingTextInput.also { pendingTextInput = null }
        } ?: return
        emitPendingTextInput(pending)
    }

    private fun emitPendingTextInput(pending: PendingTextInput) {
        val teach = com.hana.agent.teach.TeachRuntime.instance
        if (teach.state.value != com.hana.agent.teach.TeachState.RECORDING) return
        teach.recordStep(
            packageName = pending.packageName,
            actionType = "type_text",
            targetLabel = pending.targetLabel,
            inputSample = pending.inputSample,
            resourceId = pending.resourceId,
            normalizedBounds = pending.normalizedBounds,
            timestampMs = pending.timestampMs,
        )
    }

    private fun isSamePendingTarget(a: PendingTextInput, b: PendingTextInput): Boolean {
        if (a.packageName != b.packageName) return false
        if (a.resourceId != null && b.resourceId != null) return a.resourceId == b.resourceId
        if (a.normalizedBounds != null && b.normalizedBounds != null) return a.normalizedBounds == b.normalizedBounds
        return a.targetLabel == b.targetLabel
    }

    private fun displayOf(event: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return event.displayId
        val windowId = event.windowId
        if (windowId == -1) return Display.INVALID_DISPLAY
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) {
            if (all.valueAt(i).any { it.id == windowId }) return all.keyAt(i)
        }
        return Display.INVALID_DISPLAY
    }

    companion object {
        private const val EVENT_BUFFER = 64
        /** 两次截图之间系统要求的间隔(安卓原版是一秒)。 */
        private const val SCREENSHOT_INTERVAL_MS = 1_000L
        /** 示范录制文本输入防抖窗口（毫秒）：连续打字合并为最终文本。 */
        private const val TEACH_TEXT_DEBOUNCE_MS = 800L
        /** 示范录制同向滚动节流窗口（毫秒）：单次滑动只记一条方向动作。 */
        private const val TEACH_SCROLL_THROTTLE_MS = 350L

        // 保持热流:调用方用 UNDISPATCHED 先订阅再操作,不要因服务切换引入异步订阅窗口。
        // 事件只作观察用,订阅方处理不过来时丢最旧的,不阻塞系统回调
        private val events = MutableSharedFlow<DisplayEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        /** 窗口变了(加上、拿掉、挪动,包括输入法弹出收起):只是个信号,订阅方自己去读窗口。 */
        private val windowChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private val windowGeneration = java.util.concurrent.atomic.AtomicLong()
        private val contentGeneration = java.util.concurrent.atomic.AtomicLong()

        private const val IGNORED_CONTENT_EVENTS = AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START or AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED

        /** 窗口每加上、拿掉、换页一次就加一(任何屏上的都算);只用来判断「窗口自上次以来变没变」,值本身没有含义。 */
        fun windowGeneration(): Long = windowGeneration.get()

        /** 界面内容相关事件每到达一次就加一;用来零 IPC 判断读屏期间或两次紧邻调用之间是否有界面变化事件。 */
        fun contentGeneration(): Long = contentGeneration.get()
        private val notifications = MutableSharedFlow<ScreenEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        private val _instance = MutableStateFlow<ScreenAccessibility?>(null)
        /** 已连接的服务实例;服务未开启或已断开时为 null。 */
        val instance: StateFlow<ScreenAccessibility?> = _instance.asStateFlow()
    }
}

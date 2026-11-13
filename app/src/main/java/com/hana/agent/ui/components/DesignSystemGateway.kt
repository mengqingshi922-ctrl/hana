package com.hana.agent.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.hana.agent.ui.theme.HanaMotion
import com.hana.agent.ui.theme.HanaTheme
import com.hana.agent.ui.theme.HanaType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 轻提示 HUD 事件模型。 */
data class HudEvent(val id: Long, val message: String, val isLong: Boolean)

/**
 * 设计系统统一收口网关 (DesignSystemGateway):
 * 强制所有业务弹窗与轻提示收口至统一质感的 Hana 设计系统，消除原生黑气泡 Toast 与原生 AlertDialog 的割裂感。
 */
object DesignSystemGateway {

    private val _hudEvents = MutableSharedFlow<HudEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val hudEvents: SharedFlow<HudEvent> = _hudEvents.asSharedFlow()
    private val hostCount = AtomicInteger(0)
    private val idGen = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 统一轻提示网关：
     * 当应用在前台挂载了 [HanaHudHost] 时，发射沉浸式顶端胶囊 HUD；
     * 处于后台服务、悬浮胶囊等无宿主状态时，安全退化为系统原生 Toast。
     */
    fun toast(context: Context, text: String, isLong: Boolean = false) {
        if (hostCount.get() > 0) {
            val event = HudEvent(idGen.incrementAndGet(), text, isLong)
            _hudEvents.tryEmit(event)
        } else {
            val appCtx = context.applicationContext
            val duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(appCtx, text, duration).show()
            } else {
                mainHandler.post { Toast.makeText(appCtx, text, duration).show() }
            }
        }
    }

    fun toast(context: Context, resId: Int, isLong: Boolean = false) {
        val text = runCatching { context.getString(resId) }.getOrNull() ?: return
        toast(context, text, isLong)
    }

    /**
     * App 沉浸式 HUD 胶囊承载宿主：
     * 挂载在主界面最顶层，顶部状态栏下方安全滑入，超时自动淡出，不阻断下方触摸。
     */
    @Composable
    fun HanaHudHost(modifier: Modifier = Modifier) {
        DisposableEffect(Unit) {
            hostCount.incrementAndGet()
            onDispose {
                hostCount.decrementAndGet()
            }
        }

        var currentEvent by remember { mutableStateOf<HudEvent?>(null) }

        LaunchedEffect(Unit) {
            hudEvents.collectLatest { event ->
                currentEvent = event
                delay(if (event.isLong) 3200L else 1800L)
                if (currentEvent?.id == event.id) {
                    currentEvent = null
                }
            }
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 10.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedVisibility(
                visible = currentEvent != null,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = HanaMotion.page(HanaTheme.reduceMotion),
                ) + fadeIn(animationSpec = HanaMotion.page(HanaTheme.reduceMotion)),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = HanaMotion.page(HanaTheme.reduceMotion),
                ) + fadeOut(animationSpec = HanaMotion.page(HanaTheme.reduceMotion)),
            ) {
                currentEvent?.let { ev ->
                    val c = HanaTheme.colors
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = c.surface,
                        shadowElevation = 6.dp,
                        border = BorderStroke(0.5.dp, c.hair),
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .widthIn(max = 420.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(c.accent, CircleShape)
                            )
                            Text(
                                text = ev.message,
                                style = HanaType.Caption.copy(fontSize = 13.sp, lineHeight = 17.sp),
                                fontWeight = FontWeight.Medium,
                                color = c.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 统一弹窗网关组件
     */
    @Composable
    fun Dialog(
        onDismissRequest: () -> Unit,
        modifier: Modifier = Modifier,
        title: (@Composable () -> Unit)? = null,
        text: (@Composable () -> Unit)? = null,
        confirmButton: @Composable () -> Unit = {},
        dismissButton: (@Composable () -> Unit)? = null,
        properties: DialogProperties = DialogProperties(),
    ) {
        HanaDialog(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            properties = properties,
        )
    }

    /**
     * 统一通知确认弹窗
     */
    @Composable
    fun Notice(
        message: String,
        onDismiss: () -> Unit,
        title: String? = null,
        confirmText: String = "好的",
    ) {
        HanaNoticeDialog(
            message = message,
            onDismiss = onDismiss,
            title = title,
            confirmText = confirmText,
        )
    }
}

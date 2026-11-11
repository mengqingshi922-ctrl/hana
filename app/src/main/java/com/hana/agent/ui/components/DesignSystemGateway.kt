package com.hana.agent.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/**
 * 设计系统统一收口网关 (DesignSystemGateway):
 * 强制所有业务弹窗与轻提示收口至统一质感的 Hana 设计系统，消除原生黑气泡 Toast 与原生 AlertDialog 的割裂感。
 */
object DesignSystemGateway {

    /**
     * 统一轻提示网关
     */
    fun toast(context: Context, text: String, isLong: Boolean = false) {
        val duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(context.applicationContext, text, duration).show()
    }

    fun toast(context: Context, resId: Int, isLong: Boolean = false) {
        val duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(context.applicationContext, resId, duration).show()
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

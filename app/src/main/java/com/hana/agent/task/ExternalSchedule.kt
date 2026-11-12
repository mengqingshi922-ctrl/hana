package com.hana.agent.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hana.agent.chat.ConversationStore
import com.hana.agent.chat.CurrentChat
import com.hana.agent.core.agent.Turn
import com.hana.agent.settings.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 外部自动化广播调度中枢（对接 Tasker、Termux、ADB 脚本、快捷指令等）。
 *
 * 监听广播 action: "com.hana.agent.ACTION_CREATE_TASK"
 * 解析 Intent extra 字段：
 *   - task: String (任务文本)
 *   - autoStart: Boolean / String (是否立即执行，默认 true)
 *   - source: String (来源标签，如 "script" / "tasker"，默认 "external")
 *
 * 安全防护：
 *   - 特性开关：设置中默认关闭，需用户显式开启才允许外部广播建任务。
 *   - 频次限流保护：默认每分钟最多接收 1 次，防止外部恶意脚本循环轰炸造成资源耗尽。
 */
object ExternalSchedule {
    const val TAG = "HanaExternalSchedule"
    const val ACTION_CREATE_TASK = "com.hana.agent.ACTION_CREATE_TASK"

    const val EXTRA_TASK = "task"
    const val EXTRA_AUTO_START = "autoStart"
    const val EXTRA_SOURCE = "source"

    /** 检查是否允许外部广播创建任务（默认关闭） */
    fun isEnabled(context: Context): Boolean =
        FeatureFlags.get(context).externalBroadcastEnabled()

    /** 设置是否允许外部广播创建任务 */
    fun setEnabled(context: Context, enabled: Boolean) {
        FeatureFlags.get(context).setExternalBroadcastEnabled(enabled)
    }

    /**
     * 外部广播限流器：默认每分钟最多接收 1 次。
     */
    object RateLimiter {
        @Volatile
        var minIntervalMs: Long = 60_000L

        @Volatile
        private var lastAcceptedMs: Long = 0L

        @Synchronized
        fun tryAcquire(now: Long = System.currentTimeMillis()): Boolean {
            if (now - lastAcceptedMs < minIntervalMs) {
                return false
            }
            lastAcceptedMs = now
            return true
        }

        @Synchronized
        fun reset() {
            lastAcceptedMs = 0L
        }

        @Synchronized
        fun lastAcceptedTime(): Long = lastAcceptedMs
    }

    /**
     * 处理外部任务创建广播。
     * 支持普通广播与 OrderedBroadcast 状态反馈。
     */
    fun handleBroadcast(receiver: BroadcastReceiver, context: Context, intent: Intent) {
        val action = intent.action
        if (action != null && action != ACTION_CREATE_TASK) {
            Log.d(TAG, "Ignoring intent with unmatched action: $action")
            return
        }

        // 1. 特性开关安全检查（默认关闭）
        if (!isEnabled(context)) {
            Log.w(TAG, "External broadcast task rejected: external broadcast feature is disabled in settings")
            if (receiver.isOrderedBroadcast) {
                receiver.resultCode = 403
                receiver.resultData = "External task creation is disabled in settings"
            }
            return
        }

        // 2. 频次限流保护（默认每分钟最多 1 次）
        val now = System.currentTimeMillis()
        if (!RateLimiter.tryAcquire(now)) {
            Log.w(TAG, "External broadcast task rejected: rate limit exceeded (max 1 per minute)")
            if (receiver.isOrderedBroadcast) {
                receiver.resultCode = 429
                receiver.resultData = "Rate limit exceeded: only 1 task per minute is allowed"
            }
            return
        }

        // 3. 解析 task 字段
        val taskText = intent.getStringExtra(EXTRA_TASK)
            ?: intent.getStringExtra("EXTRA_TASK")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)

        if (taskText.isNullOrBlank()) {
            Log.w(TAG, "External broadcast task rejected: task description is empty")
            if (receiver.isOrderedBroadcast) {
                receiver.resultCode = 400
                receiver.resultData = "Missing or blank 'task' extra"
            }
            return
        }

        // 4. 解析 autoStart 字段（支持布尔值与字符串）
        val autoStart = if (intent.hasExtra(EXTRA_AUTO_START)) {
            val stringVal = runCatching { intent.getStringExtra(EXTRA_AUTO_START) }.getOrNull()
            if (stringVal != null) {
                stringVal.equals("true", ignoreCase = true) || stringVal == "1"
            } else {
                intent.getBooleanExtra(EXTRA_AUTO_START, true)
            }
        } else {
            true // 默认立即执行
        }

        // 5. 解析 source 字段
        val source = intent.getStringExtra(EXTRA_SOURCE)?.takeIf { it.isNotBlank() } ?: "external"

        Log.i(TAG, "Accepted external broadcast task: source=$source, autoStart=$autoStart, task=$taskText")

        val pending = runCatching { receiver.goAsync() }.getOrNull()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                executeTask(context.applicationContext, taskText.trim(), autoStart, source)
                if (receiver.isOrderedBroadcast) {
                    receiver.resultCode = 200
                    receiver.resultData = "Task accepted: autoStart=$autoStart"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch external task", e)
                if (receiver.isOrderedBroadcast) {
                    receiver.resultCode = 500
                    receiver.resultData = "Task dispatch failed: ${e.message}"
                }
            } finally {
                pending?.finish()
            }
        }
    }

    /**
     * 派发或创建任务
     */
    fun executeTask(context: Context, taskText: String, autoStart: Boolean, source: String) {
        val store = ConversationStore.shared(context)
        val conversationId = store.newConversation()
        val title = "[$source] $taskText".take(40)
        store.rename(conversationId, title)
        store.setCurrent(CurrentChat.Open(conversationId))

        if (autoStart) {
            try {
                TaskService.start(context, taskText, conversationId = conversationId)
                Log.i(TAG, "Started external task in TaskService: id=$conversationId, goal=$taskText")
            } catch (e: Exception) {
                Log.w(TAG, "Could not start TaskService directly, recording pending task in conversation", e)
                val turn = Turn(
                    userText = taskText,
                    outcome = "外部任务已登记（待启动服务）",
                    status = "pending",
                    note = "来源: $source"
                )
                store.append(conversationId, turn)
            }
        } else {
            val turn = Turn(
                userText = taskText,
                outcome = "外部系统派发待执行任务",
                status = "pending",
                note = "来源: $source"
            )
            store.append(conversationId, turn)
            Log.i(TAG, "Created external task in conversation: id=$conversationId, goal=$taskText")
        }
    }
}

/**
 * 外部自动化调度广播接收器别名。
 */
class ExternalScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ExternalSchedule.handleBroadcast(this, context, intent)
    }
}

package com.hana.agent.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 外部自动化任务广播接收器（Tasker、Termux、ADB 脚本、快捷指令等）。
 *
 * 监听广播 action: "com.hana.agent.ACTION_CREATE_TASK"
 * 解析 extra 字段：task (任务文本), autoStart (是否立即执行), source (来源标签)
 * 内置特性开关校验（默认关闭）与频次限流保护（默认每分钟最多接收 1 次）。
 */
class HanaTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ExternalSchedule.handleBroadcast(this, context, intent)
    }
}

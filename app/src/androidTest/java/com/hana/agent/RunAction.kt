package com.hana.agent

import com.hana.agent.core.action.ActionExecutor
import com.hana.agent.core.action.ActionRecorder
import com.hana.agent.core.action.Origin
import com.hana.agent.core.action.execution
import com.hana.agent.core.guard.OperationGuards
import com.hana.agent.core.model.Part
import com.hana.agent.core.record.RunSink
import com.hana.agent.core.tool.ToolDispatcher
import com.hana.agent.core.tool.ToolExecution

/** 设备测试里直接做一个工具调用:和产品一样经执行入口(不配确认关卡,不等锁屏)。 */
suspend fun ToolDispatcher.runAction(call: Part.ToolCall): ToolExecution =
    ActionExecutor(this, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE)).run(call, Origin.Model(0)).execution()

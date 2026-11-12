package com.hana.agent.core.memory

import kotlinx.serialization.Serializable

/**
 * 历史任务中的关键操作步骤。
 * @property step 步骤序号（从 1 开始）
 * @property tool 调用的工具名（如 phone_tap, phone_type_text, web_navigate, phone_launch_app 等）
 * @property action 动作简述或核心参数（如 "点击 [搜索框]", "输入 [运动鞋]", "打开淘宝"）
 * @property note 步骤说明或用意
 */
@Serializable
data class TaskMemoryStep(
    val step: Int,
    val tool: String,
    val action: String,
    val note: String? = null,
)

/**
 * 跨会话长期任务记忆模型。
 * 沉淀历史成功任务的经验、触达的 App、域名及关键操作路径，供后续任务检索复用。
 *
 * @property id 任务记忆唯一标识
 * @property goal 任务目标/用户意图原话
 * @property conclusion 任务最终结论或执行结果答复
 * @property apps 涉及的应用程序（包名或 App 名列表，如 listOf("com.taobao.taobao")）
 * @property domains 涉及的网站域名列表（如 listOf("taobao.com")）
 * @property keySteps 关键操作步骤序列（成功路径）
 * @property timestamp 任务完成时间戳（毫秒）
 * @property conversationId 会话/对话标识（可选）
 * @property success 是否执行成功
 */
@Serializable
data class TaskMemory(
    val id: String,
    val goal: String,
    val conclusion: String,
    val apps: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val keySteps: List<TaskMemoryStep> = emptyList(),
    val timestamp: Long,
    val conversationId: String? = null,
    val success: Boolean = true,
)

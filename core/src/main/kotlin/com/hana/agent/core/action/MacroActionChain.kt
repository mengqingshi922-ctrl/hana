package com.hana.agent.core.action

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 宏动作原子前置断言枚举
 */
@Serializable
enum class ChainPredicateKind {
    NONE,
    FOCUSED,          // 目标控件必须处于获取焦点状态
    TEXT_UPDATED,     // 目标文本已刷新/已填充
    PAGE_NAVIGATED,   // 界面发生跳转或前台窗口变化
    ELEMENT_APPEARED, // 预期元素已在可视视口内呈现
    ELEMENT_GONE,     // 遮挡/进度条/加载浮层已消失
}

/**
 * 宏动作步骤
 */
@Serializable
data class MacroStep(
    val tool: String,                          // 调用的基础动作工具，如 phone_tap, phone_type, phone_press_key
    val args: JsonObject,                      // 动作所需参数
    val predicate: ChainPredicateKind = ChainPredicateKind.NONE, // 原子前置断言
    val expectText: String? = null,            // 断言关联的目标文本或关键字
    val timeoutMs: Long = 300L,                // 步骤最大等待时限
)

/**
 * 宏动作链
 */
@Serializable
data class MacroActionChain(
    val steps: List<MacroStep>,                // 连续动作列表（限制 1~20 步）
    val fallbackStrategy: String = "stop_and_query", // 断言失败策略：stop_and_query, backtrack
    val comment: String? = null,
)

/**
 * 宏动作链执行结果
 */
@Serializable
data class MacroChainResult(
    val completedSteps: Int,
    val totalSteps: Int,
    val success: Boolean,
    val failedStepIndex: Int? = null,
    val failureReason: String? = null,
    val shortCircuited: Boolean = false,
)

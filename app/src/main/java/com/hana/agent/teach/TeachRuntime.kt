package com.hana.agent.teach

import com.hana.agent.core.skills.TeachActionStep
import com.hana.agent.core.skills.TeachDraftResult
import com.hana.agent.core.skills.TeachRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 示范录制状态机。
 */
enum class TeachState {
    /** 空闲未录制 */
    IDLE,

    /** 正在录制示范动作流 */
    RECORDING,

    /** 录制完成，正在预览与评审草稿 */
    REVIEWING
}

/**
 * 「教 Hana」示范录制应用层运行时。
 *
 * 负责协调用户操作示范的全流程状态机：
 * 1. 维护 [TeachState.IDLE] -> [TeachState.RECORDING] -> [TeachState.REVIEWING] 的状态流转；
 * 2. 接收来自无障碍服务（A11y）或悬浮窗交互派发的事件流，转交 [TeachRecorder]；
 * 3. 产出强脱敏的 Markdown 技能草稿，供用户核对和一键沉淀到个人技能库。
 */
class TeachRuntime private constructor() {

    private val lock = Any()
    private val recorder = TeachRecorder()

    private val _state = MutableStateFlow(TeachState.IDLE)
    val state: StateFlow<TeachState> = _state.asStateFlow()

    private val _currentStepCount = MutableStateFlow(0)
    val currentStepCount: StateFlow<Int> = _currentStepCount.asStateFlow()

    private var currentDraft: TeachDraftResult? = null
    private var currentSuggestedName: String = "custom-teach-skill"
    @Volatile private var flushHook: (() -> Unit)? = null

    /**
     * 注册示范录制停止前的防抖缓冲刷入回调（由无障碍采集服务使用）。
     */
    fun registerFlushHook(hook: () -> Unit) {
        flushHook = hook
    }

    /**
     * 注销防抖缓冲刷入回调。
     */
    fun unregisterFlushHook(hook: () -> Unit) {
        if (flushHook === hook) {
            flushHook = null
        }
    }

    /**
     * 开始进入示范录制。
     *
     * @param suggestedName 预设的技能标识名
     * @return true 表示成功启动录制；false 表示当前状态不处于 IDLE
     */
    fun startRecording(suggestedName: String = "custom-teach-skill"): Boolean = synchronized(lock) {
        if (_state.value != TeachState.IDLE) {
            return false
        }
        recorder.clear()
        currentDraft = null
        currentSuggestedName = suggestedName.ifBlank { "custom-teach-skill" }
        _currentStepCount.value = 0
        _state.value = TeachState.RECORDING
        return true
    }

    /**
     * 录制一步示范动作。
     * 仅在 [TeachState.RECORDING] 状态下记录；透传控件稳定锚点与归一化坐标。
     */
    fun recordStep(
        packageName: String,
        actionType: String,
        targetLabel: String,
        inputSample: String? = null,
        resourceId: String? = null,
        normalizedBounds: List<Int>? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (_state.value != TeachState.RECORDING) {
            return false
        }
        recorder.recordStep(
            packageName = packageName,
            actionType = actionType,
            targetLabel = targetLabel,
            inputSample = inputSample,
            timestampMs = timestampMs,
            resourceId = resourceId,
            normalizedBounds = normalizedBounds,
        )
        _currentStepCount.value = recorder.currentSteps().size
        return true
    }

    /**
     * 获取当前已录制的示范步骤快照。
     */
    fun currentSteps(): List<TeachActionStep> = synchronized(lock) {
        return recorder.currentSteps()
    }

    /**
     * 停止录制并生成脱敏草稿，进入 [TeachState.REVIEWING] 状态。
     *
     * @param displayName 技能的中文显示名称
     * @return 构建出的草稿结果；若未在录制状态则返回 null
     */
    fun stopRecording(displayName: String = "示范录制技能"): TeachDraftResult? {
        // 先将无障碍防抖缓冲中的最后一次文本输入刷入录制器，再构建草稿（在 lock 外触发防死锁）
        if (_state.value == TeachState.RECORDING) {
            flushHook?.invoke()
        }
        return synchronized(lock) {
            if (_state.value != TeachState.RECORDING) {
                return null
            }
            val draft = recorder.finishAndBuildDraft(
                suggestedName = currentSuggestedName,
                display = displayName
            )
            currentDraft = draft
            _state.value = TeachState.REVIEWING
            draft
        }
    }

    /**
     * 获取当前正在评审中的草稿结果。
     */
    fun getReviewingDraft(): TeachDraftResult? = synchronized(lock) {
        return currentDraft
    }

    /**
     * 确认并完成本次技能草稿收下，重置回 [TeachState.IDLE]。
     */
    fun completeReview(): TeachDraftResult? = synchronized(lock) {
        val draft = currentDraft
        recorder.clear()
        currentDraft = null
        _currentStepCount.value = 0
        _state.value = TeachState.IDLE
        return draft
    }

    /**
     * 放弃本次示范录制或草稿，重置回 [TeachState.IDLE]。
     */
    fun discard(): Unit = synchronized(lock) {
        recorder.clear()
        currentDraft = null
        _currentStepCount.value = 0
        _state.value = TeachState.IDLE
    }

    companion object {
        val instance: TeachRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            TeachRuntime()
        }
    }
}

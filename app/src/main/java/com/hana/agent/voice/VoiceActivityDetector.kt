package com.hana.agent.voice

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 端侧轻量级能量 VAD 配置。
 * 适配 16kHz / 50ms 音频帧（每秒 20 帧）的实时双工打断与自动断句。
 */
data class VadConfig(
    /** 触发说话开始的最小归一化能量门限 `[0f, 1f]` */
    val startEnergyThreshold: Float = 0.065f,
    /** 维持说话状态的迟滞下门限（施密特触发防抖） */
    val continueEnergyThreshold: Float = 0.035f,
    /** 动态底噪之上需要抬升的倍数系数 */
    val noiseMultiplier: Float = 2.4f,
    /** 判定说话开始所需的连续高能量帧数 */
    val startTriggerFrames: Int = 2,
    /** 播报/任务执行中触发 Barge-in 打断所需的连续强能量帧数与更高门限 */
    val bargeInEnergyThreshold: Float = 0.11f,
    val bargeInFrames: Int = 2,
    /** 说话结束后连续静音超过该时长（毫秒）则发出 SpeechEnd */
    val silenceTimeoutMs: Long = 850L,
    /** 最短有效语段时长（毫秒），避免极短瞬态杂音误判 */
    val minSpeechDurationMs: Long = 150L,
    /** 每帧默认时长（毫秒） */
    val frameDurationMs: Long = 50L,
)

enum class VadPhase {
    SILENCE,
    SPEAKING,
}

data class VadSnapshot(
    val phase: VadPhase = VadPhase.SILENCE,
    val noiseFloor: Float = 0.015f,
    val lastEnergy: Float = 0f,
    val speechDurationMs: Long = 0L,
    val silenceDurationMs: Long = 0L,
)

sealed interface VadEvent {
    /** 检测到用户开始说话 */
    data class SpeechStart(val timestampMs: Long, val energy: Float) : VadEvent

    /** 说话持续中，携带当前归一化振幅用于实时动效 */
    data class SpeechActive(val timestampMs: Long, val energy: Float) : VadEvent

    /** 执行中或 TTS 播报中检测到用户强势插话打断，需要立刻停播并 Steer 重定向 */
    data class BargeIn(val timestampMs: Long, val energy: Float) : VadEvent

    /** 静音超时判定用户本轮话语结束 */
    data class SpeechEnd(val timestampMs: Long, val speechDurationMs: Long, val timedOutSilenceMs: Long) : VadEvent
}

/**
 * 端侧轻量级能量 VAD（Voice Activity Detector）：
 * - 自适应跟踪环境底噪（EMA 平滑），在安静与微噪环境下稳定识别起止点
 * - 双阈值迟滞 + 连续帧去抖 + 静音超时自动收尾
 * - 支持在任务执行或语音播报中随时检测 Barge-in 打断并触发 Steer 重定向回调
 */
class VoiceActivityDetector(
    private val config: VadConfig = VadConfig(),
    private val onEvent: ((VadEvent) -> Unit)? = null,
) {
    private var phase: VadPhase = VadPhase.SILENCE
    private var noiseFloor: Float = 0.015f
    private var consecutiveSpeechFrames: Int = 0
    private var consecutiveBargeInFrames: Int = 0
    private var speechStartMs: Long = 0L
    private var lastSpeechActiveMs: Long = 0L
    private var lastTimestampMs: Long = 0L
    private var bargeInFiredThisUtterance: Boolean = false
    private var lastEnergy: Float = 0f

    fun currentSnapshot(): VadSnapshot = VadSnapshot(
        phase = phase,
        noiseFloor = noiseFloor,
        lastEnergy = lastEnergy,
        speechDurationMs = if (phase == VadPhase.SPEAKING) max(0L, lastTimestampMs - speechStartMs) else 0L,
        silenceDurationMs = if (phase == VadPhase.SPEAKING) max(0L, lastTimestampMs - lastSpeechActiveMs) else 0L,
    )

    fun reset() {
        phase = VadPhase.SILENCE
        consecutiveSpeechFrames = 0
        consecutiveBargeInFrames = 0
        speechStartMs = 0L
        lastSpeechActiveMs = 0L
        lastTimestampMs = 0L
        bargeInFiredThisUtterance = false
        lastEnergy = 0f
    }

    /**
     * 处理一帧 16-bit PCM 样本并返回本帧产生的 VAD 事件列表。
     */
    fun processPcmFrame(
        samples: ShortArray,
        sampleCount: Int = samples.size,
        timestampMs: Long = System.currentTimeMillis(),
        isAssistantBusy: Boolean = false,
    ): List<VadEvent> {
        val energy = computeNormalizedRms(samples, sampleCount)
        return processNormalizedEnergy(energy, timestampMs, isAssistantBusy)
    }

    /**
     * 处理已归一化的帧能量 `[0f, 1f]`，返回产生的所有 [VadEvent]。
     * @param isAssistantBusy 当前是否处于播报中或 Agent 任务执行中（用于触发 Barge-in 打断）
     */
    fun processNormalizedEnergy(
        normalizedEnergy: Float,
        timestampMs: Long = System.currentTimeMillis(),
        isAssistantBusy: Boolean = false,
    ): List<VadEvent> {
        val energy = normalizedEnergy.coerceIn(0f, 1f)
        lastEnergy = energy
        lastTimestampMs = timestampMs
        val events = mutableListOf<VadEvent>()

        val startGate = max(config.startEnergyThreshold, noiseFloor * config.noiseMultiplier)
        val continueGate = max(config.continueEnergyThreshold, noiseFloor * (config.noiseMultiplier * 0.72f))

        if (phase == VadPhase.SILENCE) {
            // 仅在静音态自适应更新环境底噪，避免把人声计入底噪
            if (energy < startGate) {
                noiseFloor = (noiseFloor * 0.92f + energy * 0.08f).coerceIn(0.004f, 0.08f)
            }

            if (energy >= startGate) {
                consecutiveSpeechFrames++
            } else {
                consecutiveSpeechFrames = 0
            }

            if (isAssistantBusy && energy >= max(config.bargeInEnergyThreshold, startGate)) {
                consecutiveBargeInFrames++
            } else {
                consecutiveBargeInFrames = 0
            }

            if (consecutiveSpeechFrames >= config.startTriggerFrames) {
                phase = VadPhase.SPEAKING
                speechStartMs = timestampMs - (consecutiveSpeechFrames - 1) * config.frameDurationMs
                lastSpeechActiveMs = timestampMs
                bargeInFiredThisUtterance = false

                val startEvent = VadEvent.SpeechStart(timestampMs, energy)
                events += startEvent
                onEvent?.invoke(startEvent)

                if (isAssistantBusy && consecutiveBargeInFrames >= config.bargeInFrames) {
                    bargeInFiredThisUtterance = true
                    val bargeEvent = VadEvent.BargeIn(timestampMs, energy)
                    events += bargeEvent
                    onEvent?.invoke(bargeEvent)
                }
            }
            return events
        }

        // phase == SPEAKING
        if (energy >= continueGate) {
            lastSpeechActiveMs = timestampMs
            val activeEvent = VadEvent.SpeechActive(timestampMs, energy)
            events += activeEvent
            onEvent?.invoke(activeEvent)

            if (isAssistantBusy && !bargeInFiredThisUtterance &&
                energy >= max(config.bargeInEnergyThreshold, startGate)
            ) {
                consecutiveBargeInFrames++
                if (consecutiveBargeInFrames >= config.bargeInFrames) {
                    bargeInFiredThisUtterance = true
                    val bargeEvent = VadEvent.BargeIn(timestampMs, energy)
                    events += bargeEvent
                    onEvent?.invoke(bargeEvent)
                }
            } else {
                consecutiveBargeInFrames = 0
            }
        } else {
            consecutiveBargeInFrames = 0
            val silenceMs = max(0L, timestampMs - lastSpeechActiveMs)
            if (silenceMs >= config.silenceTimeoutMs) {
                val totalSpeechMs = max(0L, lastSpeechActiveMs - speechStartMs)
                phase = VadPhase.SILENCE
                consecutiveSpeechFrames = 0
                bargeInFiredThisUtterance = false
                if (totalSpeechMs >= config.minSpeechDurationMs) {
                    val endEvent = VadEvent.SpeechEnd(
                        timestampMs = timestampMs,
                        speechDurationMs = totalSpeechMs,
                        timedOutSilenceMs = silenceMs,
                    )
                    events += endEvent
                    onEvent?.invoke(endEvent)
                }
            }
        }

        return events
    }

    companion object {
        /**
         * 与 [PcmRecorder] 一致的归一化 RMS 计算：`(sqrt(sum / n) / 8000.0).coerceIn(0.0, 1.0)`。
         */
        fun computeNormalizedRms(samples: ShortArray, sampleCount: Int = samples.size): Float {
            val count = sampleCount.coerceIn(0, samples.size)
            if (count == 0) return 0f
            var sum = 0.0
            for (i in 0 until count) {
                val s = samples[i].toDouble()
                sum += s * s
            }
            return (sqrt(sum / count) / 8000.0).coerceIn(0.0, 1.0).toFloat()
        }
    }
}

/**
 * 全双工语音打断桥接器：
 * 将 [VoiceActivityDetector] 与播报停止 ([onStopPlayback])、任务打断提示 ([onBargeInDetected])
 * 以及语音识别结果直连 Steer 重定向 ([onSteerRedirect]) 串联起来，供 UI / Service 绑定。
 */
class FullDuplexVoiceBridge(
    config: VadConfig = VadConfig(),
    private val isAssistantBusy: () -> Boolean,
    private val onStopPlayback: () -> Unit,
    private val onBargeInDetected: () -> Unit = {},
    private val onSpeechEndDetected: (speechDurationMs: Long) -> Unit = {},
    private val onSteerRedirect: (transcript: String) -> Unit,
) {
    private val vad = VoiceActivityDetector(config)

    @Volatile
    private var pendingBargeInSteer: Boolean = false

    fun onPcmFrame(samples: ShortArray, sampleCount: Int = samples.size, timestampMs: Long = System.currentTimeMillis()): List<VadEvent> {
        val events = vad.processPcmFrame(samples, sampleCount, timestampMs, isAssistantBusy())
        dispatchEvents(events)
        return events
    }

    fun onEnergySample(normalizedEnergy: Float, timestampMs: Long = System.currentTimeMillis()): List<VadEvent> {
        val events = vad.processNormalizedEnergy(normalizedEnergy, timestampMs, isAssistantBusy())
        dispatchEvents(events)
        return events
    }

    private fun dispatchEvents(events: List<VadEvent>) {
        for (event in events) {
            when (event) {
                is VadEvent.BargeIn -> {
                    pendingBargeInSteer = true
                    onStopPlayback()
                    onBargeInDetected()
                }
                is VadEvent.SpeechEnd -> {
                    onSpeechEndDetected(event.speechDurationMs)
                }
                else -> Unit
            }
        }
    }

    /**
     * 当打断后的语音转写完成时调用：触发 Steer 重定向回调，把新指令注入当前运行中的任务。
     */
    fun submitTranscript(transcript: String) {
        val clean = transcript.trim()
        pendingBargeInSteer = false
        if (clean.isNotEmpty()) {
            onSteerRedirect(clean)
        }
    }

    fun hasPendingBargeIn(): Boolean = pendingBargeInSteer

    fun snapshot(): VadSnapshot = vad.currentSnapshot()

    fun reset() {
        pendingBargeInSteer = false
        vad.reset()
    }
}

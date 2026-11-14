package com.hana.agent.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 端侧低延迟能量 + 过零率（ZCR）双特征语音活动检测配置。
 * 结合归一化 RMS 能量与人声典型过零率区间 `[minZcr, maxZcr]`，显著降低键盘敲击/环境低频轰鸣误触发。
 */
data class EnergyZcrVadConfig(
    val startEnergyThreshold: Float = 0.055f,
    val continueEnergyThreshold: Float = 0.030f,
    val bargeInEnergyThreshold: Float = 0.095f,
    val noiseMultiplier: Float = 2.2f,
    val minSpeechZcr: Float = 0.015f,
    val maxSpeechZcr: Float = 0.55f,
    val startTriggerFrames: Int = 2,
    val bargeInFrames: Int = 2,
    val silenceTimeoutMs: Long = 750L,
    val frameDurationMs: Long = 50L,
)

/**
 * 单帧端侧 VAD 判决输出。
 */
data class VadDecision(
    val rms: Float,
    val zcr: Float,
    val isSpeech: Boolean,
    val speechStarted: Boolean,
    val bargeInTriggered: Boolean,
    val speechEnded: Boolean,
)

/**
 * 轻量低延迟能量 / 过零率端侧 VAD 检测器（纯 Kotlin 实现，支持无设备环境单测）。
 */
class EnergyZcrVad(
    private val config: EnergyZcrVadConfig = EnergyZcrVadConfig(),
) {
    private var speaking = false
    private var noiseFloor = 0.014f
    private var speechFrames = 0
    private var bargeInFrames = 0
    private var lastSpeechMs = 0L
    private var bargeInFired = false

    fun reset() {
        speaking = false
        noiseFloor = 0.014f
        speechFrames = 0
        bargeInFrames = 0
        lastSpeechMs = 0L
        bargeInFired = false
    }

    /**
     * 处理一帧 16-bit PCM 样本并返回端侧语音活动与打断判决。
     */
    fun processFrame(
        samples: ShortArray,
        sampleCount: Int = samples.size,
        isAssistantSpeaking: Boolean = false,
        timestampMs: Long = System.currentTimeMillis(),
    ): VadDecision {
        val count = sampleCount.coerceIn(0, samples.size)
        if (count == 0) {
            return VadDecision(0f, 0f, isSpeech = false, speechStarted = false, bargeInTriggered = false, speechEnded = false)
        }

        val rms = computeNormalizedRms(samples, count)
        val zcr = computeZeroCrossingRate(samples, count)
        val zcrInSpeechBand = zcr in config.minSpeechZcr..config.maxSpeechZcr

        val startGate = max(config.startEnergyThreshold, noiseFloor * config.noiseMultiplier)
        val continueGate = max(config.continueEnergyThreshold, noiseFloor * (config.noiseMultiplier * 0.72f))
        val bargeInGate = max(config.bargeInEnergyThreshold, startGate)

        var speechStarted = false
        var bargeInTriggered = false
        var speechEnded = false

        if (!speaking) {
            if (rms < startGate) {
                noiseFloor = (noiseFloor * 0.92f + rms * 0.08f).coerceIn(0.004f, 0.075f)
            }
            if (rms >= startGate && zcrInSpeechBand) {
                speechFrames++
            } else {
                speechFrames = 0
            }

            if (isAssistantSpeaking && rms >= bargeInGate && zcrInSpeechBand) {
                bargeInFrames++
            } else {
                bargeInFrames = 0
            }

            if (speechFrames >= config.startTriggerFrames) {
                speaking = true
                lastSpeechMs = timestampMs
                bargeInFired = false
                speechStarted = true
                if (isAssistantSpeaking && bargeInFrames >= config.bargeInFrames) {
                    bargeInFired = true
                    bargeInTriggered = true
                }
            }
        } else {
            if (rms >= continueGate) {
                lastSpeechMs = timestampMs
                if (isAssistantSpeaking && !bargeInFired && rms >= bargeInGate && zcrInSpeechBand) {
                    bargeInFrames++
                    if (bargeInFrames >= config.bargeInFrames) {
                        bargeInFired = true
                        bargeInTriggered = true
                    }
                } else if (!isAssistantSpeaking) {
                    bargeInFrames = 0
                }
            } else {
                bargeInFrames = 0
                if (timestampMs - lastSpeechMs >= config.silenceTimeoutMs) {
                    speaking = false
                    speechFrames = 0
                    bargeInFired = false
                    speechEnded = true
                }
            }
        }

        return VadDecision(
            rms = rms,
            zcr = zcr,
            isSpeech = speaking,
            speechStarted = speechStarted,
            bargeInTriggered = bargeInTriggered,
            speechEnded = speechEnded,
        )
    }

    companion object {
        fun computeNormalizedRms(samples: ShortArray, sampleCount: Int = samples.size): Float {
            val count = sampleCount.coerceIn(0, samples.size)
            if (count == 0) return 0f
            var sum = 0.0
            for (i in 0 until count) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            return (sqrt(sum / count) / 8000.0).coerceIn(0.0, 1.0).toFloat()
        }

        fun computeZeroCrossingRate(samples: ShortArray, sampleCount: Int = samples.size): Float {
            val count = sampleCount.coerceIn(0, samples.size)
            if (count <= 1) return 0f
            var crossings = 0
            var prevSign = samples[0] >= 0
            for (i in 1 until count) {
                val currSign = samples[i] >= 0
                if (currSign != prevSign) {
                    crossings++
                    prevSign = currSign
                }
            }
            return crossings.toFloat() / (count - 1).toFloat()
        }
    }
}

/**
 * 全双工实时语音会话 UI 状态。
 */
sealed interface FullDuplexState {
    data object Idle : FullDuplexState
    data object Connecting : FullDuplexState
    data object Listening : FullDuplexState
    data object AssistantSpeaking : FullDuplexState
    data object Interrupted : FullDuplexState
    data class Unavailable(val reason: String) : FullDuplexState
}

/**
 * 全双工语音伴随桥接器（[RealtimeFullDuplexBridge]）：
 * - 基于 `AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION)` + 硬件 `AcousticEchoCanceler` 回声消除采集上行音频；
 * - 集成 [EnergyZcrVad] 端侧能量/过零率双特征 VAD；
 * - 连接 [RealtimeVoiceClient] 双向 WebSocket 音频流与下行 `AudioTrack` 播放；
 * - 检测到用户开口讲话（Barge-in）时立即停播并调用 `client.cancelResponse()`，收到用户转写后通过 [onUserTranscript] 注入对话；
 * - 注：`VoiceActivityDetector.kt` 中已存在同名轻量状态桥接类 `FullDuplexVoiceBridge`，本类命名为 `RealtimeFullDuplexBridge` 以避免同包重声明冲突并保持向后兼容。
 */
class RealtimeFullDuplexBridge(
    private val client: RealtimeVoiceClient,
    private val isAssistantBusy: () -> Boolean = { false },
    private val onUserTranscript: (String) -> Unit = {},
    private val onAssistantTranscript: (String, Boolean) -> Unit = { _, _ -> },
    private val onAmplitudeChanged: (Float) -> Unit = {},
    private val onBargeIn: () -> Unit = {},
    private val onStopLocalPlayback: () -> Unit = {},
    private val vad: EnergyZcrVad = EnergyZcrVad(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<FullDuplexState>(FullDuplexState.Idle)
    val state: StateFlow<FullDuplexState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private val audioLock = Any()

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null
    private var downlinkJob: Job? = null
    private var clientStateJob: Job? = null

    /**
     * 启动全双工实时语音会话（需要 `RECORD_AUDIO` 权限与合法 WS 配置）。
     */
    @SuppressLint("MissingPermission")
    fun start(context: Context): Boolean {
        val appCtx = context.applicationContext
        if (appCtx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = FullDuplexState.Unavailable("需要麦克风录音权限")
            return false
        }
        if (client.config.wsUrl.isBlank()) {
            _state.value = FullDuplexState.Unavailable("未配置实时语音服务地址")
            return false
        }
        if (!running.compareAndSet(false, true)) {
            return true
        }

        vad.reset()
        _state.value = FullDuplexState.Connecting

        val sampleRate = client.config.sampleRate.coerceAtLeast(8000)
        val frameSamples = (sampleRate / 20).coerceAtLeast(160) // 50ms 一帧
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            running.set(false)
            _state.value = FullDuplexState.Unavailable("麦克风硬件初始化失败")
            return false
        }

        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameSamples * 2 * 4),
            )
        }.getOrNull()

        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            running.set(false)
            _state.value = FullDuplexState.Unavailable("无法初始化通信录音通道")
            return false
        }

        // 绑定硬件回声消除（AEC），避免扬声器播报自激误触发 Barge-in
        val attachedAec = runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
            } else {
                null
            }
        }.getOrNull()

        val started = runCatching {
            rec.startRecording()
            rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)

        if (!started) {
            runCatching { attachedAec?.release() }
            runCatching { rec.release() }
            running.set(false)
            _state.value = FullDuplexState.Unavailable("麦克风被其他应用占用")
            return false
        }

        synchronized(audioLock) {
            audioRecord = rec
            aec = attachedAec
        }

        startDownlinkCollectors()
        client.connect()

        captureJob = scope.launch {
            val buf = ShortArray(frameSamples)
            while (isActive && running.get()) {
                val n = runCatching { rec.read(buf, 0, frameSamples) }.getOrDefault(-1)
                if (n <= 0) break
                processCapturedPcmFrame(buf, n, System.currentTimeMillis())
            }
        }
        return true
    }

    /**
     * 处理单帧采集到的 16-bit PCM 音频：执行端侧 VAD、触发 Barge-in 打断并上行发送至 [RealtimeVoiceClient]。
     * 暴露为公开方法以便单测直接喂入合成 PCM 帧验证全双工打断闭环。
     */
    fun processCapturedPcmFrame(
        samples: ShortArray,
        sampleCount: Int = samples.size,
        timestampMs: Long = System.currentTimeMillis(),
    ): VadDecision {
        val assistantActive =
            client.state.value is RealtimeSessionState.Speaking ||
                _state.value == FullDuplexState.AssistantSpeaking ||
                isAssistantBusy()
        val decision = vad.processFrame(
            samples = samples,
            sampleCount = sampleCount,
            isAssistantSpeaking = assistantActive,
            timestampMs = timestampMs,
        )
        onAmplitudeChanged(decision.rms)

        if (decision.bargeInTriggered) {
            triggerBargeIn()
        }

        val count = sampleCount.coerceIn(0, samples.size)
        if (count > 0) {
            val pcmBytes = shortsToLittleEndianBytes(samples, count)
            client.sendAudioPcm16(pcmBytes)
        }
        return decision
    }

    /**
     * 立即执行 Barge-in 强插打断：停止本地播放 + 发送 `response.cancel` 取消服务端生成。
     */
    fun triggerBargeIn() {
        stopLocalAudioTrack()
        onStopLocalPlayback()
        client.cancelResponse()
        _state.value = FullDuplexState.Interrupted
        onBargeIn()
        if (running.get()) {
            _state.value = FullDuplexState.Listening
        }
    }

    /**
     * 启动下行事件监听（在无硬件 `AudioRecord` 的单测环境中也可单独调用以验证下行联动）。
     */
    fun startDownlinkCollectors() {
        downlinkJob?.cancel()
        downlinkJob = scope.launch {
            client.events.collect { event ->
                when (event) {
                    is RealtimeDownlinkEvent.AudioChunk -> {
                        _state.value = FullDuplexState.AssistantSpeaking
                        playDownlinkChunk(event)
                    }
                    is RealtimeDownlinkEvent.UserTranscript -> {
                        if (event.isFinal && event.text.isNotBlank()) {
                            onUserTranscript(event.text)
                        }
                    }
                    is RealtimeDownlinkEvent.TranscriptDelta -> {
                        onAssistantTranscript(event.text, event.isFinal)
                    }
                    RealtimeDownlinkEvent.Interrupted -> {
                        stopLocalAudioTrack()
                        onStopLocalPlayback()
                        onAmplitudeChanged(0f)
                        if (running.get()) _state.value = FullDuplexState.Listening
                    }
                    RealtimeDownlinkEvent.ResponseDone -> {
                        onAmplitudeChanged(0f)
                        if (running.get()) _state.value = FullDuplexState.Listening
                    }
                    is RealtimeDownlinkEvent.ProtocolError -> {
                        stopLocalAudioTrack()
                        onAmplitudeChanged(0f)
                        _state.value = FullDuplexState.Unavailable(event.message)
                    }
                }
            }
        }

        clientStateJob?.cancel()
        clientStateJob = scope.launch {
            client.state.collect { sessionState ->
                when (sessionState) {
                    RealtimeSessionState.Connecting -> if (running.get()) _state.value = FullDuplexState.Connecting
                    RealtimeSessionState.Connected -> if (running.get() && _state.value !is FullDuplexState.AssistantSpeaking) {
                        _state.value = FullDuplexState.Listening
                    }
                    RealtimeSessionState.Speaking -> if (running.get()) _state.value = FullDuplexState.AssistantSpeaking
                    RealtimeSessionState.Disconnected -> if (!running.get()) _state.value = FullDuplexState.Idle
                    is RealtimeSessionState.Error -> _state.value = FullDuplexState.Unavailable(sessionState.message)
                }
            }
        }
    }

    private fun playDownlinkChunk(chunk: RealtimeDownlinkEvent.AudioChunk) {
        val pcmBytes = runCatching { Base64.getDecoder().decode(chunk.pcmBase64) }.getOrNull() ?: return
        if (pcmBytes.isEmpty()) return
        onAmplitudeChanged(VoiceSpeaker.computePcm16Amplitude(pcmBytes))

        runCatching {
            val track = ensureAudioTrack(chunk.sampleRate)
            track?.write(pcmBytes, 0, pcmBytes.size)
        }
    }

    private fun ensureAudioTrack(sampleRate: Int): AudioTrack? = synchronized(audioLock) {
        val existing = audioTrack
        if (existing != null && existing.sampleRate == sampleRate && existing.state == AudioTrack.STATE_INITIALIZED) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { existing.play() }
            }
            return existing
        }
        runCatching {
            existing?.stop()
            existing?.release()
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (created.state == AudioTrack.STATE_INITIALIZED) {
            created.play()
            audioTrack = created
            created
        } else {
            created.release()
            null
        }
    }

    private fun stopLocalAudioTrack() {
        synchronized(audioLock) {
            runCatching {
                audioTrack?.pause()
                audioTrack?.flush()
            }
        }
    }

    /**
     * 停止全双工录音与 WebSocket 会话并释放音频硬件资源。
     */
    fun stop() {
        running.set(false)
        captureJob?.cancel()
        captureJob = null
        downlinkJob?.cancel()
        downlinkJob = null
        clientStateJob?.cancel()
        clientStateJob = null

        synchronized(audioLock) {
            runCatching { aec?.release() }
            aec = null
            runCatching {
                audioRecord?.stop()
                audioRecord?.release()
            }
            audioRecord = null
            runCatching {
                audioTrack?.stop()
                audioTrack?.release()
            }
            audioTrack = null
        }
        client.disconnect()
        onAmplitudeChanged(0f)
        _state.value = FullDuplexState.Idle
    }

    fun release() {
        stop()
        client.close()
        scope.cancel()
    }

    companion object {
        internal fun shortsToLittleEndianBytes(samples: ShortArray, count: Int = samples.size): ByteArray {
            val n = count.coerceIn(0, samples.size)
            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                val s = samples[i].toInt()
                out[i * 2] = (s and 0xFF).toByte()
                out[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}

/**
 * 实时全双工语音配置解析器：
 * 从本地偏好中安全读取已配置的 WebSocket 地址；未配置时返回 `null`，供界面优雅降级至按住说话。
 */
object RealtimeVoiceConfigResolver {
    private const val PREFS_NAME = "realtime_voice_prefs"
    private const val KEY_WS_URL = "ws_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_VOICE = "voice"

    fun resolve(context: Context): RealtimeConfig? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wsUrl = prefs.getString(KEY_WS_URL, null)?.trim().orEmpty()
        if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
            return null
        }
        val apiKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim()
        val model = prefs.getString(KEY_MODEL, "gpt-4o-realtime-preview").orEmpty().ifBlank { "gpt-4o-realtime-preview" }
        val protocolName = prefs.getString(KEY_PROTOCOL, RealtimeProtocol.OPENAI_REALTIME.name).orEmpty()
        val protocol = runCatching { RealtimeProtocol.valueOf(protocolName) }.getOrDefault(RealtimeProtocol.OPENAI_REALTIME)
        val voice = prefs.getString(KEY_VOICE, "alloy").orEmpty().ifBlank { "alloy" }
        return RealtimeConfig(
            wsUrl = wsUrl,
            apiKey = apiKey,
            model = model,
            protocol = protocol,
            voice = voice,
        )
    }

    fun save(context: Context, config: RealtimeConfig?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (config == null || config.wsUrl.isBlank()) {
            prefs.edit().clear().apply()
        } else {
            prefs.edit()
                .putString(KEY_WS_URL, config.wsUrl.trim())
                .putString(KEY_API_KEY, config.apiKey)
                .putString(KEY_MODEL, config.model)
                .putString(KEY_PROTOCOL, config.protocol.name)
                .putString(KEY_VOICE, config.voice)
                .apply()
        }
    }
}

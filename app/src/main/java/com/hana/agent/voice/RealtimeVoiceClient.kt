package com.hana.agent.voice

import com.hana.agent.core.net.NetProxy
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * 实时音频协议类型
 */
enum class RealtimeProtocol {
    OPENAI_REALTIME,
    GEMINI_LIVE,
}

/**
 * 全双工实时音频会话配置
 */
data class RealtimeConfig(
    val wsUrl: String,
    val apiKey: String = "",
    val model: String = "gpt-4o-realtime-preview",
    val protocol: RealtimeProtocol = RealtimeProtocol.OPENAI_REALTIME,
    val sampleRate: Int = 16000,
    val voice: String = "alloy",
)

/**
 * 实时音频会话状态机
 */
sealed interface RealtimeSessionState {
    object Disconnected : RealtimeSessionState
    object Connecting : RealtimeSessionState
    object Connected : RealtimeSessionState
    object Speaking : RealtimeSessionState
    data class Error(val throwable: Throwable, val message: String = throwable.message.orEmpty()) : RealtimeSessionState
}

/**
 * 全双工下行事件
 */
sealed interface RealtimeDownlinkEvent {
    /** 服务端下发的 PCM 音频分块（默认 24kHz / 16-bit） */
    data class AudioChunk(val pcmBase64: String, val sampleRate: Int = 24000) : RealtimeDownlinkEvent

    /** 服务端语音转文字增量流 */
    data class TranscriptDelta(val text: String, val isFinal: Boolean = false) : RealtimeDownlinkEvent

    /** 本轮响应生成结束 */
    object ResponseDone : RealtimeDownlinkEvent

    /** 响应被打断（Barge-in 或服务端取消） */
    object Interrupted : RealtimeDownlinkEvent

    /** 协议级或网络异常 */
    data class ProtocolError(val message: String, val code: String? = null) : RealtimeDownlinkEvent
}

/**
 * Hana 全双工实时音频流客户端：
 * - 复用 [NetProxy.sharedClientBuilder] 全局连接池与代理配置；
 * - 支持标准 OpenAI Realtime 与 Gemini Live 双向流协议编解码；
 * - 暴露会话状态机 [state] 与下行事件流 [events]；
 * - 紧密集成 [VoiceActivityDetector] 的 Barge-in 打断语义，可在 300ms 内秒级切断服务端生成与本地播放。
 */
class RealtimeVoiceClient(
    val config: RealtimeConfig,
    private val client: OkHttpClient = NetProxy.sharedClientBuilder().build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<RealtimeSessionState>(RealtimeSessionState.Disconnected)
    val state: StateFlow<RealtimeSessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeDownlinkEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<RealtimeDownlinkEvent> = _events.asSharedFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    private val isClosed = AtomicBoolean(false)

    private val audioBuffer = ArrayDeque<RealtimeDownlinkEvent.AudioChunk>()
    private val bufferLock = Any()

    /**
     * 建立 WebSocket 双工连接并自动完成初始化握手
     */
    @Synchronized
    fun connect() {
        if (isClosed.get()) return
        val current = _state.value
        if (current is RealtimeSessionState.Connecting || current is RealtimeSessionState.Connected || current is RealtimeSessionState.Speaking) {
            return
        }

        _state.value = RealtimeSessionState.Connecting

        val requestBuilder = Request.Builder().url(config.wsUrl)
        if (config.apiKey.isNotBlank()) {
            when (config.protocol) {
                RealtimeProtocol.OPENAI_REALTIME -> {
                    requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
                    requestBuilder.header("OpenAI-Beta", "realtime=v1")
                }
                RealtimeProtocol.GEMINI_LIVE -> {
                    requestBuilder.header("x-goog-api-key", config.apiKey)
                }
            }
        }

        val request = requestBuilder.build()
        webSocket = client.newWebSocket(request, createSocketListener())
    }

    /**
     * 断开当前会话并释放资源
     */
    @Synchronized
    fun disconnect() {
        val ws = webSocket
        webSocket = null
        runCatching {
            ws?.close(1000, "Normal closure")
        }
        clearAudioBuffer()
        _state.value = RealtimeSessionState.Disconnected
    }

    /**
     * 关闭客户端并释放协程作用域
     */
    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            disconnect()
            scope.cancel()
        }
    }

    /**
     * 向服务端上行发送 16-bit PCM 音频数据
     */
    fun sendAudioPcm16(pcmBytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        val currentState = _state.value
        if (currentState !is RealtimeSessionState.Connected && currentState !is RealtimeSessionState.Speaking) {
            return false
        }

        val base64 = Base64.getEncoder().encodeToString(pcmBytes)
        val payload = when (config.protocol) {
            RealtimeProtocol.OPENAI_REALTIME -> {
                buildJsonObject {
                    put("type", "input_audio_buffer.append")
                    put("audio", base64)
                }.toString()
            }
            RealtimeProtocol.GEMINI_LIVE -> {
                buildJsonObject {
                    put(
                        "realtimeInput",
                        buildJsonObject {
                            put(
                                "mediaChunks",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("mimeType", "audio/pcm;rate=${config.sampleRate}")
                                            put("data", base64)
                                        }
                                    )
                                },
                            )
                        },
                    )
                }.toString()
            }
        }
        return ws.send(payload)
    }

    /**
     * VAD Barge-in 强插打断：
     * 立即向下发 `response.cancel` 帧并清空本地待播下行音频缓冲，实现秒级打断。
     */
    fun bargeInCancel(): Boolean {
        clearAudioBuffer()
        if (_state.value is RealtimeSessionState.Speaking) {
            _state.value = RealtimeSessionState.Connected
        }
        _events.tryEmit(RealtimeDownlinkEvent.Interrupted)

        val ws = webSocket ?: return false
        val cancelPayload = when (config.protocol) {
            RealtimeProtocol.OPENAI_REALTIME -> {
                buildJsonObject { put("type", "response.cancel") }.toString()
            }
            RealtimeProtocol.GEMINI_LIVE -> {
                // 发送兼容标准的取消帧
                buildJsonObject { put("type", "response.cancel") }.toString()
            }
        }
        return ws.send(cancelPayload)
    }

    /**
     * 与端侧 [VoiceActivityDetector] 及 [VoiceSpeaker] 联动：
     * 当 VAD 触发 Barge-in 事件时，自动停止 TTS 播报并下发打断帧。
     */
    fun bindVad(vad: VoiceActivityDetector, speaker: VoiceSpeaker? = null): (VadEvent) -> Unit {
        return { event ->
            handleVadEvent(event, speaker)
        }
    }

    /**
     * 处理 VAD 事件
     */
    fun handleVadEvent(event: VadEvent, speaker: VoiceSpeaker? = null) {
        if (event is VadEvent.BargeIn) {
            speaker?.stop()
            bargeInCancel()
        }
    }

    /**
     * 取出待播放的下一包下行音频数据
     */
    fun pollNextAudioChunk(): RealtimeDownlinkEvent.AudioChunk? {
        return synchronized(bufferLock) {
            if (audioBuffer.isNotEmpty()) audioBuffer.removeFirst() else null
        }
    }

    /**
     * 清空下行音频缓冲
     */
    fun clearAudioBuffer() {
        synchronized(bufferLock) {
            audioBuffer.clear()
        }
    }

    private fun sendHandshakeFrame(ws: WebSocket) {
        val handshakeJson = when (config.protocol) {
            RealtimeProtocol.OPENAI_REALTIME -> {
                buildJsonObject {
                    put("type", "session.update")
                    put(
                        "session",
                        buildJsonObject {
                            put("modalities", buildJsonArray { add("text"); add("audio") })
                            put("voice", config.voice)
                            put("input_audio_format", "pcm16")
                            put("output_audio_format", "pcm16")
                            put("turn_detection", JsonNull)
                        },
                    )
                }.toString()
            }
            RealtimeProtocol.GEMINI_LIVE -> {
                buildJsonObject {
                    put(
                        "setup",
                        buildJsonObject {
                            put("model", "models/${config.model}")
                            put(
                                "generationConfig",
                                buildJsonObject {
                                    put("responseModalities", buildJsonArray { add("AUDIO") })
                                },
                            )
                        },
                    )
                }.toString()
            }
        }
        ws.send(handshakeJson)
    }

    private fun createSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = RealtimeSessionState.Connected
                sendHandshakeFrame(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 部分协议可能下发二进制帧，先尝试转 UTF-8 字符串
                runCatching {
                    handleIncomingMessage(bytes.utf8())
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = RealtimeSessionState.Disconnected
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = RealtimeSessionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = RealtimeSessionState.Error(t, t.message ?: "WebSocket failure")
                _events.tryEmit(RealtimeDownlinkEvent.ProtocolError(t.message ?: "WebSocket failure"))
            }
        }
    }

    private fun handleIncomingMessage(text: String) {
        runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            when (config.protocol) {
                RealtimeProtocol.OPENAI_REALTIME -> handleOpenAiMessage(root)
                RealtimeProtocol.GEMINI_LIVE -> handleGeminiLiveMessage(root)
            }
        }.onFailure { err ->
            _events.tryEmit(RealtimeDownlinkEvent.ProtocolError("Failed to parse incoming frame: ${err.message}"))
        }
    }

    private fun handleOpenAiMessage(root: JsonObject) {
        val type = root["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        when (type) {
            "response.audio.delta" -> {
                val delta = root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    _state.value = RealtimeSessionState.Speaking
                    val chunk = RealtimeDownlinkEvent.AudioChunk(delta, 24000)
                    synchronized(bufferLock) { audioBuffer.addLast(chunk) }
                    _events.tryEmit(chunk)
                }
            }
            "response.audio_transcript.delta" -> {
                val delta = root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                _events.tryEmit(RealtimeDownlinkEvent.TranscriptDelta(delta, isFinal = false))
            }
            "response.audio_transcript.done" -> {
                val transcript = root["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty()
                _events.tryEmit(RealtimeDownlinkEvent.TranscriptDelta(transcript, isFinal = true))
            }
            "response.done" -> {
                _state.value = RealtimeSessionState.Connected
                _events.tryEmit(RealtimeDownlinkEvent.ResponseDone)
            }
            "response.cancelled" -> {
                clearAudioBuffer()
                _state.value = RealtimeSessionState.Connected
                _events.tryEmit(RealtimeDownlinkEvent.Interrupted)
            }
            "error" -> {
                val errObj = root["error"] as? JsonObject
                val msg = errObj?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: root["message"]?.jsonPrimitive?.contentOrNull
                    ?: "Unknown error"
                val code = errObj?.get("code")?.jsonPrimitive?.contentOrNull
                _state.value = RealtimeSessionState.Error(RuntimeException(msg), msg)
                _events.tryEmit(RealtimeDownlinkEvent.ProtocolError(msg, code))
            }
        }
    }

    private fun handleGeminiLiveMessage(root: JsonObject) {
        val serverContent = root["serverContent"] as? JsonObject
        if (serverContent != null) {
            if (serverContent["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                clearAudioBuffer()
                _state.value = RealtimeSessionState.Connected
                _events.tryEmit(RealtimeDownlinkEvent.Interrupted)
                return
            }

            val modelTurn = serverContent["modelTurn"] as? JsonObject
            val parts = modelTurn?.get("parts")?.let { runCatching { it.jsonArray }.getOrNull() }
            if (parts != null) {
                for (element in parts) {
                    val part = element as? JsonObject ?: continue
                    val inlineData = part["inlineData"] as? JsonObject
                    if (inlineData != null) {
                        val mime = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val data = inlineData["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (mime.startsWith("audio") && data.isNotEmpty()) {
                            _state.value = RealtimeSessionState.Speaking
                            val chunk = RealtimeDownlinkEvent.AudioChunk(data, 24000)
                            synchronized(bufferLock) { audioBuffer.addLast(chunk) }
                            _events.tryEmit(chunk)
                        }
                    }
                    val text = part["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (text.isNotEmpty()) {
                        _events.tryEmit(RealtimeDownlinkEvent.TranscriptDelta(text, isFinal = false))
                    }
                }
            }

            if (serverContent["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                _state.value = RealtimeSessionState.Connected
                _events.tryEmit(RealtimeDownlinkEvent.ResponseDone)
            }
        }

        if ("error" in root) {
            val errObj = root["error"] as? JsonObject
            val msg = errObj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Gemini live protocol error"
            val code = errObj?.get("code")?.jsonPrimitive?.contentOrNull
            _state.value = RealtimeSessionState.Error(RuntimeException(msg), msg)
            _events.tryEmit(RealtimeDownlinkEvent.ProtocolError(msg, code))
        }
    }
}

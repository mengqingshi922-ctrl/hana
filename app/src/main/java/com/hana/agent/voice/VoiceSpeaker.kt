package com.hana.agent.voice

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hana 全双工语音播报器：
 * 1. 移除旧版 80 字截断限制，支持长文本按中英文标点自动分句并流式排队播报。
 * 2. 通过 UtteranceProgressListener (onStart / onRangeStart / onAudioAvailable / onDone / onStop)
 *    输出约 30fps 的实时播放振幅 [playbackAmplitude]，驱动 Hana 精灵微跳动与声波反馈。
 * 3. 支持随时被 VAD / Barge-in 秒级打断（[stop] 清空待播队列并归零振幅）。
 */
class VoiceSpeaker @JvmOverloads constructor(
    context: Context,
    private val initialAmplitudeListener: ((Float) -> Unit)? = null,
) {
    private val appContext: Context = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pulseJob: Job? = null

    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    @Volatile private var isInitializing = false
    @Volatile private var isShutdown = false

    private data class QueuedUtterance(
        val id: String,
        val text: String,
        val flushQueue: Boolean,
    )

    private val pendingQueue = ArrayDeque<QueuedUtterance>()
    private val activeUtterances = ConcurrentHashMap.newKeySet<String>()
    private val streamBuffer = StringBuilder()
    private val utteranceSeq = AtomicLong(0L)

    @Volatile private var amplitudeListener: ((Float) -> Unit)? = initialAmplitudeListener
    @Volatile private var lastAudioFrameAtMs: Long = 0L
    @Volatile private var rangePulseBoost: Float = 0f

    private val _playbackAmplitude = MutableStateFlow(0f)
    val playbackAmplitude: StateFlow<Float> = _playbackAmplitude.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun setAmplitudeListener(listener: ((Float) -> Unit)?) {
        amplitudeListener = listener
    }

    private fun isAudioMuted(): Boolean {
        val am = audioManager ?: return false
        return runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }.getOrDefault(false)
    }

    @Synchronized
    private fun ensureInit(onReady: (TextToSpeech) -> Unit) {
        if (isShutdown) return
        val existing = tts
        if (existing != null && isInitialized) {
            onReady(existing)
            return
        }
        if (isInitializing) return
        isInitializing = true
        tts = TextToSpeech(appContext) { status ->
            synchronized(this) {
                isInitializing = false
                if (isShutdown) {
                    runCatching { tts?.shutdown() }
                    tts = null
                    pendingQueue.clear()
                    return@TextToSpeech
                }
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts
                    if (engine != null) {
                        configureEngine(engine)
                        isInitialized = true
                        flushPendingToEngine(engine)
                    }
                } else {
                    Log.w(TAG, "TextToSpeech init failed with status: $status")
                    tts = null
                    pendingQueue.clear()
                    setIdleState()
                }
            }
        }
    }

    private fun configureEngine(engine: TextToSpeech) {
        runCatching {
            val locale = Locale.getDefault()
            val res = engine.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.language = Locale.CHINESE
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null) activeUtterances.add(utteranceId)
                _isSpeaking.value = true
                rangePulseBoost = 0.45f
                startAmplitudePulse()
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                // 每朗读到一个新词段注入脉冲，使没有 onAudioAvailable 回调的 TTS 引擎也有自然律动
                rangePulseBoost = (0.35f + ((end - start).coerceIn(1, 10) * 0.055f)).coerceIn(0.35f, 0.92f)
            }

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                if (audio == null || audio.isEmpty()) return
                val amp = computePcm16Amplitude(audio)
                lastAudioFrameAtMs = System.currentTimeMillis()
                emitAmplitude(amp)
            }

            override fun onDone(utteranceId: String?) {
                handleUtteranceFinished(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (interrupted) {
                    activeUtterances.clear()
                    setIdleState()
                } else {
                    handleUtteranceFinished(utteranceId)
                }
            }

            @Deprecated("Deprecated in Android API")
            override fun onError(utteranceId: String?) {
                handleUtteranceFinished(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleUtteranceFinished(utteranceId)
            }
        })
    }

    private fun handleUtteranceFinished(utteranceId: String?) {
        if (utteranceId != null) {
            activeUtterances.remove(utteranceId)
        }
        val hasMore = synchronized(this) { pendingQueue.isNotEmpty() } || activeUtterances.isNotEmpty()
        if (!hasMore) {
            setIdleState()
        }
    }

    @Synchronized
    private fun flushPendingToEngine(engine: TextToSpeech) {
        while (pendingQueue.isNotEmpty()) {
            val item = pendingQueue.removeFirst()
            speakUtterance(engine, item)
        }
    }

    private fun speakUtterance(engine: TextToSpeech, item: QueuedUtterance) {
        activeUtterances.add(item.id)
        val queueMode = if (item.flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, item.id)
        }
        val code = runCatching {
            engine.speak(item.text, queueMode, params, item.id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (code == TextToSpeech.ERROR) {
            activeUtterances.remove(item.id)
            if (activeUtterances.isEmpty()) setIdleState()
        }
    }

    private fun startAmplitudePulse() {
        if (pulseJob?.isActive == true) return
        pulseJob = scope.launch {
            var phase = 0f
            while (isActive && _isSpeaking.value) {
                val now = System.currentTimeMillis()
                // 若最近 120ms 内有真实 PCM 音频帧回调，以真实音频为准，否则使用语段律动合成柔和包络
                if (now - lastAudioFrameAtMs > 120L) {
                    phase += 0.34f
                    val base = 0.24f + 0.14f * abs(sin(phase))
                    val boost = rangePulseBoost
                    rangePulseBoost *= 0.84f
                    emitAmplitude((base + boost * 0.55f).coerceIn(0.05f, 1f))
                }
                delay(33L)
            }
        }
    }

    private fun emitAmplitude(amp: Float) {
        val clamped = amp.coerceIn(0f, 1f)
        _playbackAmplitude.value = clamped
        amplitudeListener?.invoke(clamped)
    }

    private fun setIdleState() {
        pulseJob?.cancel()
        pulseJob = null
        _isSpeaking.value = false
        rangePulseBoost = 0f
        emitAmplitude(0f)
    }

    /**
     * 立即打断当前播报并按句子拆分整段长文本，加入流式播报队列（不再截断 80 字）。
     */
    fun speak(text: String) {
        if (isShutdown || isAudioMuted()) return
        val sentences = splitIntoSentences(text)
        if (sentences.isEmpty()) return

        synchronized(this) {
            streamBuffer.clear()
            pendingQueue.clear()
            activeUtterances.clear()
            sentences.forEachIndexed { index, sentence ->
                val id = "hana_tts_${utteranceSeq.incrementAndGet()}"
                pendingQueue.addLast(QueuedUtterance(id = id, text = sentence, flushQueue = index == 0))
            }
            val engine = tts
            if (engine != null && isInitialized) {
                flushPendingToEngine(engine)
                return
            }
        }
        ensureInit { engine -> flushPendingToEngine(engine) }
    }

    /**
     * 追加播报一段文本，不打断当前正在播报的语句。
     */
    fun enqueue(text: String) {
        if (isShutdown || isAudioMuted()) return
        val sentences = splitIntoSentences(text)
        if (sentences.isEmpty()) return

        synchronized(this) {
            sentences.forEach { sentence ->
                val id = "hana_tts_${utteranceSeq.incrementAndGet()}"
                pendingQueue.addLast(QueuedUtterance(id = id, text = sentence, flushQueue = false))
            }
            val engine = tts
            if (engine != null && isInitialized) {
                flushPendingToEngine(engine)
                return
            }
        }
        ensureInit { engine -> flushPendingToEngine(engine) }
    }

    /**
     * 流式分块输入：把增量文本追加到缓冲，遇到完整句子立即送入 [enqueue]，剩余半句留待后续。
     */
    fun feedStreamChunk(delta: String) {
        if (delta.isEmpty() || isShutdown) return
        val readyText: String? = synchronized(this) {
            streamBuffer.append(delta)
            val current = streamBuffer.toString()
            val lastCut = findLastSentenceBoundaryIndex(current)
            if (lastCut >= 0) {
                val head = current.substring(0, lastCut + 1)
                val tail = current.substring(lastCut + 1)
                streamBuffer.clear()
                streamBuffer.append(tail)
                head
            } else if (current.length >= DEFAULT_MAX_CHUNK_CHARS) {
                streamBuffer.clear()
                current
            } else {
                null
            }
        }
        if (!readyText.isNullOrBlank()) {
            enqueue(readyText)
        }
    }

    /**
     * 刷新流式缓冲区中的末尾残留文本。
     */
    fun flushStream() {
        val remaining = synchronized(this) {
            val s = streamBuffer.toString().trim()
            streamBuffer.clear()
            s
        }
        if (remaining.isNotEmpty()) {
            enqueue(remaining)
        }
    }

    /**
     * 立即停止播报并清空流式队列（用于 VAD 语音打断 / Barge-in）。
     */
    fun stop() {
        synchronized(this) {
            streamBuffer.clear()
            pendingQueue.clear()
            activeUtterances.clear()
            runCatching { tts?.stop() }
        }
        setIdleState()
    }

    fun shutdown() {
        isShutdown = true
        synchronized(this) {
            streamBuffer.clear()
            pendingQueue.clear()
            activeUtterances.clear()
            isInitialized = false
            isInitializing = false
            runCatching {
                tts?.stop()
                tts?.shutdown()
            }
            tts = null
        }
        setIdleState()
        scope.cancel()
    }

    companion object {
        private const val TAG = "VoiceSpeaker"
        const val DEFAULT_MAX_CHUNK_CHARS = 160

        private val SENTENCE_END_CHARS = setOf('。', '！', '？', '!', '?', '；', ';', '\n', '…')

        /**
         * 将任意长度文本按中英文句读标点智能分句：
         * - 保留小数点（如 `3.14`）和常见缩写中的点，避免误切
         * - 不丢弃任何正文内容（彻底告别 80 字硬截断）
         * - 对超长无标点单句按逗号/顿号/空格进一步安全切分，保障首句低延迟播报
         */
        fun splitIntoSentences(text: String, maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS): List<String> {
            val normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            if (normalized.isEmpty()) return emptyList()

            val rawSentences = mutableListOf<String>()
            val current = StringBuilder()

            for (i in normalized.indices) {
                val ch = normalized[i]
                current.append(ch)
                val isPeriod = ch == '.' && isEnglishSentencePeriod(normalized, i)
                if (ch in SENTENCE_END_CHARS || isPeriod) {
                    val seg = current.toString().trim()
                    if (seg.isNotEmpty()) rawSentences.add(seg)
                    current.clear()
                }
            }
            val tail = current.toString().trim()
            if (tail.isNotEmpty()) rawSentences.add(tail)

            val result = mutableListOf<String>()
            val safeLimit = maxChunkChars.coerceAtLeast(24)
            for (sentence in rawSentences) {
                if (sentence.length <= safeLimit) {
                    result.add(sentence)
                } else {
                    splitLongClause(sentence, safeLimit, result)
                }
            }
            return result
        }

        private fun isEnglishSentencePeriod(text: String, index: Int): Boolean {
            val prev = text.getOrNull(index - 1)
            val next = text.getOrNull(index + 1)
            // 保留小数/版本号如 3.14 或 Android 16.0
            if (prev != null && prev.isDigit() && next != null && next.isDigit()) return false
            // 句末或后接空白/引号/换行则判定为英文句号
            return next == null || next.isWhitespace() || next == '"' || next == '\'' || next == '”' || next == ')' || next == '）'
        }

        private fun splitLongClause(sentence: String, maxLen: Int, out: MutableList<String>) {
            var start = 0
            while (start < sentence.length) {
                if (sentence.length - start <= maxLen) {
                    val rest = sentence.substring(start).trim()
                    if (rest.isNotEmpty()) out.add(rest)
                    break
                }
                val endLimit = (start + maxLen).coerceAtMost(sentence.length)
                var splitAt = -1
                for (i in endLimit - 1 downTo start + (maxLen / 3)) {
                    val c = sentence[i]
                    if (c == '，' || c == ',' || c == '、' || c == '：' || c == ':' || c == ' ') {
                        splitAt = i + 1
                        break
                    }
                }
                if (splitAt <= start) splitAt = endLimit
                val piece = sentence.substring(start, splitAt).trim()
                if (piece.isNotEmpty()) out.add(piece)
                start = splitAt
            }
        }

        private fun findLastSentenceBoundaryIndex(text: String): Int {
            for (i in text.length - 1 downTo 0) {
                val c = text[i]
                if (c in SENTENCE_END_CHARS || (c == '.' && isEnglishSentencePeriod(text, i))) {
                    return i
                }
            }
            return -1
        }

        /**
         * 从小端 16-bit PCM 字节流计算归一化瞬时振幅 `[0f, 1f]`。
         */
        fun computePcm16Amplitude(pcmBytes: ByteArray): Float {
            if (pcmBytes.size < 2) return 0f
            val sampleCount = pcmBytes.size / 2
            var sumSq = 0.0
            for (i in 0 until sampleCount) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toDouble()
                sumSq += sample * sample
            }
            val rms = sqrt(sumSq / sampleCount)
            return (rms / 8000.0).coerceIn(0.0, 1.0).toFloat()
        }
    }
}

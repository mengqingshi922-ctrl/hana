package com.hana.agent.voice

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 轻量级、非阻塞的系统原生 TTS 语音播报器。
 * 懒加载初始化，自动适配系统默认语言，并感知当前多媒体音量（静音时不打扰用户）。
 */
class VoiceSpeaker(context: Context) {
    private val appContext: Context = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    @Volatile private var isInitializing = false
    private val pendingTexts = mutableListOf<String>()

    @Synchronized
    private fun ensureInit(onReady: (TextToSpeech) -> Unit) {
        val existing = tts
        if (existing != null && isInitialized) {
            onReady(existing)
            return
        }
        if (isInitializing) {
            return
        }
        isInitializing = true
        tts = TextToSpeech(appContext) { status ->
            synchronized(this) {
                isInitializing = false
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts
                    if (engine != null) {
                        runCatching {
                            val locale = Locale.getDefault()
                            val res = engine.setLanguage(locale)
                            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                                engine.language = Locale.CHINESE
                            }
                        }
                        isInitialized = true
                        val toSpeak = pendingTexts.toList()
                        pendingTexts.clear()
                        toSpeak.lastOrNull()?.let { text -> doSpeak(engine, text) }
                    }
                } else {
                    Log.w("VoiceSpeaker", "TextToSpeech init failed with status: $status")
                    tts = null
                    pendingTexts.clear()
                }
            }
        }
    }

    private fun doSpeak(engine: TextToSpeech, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val truncated = if (trimmed.length > 80) trimmed.take(80) else trimmed
        engine.speak(truncated, TextToSpeech.QUEUE_FLUSH, null, "hana_alert_${System.currentTimeMillis()}")
    }

    /**
     * 播报语音。
     * 检查 STREAM_MUSIC 音量，若为 0 则静默跳过；文本自动截取前 80 字。
     */
    fun speak(text: String) {
        val volume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1
        if (volume <= 0) {
            // 静音状态跳过播报
            return
        }
        synchronized(this) {
            val engine = tts
            if (engine != null && isInitialized) {
                doSpeak(engine, text)
            } else {
                pendingTexts.clear()
                pendingTexts.add(text)
                ensureInit { doSpeak(it, text) }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            runCatching { tts?.stop() }
            pendingTexts.clear()
        }
    }

    fun shutdown() {
        synchronized(this) {
            stop()
            runCatching { tts?.shutdown() }
            tts = null
            isInitialized = false
            isInitializing = false
        }
    }
}

package com.hana.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeakerAndVadTest {

    @Test
    fun splitIntoSentencesRemoves80CharLimitAndPreservesAllContent() {
        val longText = buildString {
            append("第一步先打开电商应用，把购物车里需要结算的商品逐个核对清楚。")
            append("如果页面出现滑块验证码或者短信二次校验，系统会自动把小窗瞬移到你面前！")
            append("Version 3.14 works smoothly without cutting at 80 characters. ")
            append("请问还需要我帮你修改收货地址吗？")
        }
        assertTrue(longText.length > 120)

        val sentences = VoiceSpeaker.splitIntoSentences(longText)
        assertEquals(4, sentences.size)
        assertEquals("第一步先打开电商应用，把购物车里需要结算的商品逐个核对清楚。", sentences[0])
        assertEquals("如果页面出现滑块验证码或者短信二次校验，系统会自动把小窗瞬移到你面前！", sentences[1])
        assertEquals("Version 3.14 works smoothly without cutting at 80 characters.", sentences[2])
        assertEquals("请问还需要我帮你修改收货地址吗？", sentences[3])
        // 确认没有任何文字被旧版 80 字逻辑丢弃
        val joinedLength = sentences.sumOf { it.length }
        assertTrue(joinedLength > 100)
    }

    @Test
    fun splitIntoSentencesSplitsUltraLongUnpunctuatedClauseSafely() {
        val longClause = "正在检索目标页面上的全部商品价格信息并且核对优惠券与满减规则，请稍等片刻让我完成比价计算后再向你汇报最终的最优下单方案"
        val chunks = VoiceSpeaker.splitIntoSentences(longClause, maxChunkChars = 32)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 32 })
        assertEquals(longClause.replace("，", "，"), chunks.joinToString(""))
    }

    @Test
    fun computePcm16AmplitudeHandlesSilenceAndTone() {
        assertEquals(0f, VoiceSpeaker.computePcm16Amplitude(ByteArray(0)), 1e-5f)
        assertEquals(0f, VoiceSpeaker.computePcm16Amplitude(ByteArray(64)), 1e-5f)

        // 构造振幅 4000 的 16-bit 小端 PCM 样本 -> RMS / 8000 = 0.5
        val pcm = ByteArray(100)
        for (i in 0 until 50) {
            val v = 4000
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        assertEquals(0.5f, VoiceSpeaker.computePcm16Amplitude(pcm), 1e-3f)
    }

    @Test
    fun vadDetectsSpeechStartSilenceTimeoutAndBargeInSteerRedirect() {
        var assistantBusy = false
        var playbackStopped = 0
        var bargeInCount = 0
        var lastSpeechDuration = -1L
        var steeredText: String? = null

        val bridge = FullDuplexVoiceBridge(
            config = VadConfig(
                startEnergyThreshold = 0.06f,
                continueEnergyThreshold = 0.03f,
                startTriggerFrames = 2,
                bargeInEnergyThreshold = 0.10f,
                bargeInFrames = 2,
                silenceTimeoutMs = 300L,
                minSpeechDurationMs = 100L,
                frameDurationMs = 50L,
            ),
            isAssistantBusy = { assistantBusy },
            onStopPlayback = { playbackStopped++ },
            onBargeInDetected = { bargeInCount++ },
            onSpeechEndDetected = { dur -> lastSpeechDuration = dur },
            onSteerRedirect = { text -> steeredText = text },
        )

        // 1. 环境底噪阶段不误触发
        var t = 1000L
        repeat(4) {
            val evs = bridge.onEnergySample(0.012f, t)
            assertTrue(evs.isEmpty())
            t += 50L
        }
        assertEquals(VadPhase.SILENCE, bridge.snapshot().phase)

        // 2. 助手正在播报/执行时用户开口插话 -> 触发 SpeechStart + BargeIn，停播并标记 pendingBargeIn
        assistantBusy = true
        val ev1 = bridge.onEnergySample(0.16f, t)
        assertTrue(ev1.isEmpty()) // 第 1 帧防抖
        t += 50L
        val ev2 = bridge.onEnergySample(0.18f, t)
        assertTrue(ev2.any { it is VadEvent.SpeechStart })
        assertTrue(ev2.any { it is VadEvent.BargeIn })
        assertEquals(1, playbackStopped)
        assertEquals(1, bargeInCount)
        assertTrue(bridge.hasPendingBargeIn())
        assertEquals(VadPhase.SPEAKING, bridge.snapshot().phase)

        // 3. 持续说话几帧
        t += 50L
        val ev3 = bridge.onEnergySample(0.11f, t)
        assertTrue(ev3.any { it is VadEvent.SpeechActive })
        t += 50L
        bridge.onEnergySample(0.09f, t)

        // 4. 静音超时后产生 SpeechEnd
        var gotEnd = false
        for (step in 1..8) {
            t += 50L
            val ev = bridge.onEnergySample(0.01f, t)
            if (ev.any { it is VadEvent.SpeechEnd }) {
                gotEnd = true
                break
            }
        }
        assertTrue("应在静音超时后触发 SpeechEnd", gotEnd)
        assertEquals(VadPhase.SILENCE, bridge.snapshot().phase)
        assertTrue(lastSpeechDuration >= 100L)

        // 5. 识别文本回传后触发 Steer 重定向
        bridge.submitTranscript("不要点确认，先帮我改到明天早上")
        assertEquals("不要点确认，先帮我改到明天早上", steeredText)
        assertFalse(bridge.hasPendingBargeIn())
    }
}

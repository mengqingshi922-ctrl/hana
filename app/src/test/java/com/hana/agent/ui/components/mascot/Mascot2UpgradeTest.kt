package com.hana.agent.ui.components.mascot

import com.hana.agent.capsule.AgentTaskPhase
import com.hana.agent.ui.components.GrokBotMood
import com.hana.agent.voice.EnergyZcrVad
import com.hana.agent.voice.EnergyZcrVadConfig
import com.hana.agent.voice.FullDuplexState
import com.hana.agent.voice.RealtimeConfig
import com.hana.agent.voice.RealtimeFullDuplexBridge
import com.hana.agent.voice.RealtimeProtocol
import com.hana.agent.voice.RealtimeSessionState
import com.hana.agent.voice.RealtimeVoiceClient
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 灵动小精灵 2.0（视线追踪总线、隐私护盾优先层、内联情绪标签解析、AGSL 版本分级）
 * 与全双工语音打断闭环（Energy+ZCR VAD + RealtimeFullDuplexBridge）单元测试。
 */
class Mascot2UpgradeTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        MascotGazeBus.resetForTesting()
        StreamEmotionBus.resetForTesting()
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        MascotGazeBus.resetForTesting()
        StreamEmotionBus.resetForTesting()
        runCatching { server.shutdown() }
        httpClient.dispatcher.executorService.shutdownNow()
        httpClient.connectionPool.evictAll()
    }

    @Test
    fun mascotGazeBusTracksCoordinatesClampsAndExpiresByTtl() {
        var fakeNow = 10_000L
        MascotGazeBus.nowMsProvider = { fakeNow }

        MascotGazeBus.focusAt(normX = 2.4f, normY = -3.1f, ttlMs = 1500L)
        val active = MascotGazeBus.activeTarget()
        assertNotNull(active)
        assertEquals(1f, active!!.normX, 0.001f)
        assertEquals(0f, active.normY, 0.001f)
        assertEquals(11_500L, active.expiresAtMs)

        val offset = computeGazeOffset(active, nowMs = 10_800L, maxShiftX = 14f, maxShiftY = 10f)
        assertNotNull(offset)
        assertEquals(14f, offset!!.first, 0.001f)
        assertEquals(-10f, offset.second, 0.001f)

        // 超过 TTL 后自动失效并平滑回落待机（返回 null）
        fakeNow = 11_501L
        assertNull(MascotGazeBus.activeTarget())
        assertNull(computeGazeOffset(active, nowMs = fakeNow))

        // 手动 clear 立即清空
        MascotGazeBus.focusAt(0.5f, 0.25f, ttlMs = 2000L)
        assertNotNull(MascotGazeBus.target.value)
        MascotGazeBus.clear()
        assertNull(MascotGazeBus.target.value)
    }

    @Test
    fun streamEmotionBusPriorityRespectsPrivacyShieldThenStreamOverrideThenBaseContract() {
        // 保留原有 mascotEmotionId 映射契约
        val baseId = mascotEmotionId(
            mood = GrokBotMood.Idle,
            working = false,
            phase = AgentTaskPhase.Idle,
        )
        assertEquals("02", baseId)

        // 无覆盖时回落基础表情
        assertEquals(baseId, resolveEffectiveEmotionId(baseId, StreamEmotionBus.overrideEmotionId.value, StreamEmotionBus.privacyShieldActive.value))

        // 流式情绪覆盖生效
        StreamEmotionBus.setStreamEmotion("18")
        assertEquals("18", resolveEffectiveEmotionId(baseId, StreamEmotionBus.overrideEmotionId.value, StreamEmotionBus.privacyShieldActive.value))

        // 隐私护盾优先级最高（覆盖流式表情与基础表情）
        StreamEmotionBus.setPrivacyShield(true)
        assertEquals(
            PRIVACY_SHIELD_EMOTION_ID,
            resolveEffectiveEmotionId(baseId, StreamEmotionBus.overrideEmotionId.value, StreamEmotionBus.privacyShieldActive.value),
        )

        // 护盾关闭后自动恢复流式覆盖表情
        StreamEmotionBus.setPrivacyShield(false)
        assertEquals("18", resolveEffectiveEmotionId(baseId, StreamEmotionBus.overrideEmotionId.value, StreamEmotionBus.privacyShieldActive.value))

        // 非法覆盖 ID 安全回落基础表情
        StreamEmotionBus.setStreamEmotion("99")
        assertNull(StreamEmotionBus.overrideEmotionId.value)
        assertEquals(baseId, resolveEffectiveEmotionId(baseId, StreamEmotionBus.overrideEmotionId.value, StreamEmotionBus.privacyShieldActive.value))
    }

    @Test
    fun inlineEmotionTagParserStripsTagsAndActivatesFullEmotionSet() {
        // 解析并剥离标准 [emo:XX] 编号，激活闲置的高编号表情
        val (clean1, emo1) = InlineEmotionTagParser.stripAndExtract("[emo:35] 收到啦，马上帮你查一下！")
        assertEquals("收到啦，马上帮你查一下！", clean1)
        assertEquals("35", emo1)

        // 解析中英文自然情绪别名
        val (clean2, emo2) = InlineEmotionTagParser.stripAndExtract("[shy] 好呀，这个秘密我只帮你记着。")
        assertEquals("好呀，这个秘密我只帮你记着。", clean2)
        assertEquals("14", emo2)

        val (clean3, emo3) = InlineEmotionTagParser.stripAndExtract("思考了一下 [开心] 已经帮你搞定啦！")
        assertEquals("思考了一下 已经帮你搞定啦！", clean3)
        assertEquals("10", emo3)

        // 多标签时全部剥离并取最新（末尾）情绪
        val (clean4, emo4) = InlineEmotionTagParser.stripAndExtract("[thinking] 先看看文件，[emo:41] 搞定！")
        assertEquals("先看看文件， 搞定！", clean4)
        assertEquals("41", emo4)

        // 不误伤 Markdown 链接和普通中括号注释
        val mdText = "请点击 [查看详情](https://example.com) 或 [步骤1] 继续"
        val (cleanMd, emoMd) = InlineEmotionTagParser.stripAndExtract(mdText)
        assertEquals(mdText, cleanMd)
        assertNull(emoMd)
    }

    @Test
    fun agslShaderSupportMatchesApi33Threshold() {
        assertFalse(isAgslShaderSupported(sdkInt = 29))
        assertFalse(isAgslShaderSupported(sdkInt = 32))
        assertTrue(isAgslShaderSupported(sdkInt = 33))
        assertTrue(isAgslShaderSupported(sdkInt = 35))
    }

    @Test
    fun energyZcrVadIgnoresSilenceAndTriggersBargeInOnSpeech() {
        val vad = EnergyZcrVad(
            EnergyZcrVadConfig(
                startEnergyThreshold = 0.045f,
                continueEnergyThreshold = 0.025f,
                bargeInEnergyThreshold = 0.065f,
                minSpeechZcr = 0.02f,
                maxSpeechZcr = 0.45f,
                startTriggerFrames = 2,
                bargeInFrames = 2,
            )
        )

        // 1. 静音噪底学习阶段不误触发
        val silence = ShortArray(320) { 20 }
        repeat(5) { idx ->
            val d = vad.processFrame(silence, isAssistantSpeaking = true, timestampMs = idx * 20L)
            assertFalse("静音帧不应触发说话", d.isSpeech)
            assertFalse("静音帧不应触发打断", d.bargeInTriggered)
        }

        // 2. 合成 440Hz 清晰人声正弦波（16kHz 采样率，20ms = 320 samples）
        val voiceFrame = ShortArray(320) { i ->
            (sin(2.0 * PI * 440.0 * i / 16_000.0) * 14_000.0).toInt().toShort()
        }

        val frame1 = vad.processFrame(voiceFrame, isAssistantSpeaking = true, timestampMs = 200L)
        assertFalse("第 1 帧仅计数，尚未越过连续确认阈值", frame1.bargeInTriggered)

        val frame2 = vad.processFrame(voiceFrame, isAssistantSpeaking = true, timestampMs = 220L)
        assertTrue("连续人声帧应激活语音状态", frame2.isSpeech)
        assertTrue("助手播报期间检测到连续人声应触发 Barge-in", frame2.bargeInTriggered)
    }

    @Test
    fun fullDuplexBridgeCancelsServerResponseOnBargeInAndForwardsUserTranscript() = runBlocking {
        val receivedTypes = Channel<String>(capacity = Channel.UNLIMITED)
        val serverSocketReady = CompletableDeferred<WebSocket>()

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocketReady.complete(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val match = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(text)
                        match?.groupValues?.getOrNull(1)?.let { receivedTypes.trySend(it) }
                    }
                },
            ),
        )

        val wsUrl = server.url("/v1/realtime").toString().replaceFirst("http://", "ws://")
        val client = RealtimeVoiceClient(
            config = RealtimeConfig(
                wsUrl = wsUrl,
                apiKey = "test-key",
                protocol = RealtimeProtocol.OPENAI_REALTIME,
            ),
            client = httpClient,
        )

        val userTranscripts = mutableListOf<String>()
        var bargeInCount = 0
        var localStopCount = 0

        val bridge = RealtimeFullDuplexBridge(
            client = client,
            onUserTranscript = { userTranscripts += it },
            onBargeIn = { bargeInCount++ },
            onStopLocalPlayback = { localStopCount++ },
            vad = EnergyZcrVad(
                EnergyZcrVadConfig(
                    startEnergyThreshold = 0.045f,
                    continueEnergyThreshold = 0.025f,
                    bargeInEnergyThreshold = 0.065f,
                    startTriggerFrames = 2,
                    bargeInFrames = 2,
                )
            ),
        )
        bridge.startDownlinkCollectors()
        client.connect()

        val ws = withTimeout(3000) { serverSocketReady.await() }
        // 等待 session.update 发出，确认会话已连上
        withTimeout(3000) {
            while (true) {
                if (receivedTypes.receive() == "session.update") break
            }
        }

        // 模拟服务端下发语音片段，驱动桥接器进入 AssistantSpeaking 状态
        val fakeAudioBase64 = Base64.getEncoder().encodeToString(ByteArray(64) { 1 })
        ws.send("""{"type":"response.audio.delta","delta":"$fakeAudioBase64"}""")
        withTimeout(3000) {
            while (bridge.state.value != FullDuplexState.AssistantSpeaking &&
                client.state.value !is RealtimeSessionState.Speaking
            ) {
                delay(15)
            }
        }

        // 注入两帧人声 PCM 触发端侧 VAD Barge-in
        val speechFrame = ShortArray(320) { i ->
            (sin(2.0 * PI * 440.0 * i / 16_000.0) * 14_000.0).toInt().toShort()
        }
        bridge.processCapturedPcmFrame(speechFrame, timestampMs = 500L)
        bridge.processCapturedPcmFrame(speechFrame, timestampMs = 520L)

        // 验证客户端确实发送了 response.cancel 到服务端，且本地播放停止回调触发
        withTimeout(3000) {
            while (true) {
                if (receivedTypes.receive() == "response.cancel") break
            }
        }
        assertTrue("Barge-in 回调应至少触发 1 次", bargeInCount >= 1)
        assertTrue("本地播报停止回调应至少触发 1 次", localStopCount >= 1)

        // 模拟服务端返回用户插话实时转写，验证注入对话回调
        ws.send("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"帮我查明天的天气"}""")
        withTimeout(3000) {
            while (userTranscripts.isEmpty()) {
                delay(15)
            }
        }
        assertEquals(listOf("帮我查明天的天气"), userTranscripts)

        bridge.release()
    }
}

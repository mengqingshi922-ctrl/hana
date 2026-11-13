package com.hana.agent.voice

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RealtimeVoiceClientTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        httpClient.dispatcher.executorService.shutdownNow()
        httpClient.connectionPool.evictAll()
    }

    @Test
    fun testOpenAiRealtimeHandshakeAudioUploadAndDownlinkStreaming() = runBlocking<Unit> {
        val serverReceivedMessages = Channel<String>(20)
        var serverWs: WebSocket? = null

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    serverWs = webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverReceivedMessages.trySend(text)
                }
            })
        )

        val wsUrl = server.url("/v1/realtime").toString().replace("http://", "ws://")
        val config = RealtimeConfig(
            wsUrl = wsUrl,
            apiKey = "test-openai-key",
            model = "gpt-4o-realtime-preview",
            protocol = RealtimeProtocol.OPENAI_REALTIME,
            voice = "alloy",
        )
        val client = RealtimeVoiceClient(config, httpClient)
        val eventChannel = Channel<RealtimeDownlinkEvent>(10)
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { eventChannel.send(it) }
        }

        try {
            client.connect()

            // 1. 验证客户端自动发送 session.update 握手帧
            val handshakeMsg = withTimeout(3000) { serverReceivedMessages.receive() }
            val handshakeJson = Json.parseToJsonElement(handshakeMsg).jsonObject
            assertEquals("session.update", handshakeJson["type"]!!.jsonPrimitive.content)
            val session = handshakeJson["session"]!!.jsonObject
            assertEquals("alloy", session["voice"]!!.jsonPrimitive.content)
            assertEquals("pcm16", session["input_audio_format"]!!.jsonPrimitive.content)

            // 2. 验证 PCM 音频数据发送格式
            val pcmBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            val sendOk = client.sendAudioPcm16(pcmBytes)
            assertTrue(sendOk)

            val audioMsg = withTimeout(3000) { serverReceivedMessages.receive() }
            val audioJson = Json.parseToJsonElement(audioMsg).jsonObject
            assertEquals("input_audio_buffer.append", audioJson["type"]!!.jsonPrimitive.content)
            assertTrue(audioJson["audio"]!!.jsonPrimitive.content.isNotEmpty())

            // 3. 模拟服务端下发音频和转写增量帧
            val sws = serverWs
            assertNotNull(sws)
            sws?.send(
                buildJsonObject {
                    put("type", "response.audio.delta")
                    put("delta", "dGVzdC1hdWRpby1jaHVuaw==")
                }.toString()
            )
            sws?.send(
                buildJsonObject {
                    put("type", "response.audio_transcript.delta")
                    put("delta", "你好，我是 Hana")
                }.toString()
            )
            sws?.send(
                buildJsonObject {
                    put("type", "response.done")
                }.toString()
            )

            val e1 = withTimeout(3000) { eventChannel.receive() }
            assertTrue(e1 is RealtimeDownlinkEvent.AudioChunk)
            assertEquals("dGVzdC1hdWRpby1jaHVuaw==", (e1 as RealtimeDownlinkEvent.AudioChunk).pcmBase64)

            val e2 = withTimeout(3000) { eventChannel.receive() }
            assertTrue(e2 is RealtimeDownlinkEvent.TranscriptDelta)
            assertEquals("你好，我是 Hana", (e2 as RealtimeDownlinkEvent.TranscriptDelta).text)

            val e3 = withTimeout(3000) { eventChannel.receive() }
            assertTrue(e3 is RealtimeDownlinkEvent.ResponseDone)
        } finally {
            collectJob.cancel()
            client.close()
            serverWs?.close(1000, "test done")
        }
    }

    @Test
    fun testBargeInCancelSendsCancelFrameAndClearsAudioBuffer() = runBlocking<Unit> {
        val serverReceivedMessages = Channel<String>(20)
        var serverWs: WebSocket? = null

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    serverWs = webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverReceivedMessages.trySend(text)
                }
            })
        )

        val wsUrl = server.url("/v1/realtime").toString().replace("http://", "ws://")
        val client = RealtimeVoiceClient(
            RealtimeConfig(wsUrl = wsUrl, protocol = RealtimeProtocol.OPENAI_REALTIME),
            httpClient,
        )
        val eventChannel = Channel<RealtimeDownlinkEvent>(5)
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { eventChannel.send(it) }
        }

        try {
            client.connect()
            // 消费握手帧
            withTimeout(3000) { serverReceivedMessages.receive() }

            // 模拟收到音频填充缓冲
            val sws = serverWs
            assertNotNull(sws)
            sws?.send(
                buildJsonObject {
                    put("type", "response.audio.delta")
                    put("delta", "YnVmZmVyZWRfcGNt")
                }.toString()
            )

            // 确认缓冲内存在待播音频
            val firstAudio = withTimeout(3000) { eventChannel.receive() }
            assertTrue(firstAudio is RealtimeDownlinkEvent.AudioChunk)

            // 执行 Barge-in 打断
            val cancelSent = client.bargeInCancel()
            assertTrue(cancelSent)

            // 验证服务端收到 response.cancel 帧
            val cancelMsg = withTimeout(3000) { serverReceivedMessages.receive() }
            val cancelJson = Json.parseToJsonElement(cancelMsg).jsonObject
            assertEquals("response.cancel", cancelJson["type"]!!.jsonPrimitive.content)

            // 验证下行缓冲已被清空
            assertNull(client.pollNextAudioChunk())

            // 验证产生 Interrupted 事件
            val interruptedEvent = withTimeout(3000) { eventChannel.receive() }
            assertEquals(RealtimeDownlinkEvent.Interrupted, interruptedEvent)

            // 验证 bindVad 联动机制
            val vadHandler = client.bindVad(VoiceActivityDetector())
            vadHandler(VadEvent.BargeIn(timestampMs = 1000L, energy = 0.5f))
            val cancelMsg2 = withTimeout(3000) { serverReceivedMessages.receive() }
            assertEquals("response.cancel", Json.parseToJsonElement(cancelMsg2).jsonObject["type"]!!.jsonPrimitive.content)
        } finally {
            collectJob.cancel()
            client.close()
            serverWs?.close(1000, "test done")
        }
    }

    @Test
    fun testGeminiLiveProtocolHandshakeAndDownlinkEvents() = runBlocking<Unit> {
        val serverReceivedMessages = Channel<String>(20)
        var serverWs: WebSocket? = null

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    serverWs = webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverReceivedMessages.trySend(text)
                }
            })
        )

        val wsUrl = server.url("/gemini/live").toString().replace("http://", "ws://")
        val config = RealtimeConfig(
            wsUrl = wsUrl,
            apiKey = "gemini-key",
            model = "gemini-2.0-flash-exp",
            protocol = RealtimeProtocol.GEMINI_LIVE,
        )
        val client = RealtimeVoiceClient(config, httpClient)
        val eventChannel = Channel<RealtimeDownlinkEvent>(5)
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { eventChannel.send(it) }
        }

        try {
            client.connect()

            // 验证 Gemini Live 的 setup 握手帧
            val setupMsg = withTimeout(3000) { serverReceivedMessages.receive() }
            val setupJson = Json.parseToJsonElement(setupMsg).jsonObject
            assertTrue("setup" in setupJson)
            val setupObj = setupJson["setup"]!!.jsonObject
            assertEquals("models/gemini-2.0-flash-exp", setupObj["model"]!!.jsonPrimitive.content)

            // 验证 Gemini Live 音频帧上行
            client.sendAudioPcm16(byteArrayOf(10, 20, 30))
            val mediaMsg = withTimeout(3000) { serverReceivedMessages.receive() }
            val mediaJson = Json.parseToJsonElement(mediaMsg).jsonObject
            assertTrue("realtimeInput" in mediaJson)
            val mediaChunks = mediaJson["realtimeInput"]!!.jsonObject["mediaChunks"]!!.jsonArray
            assertEquals("audio/pcm;rate=16000", mediaChunks[0].jsonObject["mimeType"]!!.jsonPrimitive.content)

            // 服务端下发 Gemini Live 响应
            val sws = serverWs
            assertNotNull(sws)
            val geminiResponse = buildJsonObject {
                put(
                    "serverContent",
                    buildJsonObject {
                        put(
                            "modelTurn",
                            buildJsonObject {
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "inlineData",
                                                    buildJsonObject {
                                                        put("mimeType", "audio/pcm;rate=24000")
                                                        put("data", "Z2VtaW5pLWF1ZGlv")
                                                    },
                                                )
                                            }
                                        )
                                        add(buildJsonObject { put("text", "Gemini text chunk") })
                                    },
                                )
                            },
                        )
                        put("turnComplete", true)
                    },
                )
            }
            sws?.send(geminiResponse.toString())

            val audioEvent = withTimeout(3000) { eventChannel.receive() }
            assertTrue(audioEvent is RealtimeDownlinkEvent.AudioChunk)
            assertEquals("Z2VtaW5pLWF1ZGlv", (audioEvent as RealtimeDownlinkEvent.AudioChunk).pcmBase64)

            val textEvent = withTimeout(3000) { eventChannel.receive() }
            assertTrue(textEvent is RealtimeDownlinkEvent.TranscriptDelta)
            assertEquals("Gemini text chunk", (textEvent as RealtimeDownlinkEvent.TranscriptDelta).text)

            val doneEvent = withTimeout(3000) { eventChannel.receive() }
            assertEquals(RealtimeDownlinkEvent.ResponseDone, doneEvent)
        } finally {
            collectJob.cancel()
            client.close()
            serverWs?.close(1000, "test done")
        }
    }
}

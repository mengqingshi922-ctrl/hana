package com.hana.agent.wechat

import com.hana.agent.core.wechat.WechatAccount
import com.hana.agent.core.wechat.WechatApi
import com.hana.agent.core.wechat.WechatImage
import com.hana.agent.core.wechat.WechatItem
import com.hana.agent.core.wechat.WechatMessage
import com.hana.agent.core.wechat.WechatText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 验证微信通道的图片收发能力:
 * 1. WechatImageItem / WechatMessage 正常解析图片 URL 与 payload;
 * 2. WechatApi.sendImage 正常组装并发送 type = 2 的图片消息请求;
 * 3. 混合消息同时提取文字与图片。
 */
class WechatImageTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun testParseImageMessage() {
        val raw = """
            {
                "message_id": "msg_img_1001",
                "from_user_id": "user_wechat_1",
                "to_user_id": "bot_id",
                "message_type": 1,
                "item_list": [
                    {
                        "type": 1,
                        "text_item": { "text": "请看这张照片" }
                    },
                    {
                        "type": 2,
                        "image_item": {
                            "url": "https://example.com/images/test.jpg"
                        }
                    }
                ]
            }
        """.trimIndent()

        val msg = json.decodeFromString<WechatMessage>(raw)
        assertEquals("msg_img_1001", msg.messageId)
        assertEquals("请看这张照片", msg.userText())
        val images = msg.userImageUrls()
        assertEquals(1, images.size)
        assertEquals("https://example.com/images/test.jpg", images.first())
    }

    @Test
    fun testPureImageMessage() {
        val raw = """
            {
                "message_id": "msg_img_pure",
                "from_user_id": "user_wechat_2",
                "message_type": 1,
                "item_list": [
                    {
                        "type": 2,
                        "image_item": {
                            "image_url": "https://example.com/cdn/screenshot.png"
                        }
                    }
                ]
            }
        """.trimIndent()

        val msg = json.decodeFromString<WechatMessage>(raw)
        assertEquals(null, msg.userText())
        val images = msg.userImageUrls()
        assertEquals(listOf("https://example.com/cdn/screenshot.png"), images)
    }

    @Test
    fun testSendImageRequest() {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val api = WechatApi(OkHttpClient(), "0.1.35", server.url("/").toString())
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ret":0}"""))

            val account = WechatAccount("test-token", server.url("/").toString(), "bot-1", "owner-1")
            api.sendImage(account, "user-target", "client-delivery-1", "https://img.sample/pic.jpg", "ctx-token-abc")

            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertEquals("/ilink/bot/sendmessage", recorded?.path)

            val body = json.parseToJsonElement(recorded!!.body.readUtf8()).jsonObject
            val msg = body["msg"]!!.jsonObject
            assertEquals("user-target", msg["to_user_id"]?.jsonPrimitive?.content)
            assertEquals("client-delivery-1", msg["client_id"]?.jsonPrimitive?.content)
            assertEquals("ctx-token-abc", msg["context_token"]?.jsonPrimitive?.content)

            val items = msg["item_list"]!!.jsonArray
            assertEquals(1, items.size)
            val item = items[0].jsonObject
            assertEquals(2, item["type"]?.jsonPrimitive?.content?.toInt())
            val imageItem = item["image_item"]?.jsonObject
            assertEquals("https://img.sample/pic.jpg", imageItem?.get("url")?.jsonPrimitive?.content)
        }
    }
}

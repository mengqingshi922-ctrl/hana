package com.hana.agent.mcp

import com.hana.agent.core.action.ActionRisk
import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.cap.mcp.McpConnection
import com.hana.agent.core.cap.mcp.McpTrust
import com.hana.agent.core.mcp.McpElicitationAction
import com.hana.agent.core.mcp.McpElicitationResponse
import com.hana.agent.core.mcp.McpRpc
import com.hana.agent.core.mcp.McpTaskStatus
import com.hana.agent.core.model.Part
import com.hana.agent.core.uistate.ActionResult
import com.hana.agent.core.uistate.ScreenPort
import com.hana.agent.core.uistate.ScreenSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class HttpMcpEndpointTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun modern2026DiscoverPaginatedToolsAndCallIntegratesWithMcpClient() = runBlocking {
        val seenHeaders = CopyOnWriteArrayList<Pair<String?, String?>>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer static-secret-2026") {
                    return MockResponse().setResponseCode(401)
                }
                val body = request.body.readUtf8()
                val obj = Json.parseToJsonElement(body).jsonObject
                val id = obj["id"]!!
                val method = obj["method"]!!.jsonPrimitive.content
                seenHeaders += request.getHeader("MCP-Protocol-Version") to request.getHeader("Mcp-Method")

                return when (method) {
                    "server/discover" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","supportedVersions":["${McpRpc.MODERN}"],"capabilities":{"tools":{"listChanged":false}}}}""",
                        )
                    "tools/list" -> {
                        val cursor = obj["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content
                        if (cursor == null) {
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"search_docs","description":"Search docs","inputSchema":{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]},"annotations":{"readOnlyHint":true}}],"nextCursor":"page2"}}""",
                                )
                        } else {
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"delete_item","description":"Delete item","inputSchema":{"type":"object"},"annotations":{"destructiveHint":true}}]}}""",
                                )
                        }
                    }
                    "tools/call" -> {
                        assertEquals("search_docs", request.getHeader("Mcp-Name"))
                        val q = obj["params"]!!.jsonObject["arguments"]!!.jsonObject["q"]!!.jsonPrimitive.content
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"result for $q"}],"isError":false}}""",
                            )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val endpoint = HttpMcpEndpoint(
            initialConfig = McpHttpServerConfig(
                serverId = "docs",
                endpointUrl = server.url("/mcp").toString(),
                protocolMode = McpProtocolMode.AUTO,
                oauth = McpOAuth2Config(staticBearerToken = "static-secret-2026"),
            ),
        )

        val session = endpoint.connect()
        assertTrue(session.isModern2026)
        assertEquals(McpRpc.MODERN, session.protocolVersion)

        val tools = endpoint.tools()
        assertEquals(listOf("search_docs", "delete_item"), tools.map { it.name })

        // 验证可无缝接入 :core 的 McpConnection + ToolDispatcher（含注解风险映射）
        val dummyScreen = object : ScreenPort {
            override suspend fun snapshot() = ScreenSnapshot("s1", "com.test", emptyList(), 1080, 2400)
            override suspend fun screenshotPng(): ByteArray? = null
            override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok()
            override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok()
            override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
            override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
            override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
            override suspend fun back() = ActionResult.Ok()
            override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
            override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
            override suspend fun waitStable(timeoutMs: Long) = true
            override suspend fun listApps(): List<Pair<String, String>> = emptyList()
        }
        val dispatcher = toolDispatcher(
            dummyScreen,
            mcpServers = listOf(McpConnection(endpoint, trust = McpTrust.TrustAnnotations)),
        )
        assertEquals(ActionRisk.Read, dispatcher.approvalRisk("mcp_docs__search_docs")?.level)
        assertEquals(ActionRisk.Irreversible, dispatcher.approvalRisk("mcp_docs__delete_item")?.level)

        val exec = dispatcher.executeRead(Part.ToolCall("c1", "mcp_docs__search_docs", """{"q":"kotlin"}"""))
        assertTrue(exec.content["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(exec.content["text"].toString().contains("result for kotlin"))
        assertTrue(seenHeaders.any { it.first == McpRpc.MODERN && it.second == "tools/call" })
    }

    @Test
    fun streamableSseHandlesNotificationsInterleavedElicitationAndResult() = runBlocking {
        val notifications = CopyOnWriteArrayList<String>()
        val elicitationReplyBodies = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "DELETE") {
                    assertEquals("sess-xyz", request.getHeader("Mcp-Session-Id"))
                    return MockResponse().setResponseCode(200)
                }
                val body = request.body.readUtf8()
                val obj = Json.parseToJsonElement(body).jsonObject
                val id = obj["id"]
                val method = obj["method"]?.jsonPrimitive?.content

                // 客户端回传给服务端的 elicitation/create JSON-RPC 响应
                if (method == null && id?.jsonPrimitive?.content == "elicit-99") {
                    elicitationReplyBodies += body
                    return MockResponse().setResponseCode(202)
                }

                return when (method) {
                    "server/discover" -> MockResponse().setResponseCode(404)
                    "initialize" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setHeader("Mcp-Session-Id", "sess-xyz")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}}""")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"book_flight","description":"Book","inputSchema":{"type":"object"}}]}}""")
                    "tools/call" -> {
                        val sseBody = buildString {
                            append("id: evt-1\n")
                            append("event: message\n")
                            append("""data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":50,"total":100}}""")
                            append("\n\n")
                            append("id: evt-2\n")
                            append("event: message\n")
                            append("""data: {"jsonrpc":"2.0","id":"elicit-99","method":"elicitation/create","params":{"mode":"form","message":"Confirm seat preference","requestedSchema":{"type":"object","properties":{"seat":{"type":"string"}}}}}""")
                            append("\n\n")
                            append("id: evt-3\n")
                            append("event: message\n")
                            append("""data: {"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"Flight booked: window seat"}],"isError":false}}""")
                            append("\n\n")
                        }
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(sseBody)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val endpoint = HttpMcpEndpoint(
            initialConfig = McpHttpServerConfig(
                serverId = "travel",
                endpointUrl = server.url("/mcp").toString(),
                protocolMode = McpProtocolMode.AUTO,
            ),
            elicitationHandler = { req ->
                assertEquals("Confirm seat preference", req.message)
                McpElicitationResponse(
                    action = McpElicitationAction.ACCEPT,
                    content = buildJsonObject { put("seat", JsonPrimitive("window")) },
                )
            },
            onNotification = { method, _ -> notifications += method },
        )

        val session = endpoint.connect()
        assertFalse(session.isModern2026)
        assertEquals("sess-xyz", session.sessionId)

        val result = endpoint.call("book_flight", buildJsonObject { put("to", "TYO") })
        assertFalse(result.isError)
        assertEquals("Flight booked: window seat", result.text)
        assertEquals(listOf("notifications/progress"), notifications.toList())
        assertEquals(1, elicitationReplyBodies.size)
        val elicitResp = Json.parseToJsonElement(elicitationReplyBodies.single()).jsonObject["result"]!!.jsonObject
        assertEquals("accept", elicitResp["action"]!!.jsonPrimitive.content)
        assertEquals("window", elicitResp["content"]!!.jsonObject["seat"]!!.jsonPrimitive.content)

        endpoint.close()
        // 确认 close 发送了 DELETE 请求带 Mpc-Session-Id
        val requests = generateSequence { server.takeRequest(10, TimeUnit.MILLISECONDS) }.toList()
        assertTrue(requests.any { it.method == "DELETE" && it.getHeader("Mcp-Session-Id") == "sess-xyz" })
    }

    @Test
    fun oauth2TokenDiscoveryRefreshAndDynamicConfigUpdate() = runBlocking {
        var tokenIssuedCount = 0

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/.well-known/oauth-protected-resource" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"authorization_servers":["${server.url("").toString().trimEnd('/')}"]}""")
                    "/.well-known/oauth-authorization-server" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"token_endpoint":"${server.url("/oauth/token")}"}""")
                    "/oauth/token" -> {
                        val form = request.body.readUtf8()
                        assertTrue(form.contains("grant_type=client_credentials"))
                        assertTrue(form.contains("resource=https%3A%2F%2Fmcp.hana.local"))
                        tokenIssuedCount++
                        val tok = if (tokenIssuedCount == 1) "tok-v1" else "tok-v2"
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"access_token":"$tok","token_type":"Bearer","expires_in":3600}""")
                    }
                    "/mcp" -> {
                        val auth = request.getHeader("Authorization")
                        // 第一次携带 static/old token 返回 401 并附带 RFC 9728 resource_metadata，触发动态发现并刷新新 token
                        if (auth == "Bearer expired-tok") {
                            return MockResponse()
                                .setResponseCode(401)
                                .setHeader(
                                    "WWW-Authenticate",
                                    """Bearer resource_metadata="${server.url("/.well-known/oauth-protected-resource")}"""",
                                )
                        }
                        if (auth != "Bearer tok-v1" && auth != "Bearer tok-v2") {
                            return MockResponse().setResponseCode(401)
                        }
                        val obj = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val id = obj["id"]!!
                        when (obj["method"]!!.jsonPrimitive.content) {
                            "server/discover" -> MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","supportedVersions":["${McpRpc.MODERN}"],"capabilities":{}}}""")
                            "ping" -> MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete"}}""")
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                    "/mcp-v2" -> {
                        if (request.getHeader("Authorization") != "Bearer updated-static-tok") {
                            return MockResponse().setResponseCode(401)
                        }
                        val obj = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val id = obj["id"]!!
                        when (obj["method"]!!.jsonPrimitive.content) {
                            "server/discover" -> MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","supportedVersions":["${McpRpc.MODERN}"],"capabilities":{}}}""")
                            "ping" -> MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete"}}""")
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val endpoint = HttpMcpEndpoint(
            initialConfig = McpHttpServerConfig(
                serverId = "oauth-srv",
                endpointUrl = server.url("/mcp").toString(),
                protocolMode = McpProtocolMode.MODERN_2026,
                oauth = McpOAuth2Config(
                    staticBearerToken = "expired-tok",
                    clientId = "hana-app",
                    clientSecret = "secret",
                    resource = "https://mcp.hana.local",
                ),
            ),
        )

        // connect() 先用 expired-tok 收到 401 -> 解析 WWW-Authenticate -> RFC 9728/8414 发现 -> 拿 tok-v1 -> 成功！
        val session1 = endpoint.connect()
        assertNotNull(session1)
        assertEquals(1, tokenIssuedCount)
        endpoint.ping()
        assertEquals(1, tokenIssuedCount) // 命中缓存不重复请求 token

        // 动态热更新配置切换到 /mcp-v2 和新的 Bearer Token
        endpoint.updateConfig(
            endpoint.currentConfig.copy(
                endpointUrl = server.url("/mcp-v2").toString(),
                oauth = McpOAuth2Config(staticBearerToken = "updated-static-tok"),
            ),
        )
        endpoint.ping()
    }

    @Test
    fun tasksGetResultCancelAndTaskAugmentedToolCall() = runBlocking {
        var pollCount = 0

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val obj = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val id = obj["id"]!!
                val method = obj["method"]!!.jsonPrimitive.content
                return when (method) {
                    "server/discover" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","supportedVersions":["${McpRpc.MODERN}"],"capabilities":{"tasks":{}}}}""")
                    "tools/call" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"task":{"taskId":"task-88","status":"working","pollInterval":25}}}""")
                    "tasks/get" -> {
                        pollCount++
                        val status = if (pollCount >= 2) "completed" else "working"
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"taskId":"task-88","status":"$status","pollInterval":20}}""")
                    }
                    "tasks/result" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"async done"}],"isError":false}}""")
                    "tasks/cancel" -> {
                        val tid = obj["params"]!!.jsonObject["taskId"]!!.jsonPrimitive.content
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"taskId":"$tid","status":"cancelled"}}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val endpoint = HttpMcpEndpoint(
            initialConfig = McpHttpServerConfig(
                serverId = "async-srv",
                endpointUrl = server.url("/mcp").toString(),
                protocolMode = McpProtocolMode.MODERN_2026,
            ),
        )

        val result = endpoint.call("long_running_tool", buildJsonObject {})
        assertFalse(result.isError)
        assertEquals("async done", result.text)
        assertTrue(pollCount >= 2)

        val cancelled = endpoint.cancelTask("task-99")
        assertEquals("task-99", cancelled.taskId)
        assertEquals(McpTaskStatus.CANCELLED, cancelled.status)
    }
}

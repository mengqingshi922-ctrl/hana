package com.hana.agent.core.mcp

import com.hana.agent.core.agent.TaskState
import com.hana.agent.core.remote.HttpRequest
import com.hana.agent.core.remote.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class Mcp2026CodecTest {
    private val fake = FakeRemoteTasks()

    private fun post(rpc: McpRpc, body: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        rpc.handle(
            HttpRequest(
                method = "POST",
                path = "/mcp",
                headers = (mapOf("content-type" to "application/json", "accept" to "application/json, text/event-stream") + headers)
                    .mapKeys { it.key.lowercase() },
                body = body.toByteArray(),
                remote = InetAddress.getLoopbackAddress(),
            ),
        )

    private fun modernHeaders(method: String) =
        mapOf("MCP-Protocol-Version" to McpRpc.MODERN, "Mcp-Method" to method)

    private fun modernMeta(): JsonObject = buildJsonObject {
        put(McpRpc.META_VERSION, McpRpc.MODERN)
        putJsonObject(McpRpc.META_CLIENT) { put("name", "test-client"); put("version", "1.0") }
        putJsonObject(McpRpc.META_CAPABILITIES) {}
    }

    @Test
    fun tasksGetResultAndCancelRoundTripInModern2026() {
        val rpc = McpRpc(McpTaskTools(fake), "0.1.test")
        fake.start("check weather", null)
        fake.set("t1", TaskState.Running(step = 2, note = "Searching forecast"))

        // 1. tasks/get -> working
        val getReq = McpRpc.encodeTaskGetRequest(JsonPrimitive(1), "t1", modernMeta())
        val getResp = post(rpc, getReq.toString(), modernHeaders("tasks/get"))
        assertEquals(200, getResp.status)
        val getResult = Json.parseToJsonElement(getResp.body.toString(Charsets.UTF_8)).jsonObject["result"]!!.jsonObject
        val infoWorking = McpRpc.decodeTaskInfo(getResult)
        assertEquals("t1", infoWorking.taskId)
        assertEquals(McpTaskStatus.WORKING, infoWorking.status)
        assertEquals("Searching forecast", infoWorking.statusMessage)

        // 2. tasks/result while still working -> INVALID_PARAMS error
        val pendingResultReq = McpRpc.encodeTaskResultRequest(JsonPrimitive(2), "t1", modernMeta())
        val pendingResp = post(rpc, pendingResultReq.toString(), modernHeaders("tasks/result"))
        assertEquals(400, pendingResp.status)
        assertEquals(
            McpRpc.INVALID_PARAMS,
            Json.parseToJsonElement(pendingResp.body.toString(Charsets.UTF_8)).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int,
        )

        // 3. Task succeeds -> tasks/get is completed, tasks/result returns answer & related-task meta
        fake.set("t1", TaskState.Succeeded("Sunny 24C"))
        val doneGetResp = post(rpc, McpRpc.encodeTaskGetRequest(JsonPrimitive(3), "t1", modernMeta()).toString(), modernHeaders("tasks/get"))
        assertEquals(McpTaskStatus.COMPLETED, McpRpc.decodeTaskInfo(Json.parseToJsonElement(doneGetResp.body.toString(Charsets.UTF_8)).jsonObject["result"]!!.jsonObject).status)

        val resResp = post(rpc, McpRpc.encodeTaskResultRequest(JsonPrimitive(4), "t1", modernMeta()).toString(), modernHeaders("tasks/result"))
        assertEquals(200, resResp.status)
        val resObj = Json.parseToJsonElement(resResp.body.toString(Charsets.UTF_8)).jsonObject["result"]!!.jsonObject
        val decodedResult = McpRpc.decodeTaskCallResult(resObj)
        assertFalse(decodedResult.isError)
        assertEquals("Sunny 24C", decodedResult.text)
        assertEquals("t1", decodedResult.relatedTaskId)

        // 4. tasks/cancel on a running task
        fake.start("long job", null)
        fake.set("t2", TaskState.Running(1, "Working"))
        val cancelResp = post(rpc, McpRpc.encodeTaskCancelRequest(JsonPrimitive(5), "t2", modernMeta()).toString(), modernHeaders("tasks/cancel"))
        assertEquals(200, cancelResp.status)
        val cancelInfo = McpRpc.decodeTaskInfo(Json.parseToJsonElement(cancelResp.body.toString(Charsets.UTF_8)).jsonObject["result"]!!.jsonObject)
        assertEquals(McpTaskStatus.CANCELLED, cancelInfo.status)
    }

    @Test
    fun elicitationCreateCodecAndRpcDispatch() {
        val formReq = McpElicitationRequest(
            message = "Enter shipping city",
            mode = McpElicitationMode.FORM,
            requestedSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("city") { put("type", "string") }
                }
            },
        )
        val encodedForm = McpRpc.encodeElicitationCreateRequest(JsonPrimitive(10), formReq, modernMeta())
        val decodedForm = McpRpc.decodeElicitationCreateParams(encodedForm["params"]!!.jsonObject)
        assertEquals(McpElicitationMode.FORM, decodedForm.mode)
        assertEquals("Enter shipping city", decodedForm.message)
        assertNotNull(decodedForm.requestedSchema)

        val urlReq = McpElicitationRequest(
            message = "Authorize GitHub access",
            mode = McpElicitationMode.URL,
            url = "https://example.com/oauth/authorize",
            elicitationId = "elicit-42",
        )
        val encodedUrl = McpRpc.encodeElicitationCreateRequest(JsonPrimitive(11), urlReq)
        val decodedUrl = McpRpc.decodeElicitationCreateParams(encodedUrl["params"]!!.jsonObject)
        assertEquals(McpElicitationMode.URL, decodedUrl.mode)
        assertEquals("https://example.com/oauth/authorize", decodedUrl.url)
        assertEquals("elicit-42", decodedUrl.elicitationId)

        // RPC dispatch with elicitation handler
        val rpcWithHandler = McpRpc(
            tools = McpTaskTools(fake),
            serverVersion = "0.1.test",
            onRequest = { _, _ -> },
            onElicitation = { req ->
                assertEquals("Enter shipping city", req.message)
                McpElicitationResponse(
                    action = McpElicitationAction.ACCEPT,
                    content = buildJsonObject { put("city", "Shanghai") },
                )
            },
        )
        val resp = post(rpcWithHandler, encodedForm.toString(), modernHeaders("elicitation/create"))
        assertEquals(200, resp.status)
        val resultObj = Json.parseToJsonElement(resp.body.toString(Charsets.UTF_8)).jsonObject["result"]!!.jsonObject
        val elicitationResult = McpRpc.decodeElicitationResponse(resultObj)
        assertEquals(McpElicitationAction.ACCEPT, elicitationResult.action)
        assertEquals("Shanghai", elicitationResult.content?.get("city")?.jsonPrimitive?.content)

        // Without handler -> METHOD_NOT_FOUND
        val rpcNoHandler = McpRpc(McpTaskTools(fake), "0.1.test")
        val noHandlerResp = post(rpcNoHandler, encodedForm.toString(), modernHeaders("elicitation/create"))
        assertEquals(404, noHandlerResp.status)
    }

    @Test
    fun legacyTasksGetWorksAndCapabilitiesAdvertiseTasks() {
        val rpc = McpRpc(McpTaskTools(fake), "0.1.test")
        val init = post(
            rpc,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{}}}""",
        )
        assertEquals(200, init.status)
        val caps = Json.parseToJsonElement(init.body.toString(Charsets.UTF_8)).jsonObject["result"]!!.jsonObject["capabilities"]!!.jsonObject
        assertTrue(caps.containsKey("tasks"))

        fake.start("legacy task", null)
        fake.set("t1", TaskState.WaitingInput("Pick size", listOf("M", "L")))
        val getResp = post(rpc, """{"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"taskId":"t1"}}""")
        assertEquals(200, getResp.status)
        val info = McpRpc.decodeTaskInfo(Json.parseToJsonElement(getResp.body.toString(Charsets.UTF_8)).jsonObject["result"]!!.jsonObject)
        assertEquals(McpTaskStatus.INPUT_REQUIRED, info.status)
        assertTrue(info.statusMessage?.contains("Pick size") == true)
    }
}

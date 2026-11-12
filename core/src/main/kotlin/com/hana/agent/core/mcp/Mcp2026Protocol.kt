package com.hana.agent.core.mcp

import com.hana.agent.core.remote.RemoteStatus
import com.hana.agent.core.remote.RemoteTask
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * MCP 2026 Tasks 与 Elicitation 协议消息模型与 JSON-RPC 编解码。
 */
enum class McpTaskStatus(val wire: String) {
    WORKING("working"),
    INPUT_REQUIRED("input_required"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    companion object {
        fun fromWire(value: String): McpTaskStatus? = entries.firstOrNull { it.wire == value }

        fun fromRemoteStatus(status: String): McpTaskStatus = when (status) {
            RemoteStatus.STARTING, RemoteStatus.RUNNING, RemoteStatus.QUEUED -> WORKING
            RemoteStatus.PAUSED, RemoteStatus.WAITING_FOR_ANSWER, RemoteStatus.WAITING_ON_PHONE -> INPUT_REQUIRED
            RemoteStatus.DONE -> COMPLETED
            RemoteStatus.FAILED -> FAILED
            RemoteStatus.CANCELLED -> CANCELLED
            else -> WORKING
        }
    }
}

data class McpTaskInfo(
    val taskId: String,
    val status: McpTaskStatus,
    val statusMessage: String? = null,
    val createdAt: String? = null,
    val lastUpdatedAt: String? = null,
    val ttlMs: Long? = null,
    val pollIntervalMs: Long? = null,
)

data class McpTaskCallResult(
    val text: String,
    val content: List<JsonObject> = emptyList(),
    val structuredContent: JsonObject? = null,
    val isError: Boolean = false,
    val relatedTaskId: String? = null,
)

enum class McpElicitationMode(val wire: String) {
    FORM("form"),
    URL("url");

    companion object {
        fun fromWire(value: String): McpElicitationMode? = entries.firstOrNull { it.wire == value }
    }
}

enum class McpElicitationAction(val wire: String) {
    ACCEPT("accept"),
    DECLINE("decline"),
    CANCEL("cancel");

    companion object {
        fun fromWire(value: String): McpElicitationAction? = entries.firstOrNull { it.wire == value }
    }
}

data class McpElicitationRequest(
    val message: String,
    val mode: McpElicitationMode = McpElicitationMode.FORM,
    val requestedSchema: JsonObject? = null,
    val url: String? = null,
    val elicitationId: String? = null,
)

data class McpElicitationResponse(
    val action: McpElicitationAction,
    val content: JsonObject? = null,
)

object Mcp2026Codec {
    const val METHOD_TASKS_GET = "tasks/get"
    const val METHOD_TASKS_RESULT = "tasks/result"
    const val METHOD_TASKS_CANCEL = "tasks/cancel"
    const val METHOD_ELICITATION_CREATE = "elicitation/create"
    const val META_RELATED_TASK = "io.modelcontextprotocol/related-task"

    fun encodeTaskGetRequest(id: JsonElement, taskId: String, meta: JsonObject? = null): JsonObject =
        jsonRpcRequest(id, METHOD_TASKS_GET, buildJsonObject {
            put("taskId", taskId)
            meta?.let { put("_meta", it) }
        })

    fun encodeTaskResultRequest(id: JsonElement, taskId: String, meta: JsonObject? = null): JsonObject =
        jsonRpcRequest(id, METHOD_TASKS_RESULT, buildJsonObject {
            put("taskId", taskId)
            meta?.let { put("_meta", it) }
        })

    fun encodeTaskCancelRequest(id: JsonElement, taskId: String, meta: JsonObject? = null): JsonObject =
        jsonRpcRequest(id, METHOD_TASKS_CANCEL, buildJsonObject {
            put("taskId", taskId)
            meta?.let { put("_meta", it) }
        })

    fun decodeTaskIdParams(params: JsonObject): String {
        val raw = (params["taskId"] ?: params["task_id"]) as? JsonPrimitive
        val id = raw?.takeIf { it.isString }?.contentOrNull?.trim()
        require(!id.isNullOrEmpty()) { "params.taskId must be a non-empty string" }
        return id
    }

    fun encodeTaskInfo(info: McpTaskInfo): JsonObject = buildJsonObject {
        put("taskId", info.taskId)
        put("status", info.status.wire)
        info.statusMessage?.let { put("statusMessage", it) }
        info.createdAt?.let { put("createdAt", it) }
        info.lastUpdatedAt?.let { put("lastUpdatedAt", it) }
        info.ttlMs?.let { put("ttl", it) }
        info.pollIntervalMs?.let { put("pollInterval", it) }
    }

    fun decodeTaskInfo(obj: JsonObject): McpTaskInfo {
        val taskId = (obj["taskId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
            ?: error("taskId is required")
        val statusRaw = (obj["status"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("status is required")
        val status = McpTaskStatus.fromWire(statusRaw)
            ?: error("Unknown MCP task status: $statusRaw")
        val statusMessage = (obj["statusMessage"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val createdAt = (obj["createdAt"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val lastUpdatedAt = (obj["lastUpdatedAt"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val ttlMs = (obj["ttl"] as? JsonPrimitive)?.longOrNull
        val pollIntervalMs = (obj["pollInterval"] as? JsonPrimitive)?.longOrNull
        return McpTaskInfo(
            taskId = taskId,
            status = status,
            statusMessage = statusMessage,
            createdAt = createdAt,
            lastUpdatedAt = lastUpdatedAt,
            ttlMs = ttlMs,
            pollIntervalMs = pollIntervalMs,
        )
    }

    fun encodeTaskCallResult(result: McpTaskCallResult): JsonObject = buildJsonObject {
        if (result.content.isNotEmpty()) {
            putJsonArray("content") { result.content.forEach { add(it) } }
        } else {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", result.text)
                })
            }
        }
        result.structuredContent?.let { put("structuredContent", it) }
        put("isError", result.isError)
        result.relatedTaskId?.let { tid ->
            putJsonObject("_meta") {
                putJsonObject(META_RELATED_TASK) { put("taskId", tid) }
            }
        }
    }

    fun decodeTaskCallResult(obj: JsonObject): McpTaskCallResult {
        val contentArr = (obj["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val text = contentArr
            .mapNotNull { item ->
                if ((item["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                    (item["text"] as? JsonPrimitive)?.contentOrNull
                } else null
            }
            .joinToString("\n")
            .ifEmpty { (obj["structuredContent"] as? JsonObject)?.toString().orEmpty() }
        val structured = obj["structuredContent"] as? JsonObject
        val isError = (obj["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        val related = ((obj["_meta"] as? JsonObject)?.get(META_RELATED_TASK) as? JsonObject)
            ?.get("taskId")?.let { (it as? JsonPrimitive)?.contentOrNull }
        return McpTaskCallResult(
            text = text,
            content = contentArr,
            structuredContent = structured,
            isError = isError,
            relatedTaskId = related,
        )
    }

    fun encodeElicitationCreateRequest(
        id: JsonElement,
        request: McpElicitationRequest,
        meta: JsonObject? = null,
    ): JsonObject = jsonRpcRequest(id, METHOD_ELICITATION_CREATE, encodeElicitationCreateParams(request, meta))

    fun encodeElicitationCreateParams(request: McpElicitationRequest, meta: JsonObject? = null): JsonObject = buildJsonObject {
        put("mode", request.mode.wire)
        put("message", request.message)
        when (request.mode) {
            McpElicitationMode.FORM -> put("requestedSchema", request.requestedSchema ?: JsonObject(emptyMap()))
            McpElicitationMode.URL -> {
                request.url?.let { put("url", it) }
                request.elicitationId?.let { put("elicitationId", it) }
            }
        }
        meta?.let { put("_meta", it) }
    }

    fun decodeElicitationCreateParams(params: JsonObject): McpElicitationRequest {
        val message = (params["message"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        require(!message.isNullOrEmpty()) { "params.message must be a non-empty string" }

        val modeRaw = (params["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val mode = if (modeRaw == null) McpElicitationMode.FORM
        else McpElicitationMode.fromWire(modeRaw) ?: throw IllegalArgumentException("Unsupported elicitation mode: $modeRaw")

        return when (mode) {
            McpElicitationMode.FORM -> {
                val schema = params["requestedSchema"] as? JsonObject
                    ?: throw IllegalArgumentException("params.requestedSchema must be an object for form elicitation")
                McpElicitationRequest(
                    message = message,
                    mode = McpElicitationMode.FORM,
                    requestedSchema = schema,
                )
            }
            McpElicitationMode.URL -> {
                val url = (params["url"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                val elicitationId = (params["elicitationId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                require(!url.isNullOrEmpty()) { "params.url must be a non-empty string for url elicitation" }
                require(!elicitationId.isNullOrEmpty()) { "params.elicitationId must be a non-empty string for url elicitation" }
                McpElicitationRequest(
                    message = message,
                    mode = McpElicitationMode.URL,
                    url = url,
                    elicitationId = elicitationId,
                )
            }
        }
    }

    fun encodeElicitationResponse(response: McpElicitationResponse): JsonObject = buildJsonObject {
        put("action", response.action.wire)
        if (response.content != null && response.action == McpElicitationAction.ACCEPT) {
            put("content", response.content)
        }
    }

    fun decodeElicitationResponse(obj: JsonObject): McpElicitationResponse {
        val actionRaw = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw IllegalArgumentException("elicitation response.action is required")
        val action = McpElicitationAction.fromWire(actionRaw)
            ?: throw IllegalArgumentException("Unsupported elicitation action: $actionRaw")
        val content = obj["content"]?.takeIf { it !is JsonNull } as? JsonObject
        return McpElicitationResponse(action = action, content = content)
    }

    internal fun toTaskInfo(task: RemoteTask, statusOverride: String? = null): McpTaskInfo {
        val remoteStatus = statusOverride ?: RemoteStatus.of(task.state)
        val mcpStatus = McpTaskStatus.fromRemoteStatus(remoteStatus)
        val described = RemoteStatus.describe(task)
        val statusMsg = (described["progress"] as? JsonPrimitive)?.contentOrNull
            ?: (described["question"] as? JsonPrimitive)?.contentOrNull
            ?: (described["reason"] as? JsonPrimitive)?.contentOrNull
            ?: (described["answer"] as? JsonPrimitive)?.contentOrNull
            ?: remoteStatus
        val createdIso = Instant.ofEpochMilli(task.createdAtMs).toString()
        return McpTaskInfo(
            taskId = task.id,
            status = mcpStatus,
            statusMessage = statusMsg,
            createdAt = createdIso,
            lastUpdatedAt = Instant.now().toString(),
            pollIntervalMs = if (mcpStatus.isTerminal) null else 500L,
        )
    }

    private fun jsonRpcRequest(id: JsonElement, method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }
}

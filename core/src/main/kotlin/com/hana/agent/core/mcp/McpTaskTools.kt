package com.hana.agent.core.mcp

import com.hana.agent.core.remote.RemoteStatus
import com.hana.agent.core.remote.RemoteTasks
import com.hana.agent.core.remote.RemoteTask
import com.hana.agent.core.remote.RemoteTaskService
import com.hana.agent.core.remote.RemoteTaskError
import com.hana.agent.core.device.DeviceAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 电脑能用的工具:只给高层的「把事交给 Hana」,具体怎么做由手机上的 Hana 自己决定。 */
class McpTaskTools(
    private val service: RemoteTaskService,
    device: DeviceAccess? = null,
) {
    constructor(tasks: RemoteTasks, pollMs: Long = 250, device: DeviceAccess? = null) :
        this(RemoteTaskService(tasks, pollMs), device)

    private val deviceTools = device?.let(::McpDeviceTools)
    class ToolError(message: String) : Exception(message)

    data class Tool(val name: String, val description: String, val schema: JsonObject, val readOnly: Boolean)

    val tools: List<Tool> = listOf(
        Tool(RUN, "Hand Hana a task to do on the Android phone. Hana runs its own agent on the phone (apps, web, files) " +
            "and records the task in a conversation the user can see in the app. Returns a task_id right away; " +
            "follow it with hana_wait_task. Payments and confirmations are never approved over MCP.",
            schema(required = listOf("goal")) {
                putJsonObject("goal") { put("type", "string"); put("description", "What to get done, in plain words, as you would tell a person.") }
                putJsonObject("conversation") {
                    put("type", "string")
                    put("description", "Optional: continue a conversation returned by an earlier task so Hana keeps its context.")
                }
            }, readOnly = false),
        Tool(STATUS, "Current state of a task: status, latest progress note, the question it is waiting on, or the final answer.",
            schema(required = listOf("task_id")) { taskIdProp() }, readOnly = true),
        Tool(WAIT, "Wait until the task's status changes or it finishes, up to timeout_s seconds " +
            "(default $DEFAULT_WAIT_S, at most $MAX_WAIT_S), then return its state. Call again to keep waiting.",
            schema(required = listOf("task_id")) {
                taskIdProp()
                putJsonObject("timeout_s") {
                    put("type", "integer"); put("minimum", 1); put("maximum", MAX_WAIT_S)
                    put("description", "Longest wait in seconds.")
                }
            }, readOnly = true),
        Tool(ANSWER, "Answer the question Hana asked in this task (status waiting_for_answer). " +
            "Cannot approve payments, confirmations or steps handed to the user; those stay on the phone.",
            schema(required = listOf("task_id", "text")) {
                taskIdProp()
                putJsonObject("text") { put("type", "string"); put("description", "The answer.") }
            }, readOnly = false),
        Tool(STEER, "Add instructions to a running remote task, keeping its task_id and context. " +
            "Accepted means queued, not executed; check steers in hana_task_status for the message_id. " +
            "Does not answer questions, approve payments, resume paused tasks, or start a new task.",
            schema(required = listOf("task_id", "text")) {
                taskIdProp()
                putJsonObject("text") { put("type", "string"); put("minLength", 1); put("maxLength", RemoteTaskService.MAX_STEER) }
            }, readOnly = false),
        Tool(CANCEL, "Stop a remote task.",
            schema(required = listOf("task_id")) { taskIdProp() }, readOnly = false),
        Tool(LIST, "Recent remote tasks, newest first.",
            schema(required = emptyList()) {
                putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", MAX_LIST); put("description", "How many (default $DEFAULT_LIST).") }
            }, readOnly = true),
    ) + deviceTools?.tools.orEmpty()

    /** MCP 的工具定义(tools/list 的一项)。 */
    fun definitions(): JsonArray = buildJsonArray {
        tools.forEach { t ->
            add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("inputSchema", t.schema)
                putJsonObject("annotations") {
                    put("readOnlyHint", t.readOnly)
                    if (!t.readOnly) put("destructiveHint", t.name != ANSWER)
                    put("openWorldHint", t.name == RUN || t.name == STEER)
                }
            })
        }
    }

    fun has(name: String) = tools.any { it.name == name }

    /** MCP 2026 `tasks/get`：查询任务当前状态并映射为标准 MCP Task 载荷。 */
    internal fun getMcpTask(taskId: String): McpTaskInfo = try {
        Mcp2026Codec.toTaskInfo(service.get(taskId))
    } catch (e: RemoteTaskError) {
        throw ToolError(mapRemoteError(e))
    }

    /** MCP 2026 `tasks/cancel`：取消指定任务并返回最新状态。 */
    internal fun cancelMcpTask(taskId: String): McpTaskInfo = try {
        val cancellation = service.cancel(taskId)
        val task = service.get(taskId)
        val statusOverride = if (cancellation.accepted) RemoteStatus.CANCELLED else cancellation.status
        Mcp2026Codec.toTaskInfo(task, statusOverride = statusOverride)
    } catch (e: RemoteTaskError) {
        throw ToolError(mapRemoteError(e))
    }

    /** MCP 2026 `tasks/result`：获取已完成任务结果；若传 [waitMs] > 0 则先有界等待。 */
    internal suspend fun getMcpTaskResult(taskId: String, waitMs: Long = 0L): JsonObject = try {
        var task = service.get(taskId)
        var status = RemoteStatus.of(task.state)
        if (!RemoteStatus.finished(status) && waitMs > 0L) {
            val sec = ((waitMs + 999L) / 1000L).toInt().coerceIn(1, MAX_WAIT_S)
            task = service.wait(taskId, sec).task
            status = RemoteStatus.of(task.state)
        }
        if (!RemoteStatus.finished(status)) {
            val mcpStatus = McpTaskStatus.fromRemoteStatus(status).wire
            throw ToolError("Task $taskId is not completed yet (status: $mcpStatus)")
        }
        val structured = describe(task)
        val text = when (status) {
            RemoteStatus.DONE -> (structured["answer"] as? JsonPrimitive)?.contentOrNull ?: structured.toString()
            RemoteStatus.FAILED -> (structured["reason"] as? JsonPrimitive)?.contentOrNull ?: "Task failed"
            RemoteStatus.CANCELLED -> "Task cancelled"
            else -> structured.toString()
        }
        Mcp2026Codec.encodeTaskCallResult(
            McpTaskCallResult(
                text = text,
                structuredContent = structured,
                isError = status != RemoteStatus.DONE,
                relatedTaskId = task.id,
            ),
        )
    } catch (e: RemoteTaskError) {
        throw ToolError(mapRemoteError(e))
    }

    private fun mapRemoteError(e: RemoteTaskError): String = when (e) {
        is RemoteTaskError.NoSuchTask -> "No MCP task with id ${e.id}"
        is RemoteTaskError.BadConversation -> "conversation must be one returned by an earlier MCP task (looks like mcp-<uuid>)"
        is RemoteTaskError.NeedsPhone -> ON_PHONE_NOTE
        is RemoteTaskError.QuestionClosed -> "The question is no longer open; check hana_task_status."
        is RemoteTaskError.General -> e.message.orEmpty()
    }

    /** 只做协议参数转换和 MCP 展示;业务规则由两个入口共用的 service 执行。 */
    suspend fun call(name: String, args: JsonObject): JsonObject = try {
        when (name) {
            RUN -> describe(service.start(string(args, "goal").orEmpty(), optString(args, "conversation")))
            STATUS -> describe(service.get(taskId(args)))
            WAIT -> service.wait(taskId(args), optInt(args, "timeout_s") ?: DEFAULT_WAIT_S).let { r ->
                buildJsonObject {
                    describe(r.task).forEach { (k, v) -> put(k, v) }
                    put("changed", r.changed)
                }
            }
            ANSWER -> buildJsonObject {
                put("task_id", service.answer(taskId(args), string(args, "text").orEmpty()))
                put("accepted", true)
            }
            STEER -> service.steer(taskId(args), string(args, "text").orEmpty()).let { r -> buildJsonObject {
                put("task_id", r.taskId); put("message_id", r.messageId); put("accepted", true)
            } }
            CANCEL -> service.cancel(taskId(args)).let { r -> buildJsonObject {
                put("task_id", r.taskId); put("cancelled", r.accepted)
                r.status?.let { put("status", it) }
            } }
            LIST -> buildJsonObject {
                putJsonArray("tasks") {
                    service.list(optInt(args, "limit") ?: DEFAULT_LIST).forEach { t ->
                        add(buildJsonObject {
                            put("task_id", t.id); put("status", RemoteStatus.of(t.state)); put("goal", t.goal)
                            put("conversation", t.conversation); put("started_at_ms", t.createdAtMs)
                        })
                    }
                }
            }
            else -> if (deviceTools?.tools?.any { it.name == name } == true) deviceTools.call(name, args)
                else throw ToolError("Unknown tool: $name")
        }
    } catch (e: RemoteTaskError) {
        // 按错误类型换回原 MCP 客户端看到的说法(带 MCP 工具名);共用服务不包含 MCP 命令名。
        throw ToolError(when (e) {
            is RemoteTaskError.NoSuchTask -> "No MCP task with id ${e.id}"
            is RemoteTaskError.BadConversation -> "conversation must be one returned by an earlier MCP task (looks like mcp-<uuid>)"
            is RemoteTaskError.NeedsPhone -> ON_PHONE_NOTE
            is RemoteTaskError.QuestionClosed -> "The question is no longer open; check hana_task_status."
            is RemoteTaskError.General -> e.message.orEmpty()
        })
    }

    private fun taskId(args: JsonObject) = string(args, "task_id").orEmpty()

    private fun describe(task: RemoteTask): JsonObject = buildJsonObject {
        RemoteStatus.describe(task).forEach { (k, v) -> put(k, v) }
        when (RemoteStatus.of(task.state)) {
            RemoteStatus.WAITING_ON_PHONE -> put("note", ON_PHONE_NOTE)
            RemoteStatus.WAITING_FOR_ANSWER -> put("note", "Hana is asking a question. Reply with hana_answer; the user can also answer on the phone.")
        }
    }

    private fun string(args: JsonObject, key: String): String? = when (val v = args[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else throw ToolError("$key must be a string")
        else -> throw ToolError("$key must be a string")
    }

    private fun optString(args: JsonObject, key: String) = string(args, key)

    private fun optInt(args: JsonObject, key: String): Int? = when (val v = args[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) throw ToolError("$key must be an integer")
            else v.intOrNull ?: v.contentOrNull?.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && it in -1e9..1e9 }?.toInt()
                ?: throw ToolError("$key must be an integer")
        else -> throw ToolError("$key must be an integer")
    }

    companion object {
        const val RUN = "hana_run_task"
        const val STEER = "hana_steer_task"
        const val STATUS = "hana_task_status"
        const val WAIT = "hana_wait_task"
        const val ANSWER = "hana_answer"
        const val CANCEL = "hana_cancel_task"
        const val LIST = "hana_list_tasks"
        const val DEFAULT_WAIT_S = RemoteTaskService.DEFAULT_WAIT_S
        const val MAX_WAIT_S = RemoteTaskService.MAX_WAIT_S
        const val DEFAULT_LIST = RemoteTaskService.DEFAULT_LIST
        const val MAX_LIST = RemoteTaskService.MAX_LIST
        const val MAX_GOAL = RemoteTaskService.MAX_GOAL
        const val MAX_ANSWER = RemoteTaskService.MAX_ANSWER
        private const val ON_PHONE_NOTE = "This step needs the user on the phone and cannot be answered over MCP. " +
            "Hana has shown its usual notification there; keep waiting with hana_wait_task or cancel the task."

        fun newConversation(): String = RemoteTaskService.newConversation()
        fun isConversation(id: String): Boolean = RemoteTaskService.isConversation(id)

        private fun schema(required: List<String>, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties", props)
            putJsonArray("required") { required.forEach { add(it) } }
            put("additionalProperties", false)
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.taskIdProp() =
            putJsonObject("task_id") { put("type", "string"); put("description", "The task_id returned by hana_run_task.") }
    }
}

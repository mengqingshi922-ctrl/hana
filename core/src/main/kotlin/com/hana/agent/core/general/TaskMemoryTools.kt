package com.hana.agent.core.general

import com.hana.agent.core.observation.ObsKind
import com.hana.agent.core.observation.Observation
import com.hana.agent.core.prompt.Prompts
import com.hana.agent.core.tool.AgentTool
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.Concurrency
import com.hana.agent.core.tool.EvidenceLog
import com.hana.agent.core.tool.Risk
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolDef
import com.hana.agent.core.tool.ToolExecution
import com.hana.agent.core.tool.Verb
import com.hana.agent.core.tool.argStr
import com.hana.agent.core.tool.fail
import com.hana.agent.core.tool.int
import com.hana.agent.core.tool.ok
import com.hana.agent.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private fun taskMemoryOn(ctx: ToolContext) = ctx.config.taskMemoryStore != null

/**
 * 第一层：按意图关键词或特定 App 检索历史相关任务记忆及执行结论。
 */
internal object TaskMemorySearch : AgentTool {
    override val verb = Verb.TaskMemorySearch
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(
        name = "task_memory_search",
        description = Prompts.tool("task_memory_search"),
        props = mapOf(
            "query" to str("Intent keywords, operation terms or goal keywords to search for, 1-200 chars."),
            "app" to str("Optional target app package or app keyword (e.g. com.taobao.taobao or taobao)."),
            "limit" to int("Optional max number of records to return, between 1 and 20. Default is 5.", min = 1, max = 20),
        ),
        required = listOf("query"),
    )

    override fun available(ctx: ToolContext) = taskMemoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 200)!!
        val app = a.str("app", max = 100)
        val limit = a.int("limit", min = 1, max = 20) ?: 5

        val store = ctx.config.taskMemoryStore!!
        val found = store.search(q, app = app, limit = limit)

        return ok {
            putJsonArray("tasks") {
                found.forEach { mem ->
                    add(buildJsonObject {
                        put("id", mem.id)
                        put("goal", mem.goal)
                        put("conclusion", mem.conclusion)
                        put("timestamp", mem.timestamp)
                        put("success", mem.success)
                        putJsonArray("apps") { mem.apps.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("domains") { mem.domains.forEach { add(JsonPrimitive(it)) } }
                        put("stepCount", mem.keySteps.size)
                    })
                }
            }
        }
    }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val tasks = r.content["tasks"] as? JsonArray ?: return emptyList()
        if (tasks.isEmpty()) return emptyList()

        val q = o.argStr("query") ?: ""
        val text = buildString {
            appendLine("任务记忆检索「$q」找到 ${tasks.size} 条历史经验:")
            tasks.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val goal = (obj["goal"] as? JsonPrimitive)?.content ?: ""
                val conclusion = (obj["conclusion"] as? JsonPrimitive)?.content ?: ""
                appendLine("- 目标: $goal -> 结论: $conclusion (id: ${(obj["id"] as? JsonPrimitive)?.content})")
            }
        }.trimEnd()

        return listOf(Observation(ObsKind.Fact, text, source = "task_memory"))
    }
}

/**
 * 第二层：展开某个历史任务的关键操作序列。
 */
internal object TaskMemoryStep : AgentTool {
    override val verb = Verb.TaskMemoryStep
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(
        name = "task_memory_step",
        description = Prompts.tool("task_memory_step"),
        props = mapOf(
            "id" to str("Unique task memory id, e.g. tm_1234567890abcdef"),
        ),
        required = listOf("id"),
    )

    override fun available(ctx: ToolContext) = taskMemoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val id = a.str("id", max = 100)!!
        val store = ctx.config.taskMemoryStore!!
        val mem = store.get(id) ?: return fail("not_found", "Task memory with id '$id' was not found")

        return ok {
            put("id", mem.id)
            put("goal", mem.goal)
            put("conclusion", mem.conclusion)
            put("timestamp", mem.timestamp)
            put("success", mem.success)
            putJsonArray("apps") { mem.apps.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("domains") { mem.domains.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("steps") {
                mem.keySteps.forEach { step ->
                    add(buildJsonObject {
                        put("step", step.step)
                        put("tool", step.tool)
                        put("action", step.action)
                        step.note?.let { put("note", it) }
                    })
                }
            }
        }
    }
}

/**
 * 第三层：浏览近期的任务执行足迹。
 */
internal object TaskMemoryTimeline : AgentTool {
    override val verb = Verb.TaskMemoryTimeline
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(
        name = "task_memory_timeline",
        description = Prompts.tool("task_memory_timeline"),
        props = mapOf(
            "limit" to int("Optional number of recent tasks to return, between 1 and 50. Default is 10.", min = 1, max = 50),
            "before" to str("Optional timestamp cursor (in milliseconds) to retrieve tasks executed before this time."),
        ),
        required = emptyList(),
    )

    override fun available(ctx: ToolContext) = taskMemoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val limit = a.int("limit", min = 1, max = 50) ?: 10
        val beforeStr = a.str("before")
        val before = beforeStr?.toLongOrNull()

        val store = ctx.config.taskMemoryStore!!
        val items = store.timeline(limit = limit, beforeTimestamp = before)

        return ok {
            put("total", items.size)
            putJsonArray("timeline") {
                items.forEach { mem ->
                    add(buildJsonObject {
                        put("id", mem.id)
                        put("goal", mem.goal)
                        put("conclusion", mem.conclusion)
                        put("timestamp", mem.timestamp)
                        put("success", mem.success)
                        putJsonArray("apps") { mem.apps.forEach { add(JsonPrimitive(it)) } }
                        put("stepCount", mem.keySteps.size)
                    })
                }
            }
        }
    }
}

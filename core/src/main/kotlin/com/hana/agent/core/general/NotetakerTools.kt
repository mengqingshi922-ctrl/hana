package com.hana.agent.core.general

import com.hana.agent.core.memory.TaskNotePad
import com.hana.agent.core.tool.AgentTool
import com.hana.agent.core.tool.ArgError
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.Risk
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolDef
import com.hana.agent.core.tool.ToolExecution
import com.hana.agent.core.tool.Verb
import com.hana.agent.core.tool.str
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 结构化笔记追加工具：在长列表或跨步骤翻页时记录提取的事实（如 price, contact, summary, todo 等），
 * 避免重复翻页或上下文超限。
 */
internal object NoteAppend : AgentTool {
    override val verb: Verb = Verb.MemorySave
    override val risk: Risk = Risk.Read
    override fun available(ctx: ToolContext): Boolean = ctx.config.enableNotetaker
    override val def: ToolDef = ToolDef(
        name = "note_append",
        description = com.hana.agent.core.prompt.Prompts.tool("note_append"),
        props = mapOf(
            "category" to str("Category of the note (e.g. 'price', 'contact', 'summary', 'todo')."),
            "key" to str("Identifier of the note entry (e.g. product title or person name)."),
            "value" to str("Content or value of the note entry.")
        ),
        required = listOf("category", "key", "value")
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val category = a.str("category") ?: throw ArgError("category", "category is required")
        val key = a.str("key") ?: throw ArgError("key", "key is required")
        val value = a.str("value") ?: throw ArgError("value", "value is required")

        val runId = ctx.config.runSpec?.id?.value ?: ctx.config.conversationId ?: "default"
        val pad = TaskNotePad.getForRun(runId)
        val isNew = pad.append(category, key, value)

        return ToolExecution(buildJsonObject {
            put("ok", true)
            put("isNew", isNew)
            put("category", category.trim().lowercase())
            put("key", key.trim())
            put("totalNotes", pad.totalEntriesCount)
        })
    }
}

/**
 * 结构化笔记查询工具：读取当前任务笔记槽中已记录的条目。
 */
internal object NoteRead : AgentTool {
    override val verb: Verb = Verb.MemorySearch
    override val risk: Risk = Risk.Read
    override fun available(ctx: ToolContext): Boolean = ctx.config.enableNotetaker
    override val def: ToolDef = ToolDef(
        name = "note_read",
        description = com.hana.agent.core.prompt.Prompts.tool("note_read"),
        props = mapOf(
            "category" to str("Optional category to filter (e.g. 'price', 'contact', 'summary', 'todo'). Omit to read all.")
        )
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val category = a.str("category")
        val runId = ctx.config.runSpec?.id?.value ?: ctx.config.conversationId ?: "default"
        val pad = TaskNotePad.getForRun(runId)
        val entries = pad.read(category)
        val summary = pad.renderCompactSummary()

        return ToolExecution(buildJsonObject {
            put("ok", true)
            category?.let { put("category", it) }
            put("count", entries.size)
            put("totalNotes", pad.totalEntriesCount)
            put("summary", summary)
            put("notes", buildJsonArray {
                entries.forEach { entry ->
                    add(buildJsonObject {
                        put("category", entry.category)
                        put("key", entry.key)
                        put("value", entry.value)
                        put("timestamp", entry.timestamp)
                    })
                }
            })
        })
    }
}

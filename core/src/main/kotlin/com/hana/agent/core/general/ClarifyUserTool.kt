package com.hana.agent.core.general

import com.hana.agent.core.tool.AgentTool
import com.hana.agent.core.tool.ArgError
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.Outcome
import com.hana.agent.core.tool.Risk
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolDef
import com.hana.agent.core.tool.ToolExecution
import com.hana.agent.core.tool.Verb
import com.hana.agent.core.tool.bool
import com.hana.agent.core.tool.str
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MAI-UI 主动澄清歧义工具：
 * 在用户目标存在歧义、关键约束缺失或存在多分支决策时，向用户发起结构化澄清提问。
 * 请求附带结构化候选选项，便于 UI 悬浮胶囊直接渲染可点击按钮，亦允许自由文本补充。
 */
internal object ClarifyUserTool : AgentTool {
    override val verb: Verb = Verb.AskUser
    override val risk: Risk = Risk.Read
    override fun available(ctx: ToolContext): Boolean = ctx.config.enableClarifyIntent
    override val def: ToolDef = ToolDef(
        name = "clarify_intent",
        description = com.hana.agent.core.prompt.Prompts.tool("clarify_intent"),
        props = mapOf(
            "question" to str("The clarification question to show the user."),
            "options" to buildJsonObject {
                put("type", "array")
                put("description", "Candidate choice buttons for the user to tap directly.")
                put("items", buildJsonObject { put("type", "string") })
            },
            "allowFreeText" to bool("Whether the user can enter custom text in addition to clicking option buttons (default true)."),
            "missingConstraint" to str("Optional description of the missing parameter or constraint triggering this clarification.")
        ),
        required = listOf("question", "options")
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val question = a.str("question", max = 1000)?.trim()
            ?: a.str("prompt", max = 1000)?.trim()
            ?: throw ArgError("question", "question is required")
        val options = a.strList("options", min = 1, max = 20)?.map(String::trim)?.distinct() ?: emptyList()
        val allowFreeText = a.bool("allowFreeText", default = true)
        val missingConstraint = a.str("missingConstraint")?.trim()

        val content = buildJsonObject {
            put("asked", true)
            put("clarification", true)
            put("question", question)
            put("options", buildJsonArray { options.forEach { add(JsonPrimitive(it)) } })
            put("allowFreeText", allowFreeText)
            missingConstraint?.let { put("missingConstraint", it) }
        }

        return ToolExecution(
            content = content,
            outcome = Outcome.Input(prompt = question, options = options.ifEmpty { null })
        )
    }
}

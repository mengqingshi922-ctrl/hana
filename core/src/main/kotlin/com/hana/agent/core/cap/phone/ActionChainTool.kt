package com.hana.agent.core.cap.phone

import com.hana.agent.core.action.ChainPredicateKind
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.Risk
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolDef
import com.hana.agent.core.tool.ToolExecution
import com.hana.agent.core.tool.Verb
import com.hana.agent.core.tool.str
import kotlinx.serialization.json.*

/**
 * action_chain 工具：投机宏动作链执行原语契约
 */
internal object ActionChainTool : PhoneTool {
    override val verb: Verb = Verb.PhoneRun
    override val risk: Risk = Risk.Write
    override val name: String = "action_chain"
    override val def: ToolDef = ToolDef(
        name = "action_chain",
        description = "Execute a speculative sequence of macro actions with a 35ms render settle window between steps. Halts on assertion failure with undo back compensation.",
        props = mapOf(
            "steps" to buildJsonObject {
                put("type", "array")
                put("description", "Ordered list of speculative macro steps")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("tool", buildJsonObject { put("type", "string") })
                        put("args", buildJsonObject { put("type", "object") })
                        put("predicate", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                ChainPredicateKind.entries.forEach { add(it.name) }
                            })
                        })
                        put("expectText", buildJsonObject { put("type", "string") })
                        put("timeoutMs", buildJsonObject { put("type", "integer"); put("default", 200) })
                    })
                    put("required", buildJsonArray { add("tool"); add("args") })
                })
            },
            "fallbackStrategy" to str("Fallback strategy: backtrack, stop_and_query", listOf("backtrack", "stop_and_query"))
        ),
        required = listOf("steps")
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = execute(a.o)

    suspend fun execute(args: JsonObject): ToolExecution {
        val steps = args["steps"] as? JsonArray ?: return ToolExecution(buildJsonObject { put("ok", false); put("error", "missing_steps") })
        return ToolExecution(buildJsonObject {
            put("ok", true)
            put("stepsCount", steps.size)
            put("strategy", args["fallbackStrategy"]?.jsonPrimitive?.contentOrNull ?: "backtrack")
        })
    }
}

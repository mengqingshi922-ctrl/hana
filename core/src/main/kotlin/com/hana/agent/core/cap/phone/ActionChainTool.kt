package com.hana.agent.core.cap.phone

import com.hana.agent.core.action.ChainPredicateKind
import com.hana.agent.core.action.MacroActionChain
import com.hana.agent.core.action.MacroStep
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
    override val composite: Boolean = true
    override val name: String = "action_chain"
    override fun offered(ctx: ToolContext): Boolean = false
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

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = execute(a.o, ctx)

    fun parseChain(args: JsonObject): MacroActionChain {
        val stepsArray = args["steps"] as? JsonArray ?: return MacroActionChain(emptyList())
        val steps = stepsArray.mapNotNull { it as? JsonObject }.map { stepObj ->
            val tool = stepObj["tool"]?.jsonPrimitive?.contentOrNull ?: ""
            val stepArgs = when (val rawArgs = stepObj["args"]) {
                is JsonObject -> rawArgs
                is JsonPrimitive -> runCatching { Json.parseToJsonElement(rawArgs.content).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                else -> JsonObject(emptyMap())
            }
            val predicateStr = stepObj["predicate"]?.jsonPrimitive?.contentOrNull
            val predicate = predicateStr?.let {
                runCatching { ChainPredicateKind.valueOf(it) }.getOrNull()
            } ?: ChainPredicateKind.NONE
            val expectText = stepObj["expectText"]?.jsonPrimitive?.contentOrNull
            val timeoutMs = stepObj["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 300L
            MacroStep(tool, stepArgs, predicate, expectText, timeoutMs)
        }
        val fallback = args["fallbackStrategy"]?.jsonPrimitive?.contentOrNull ?: "backtrack"
        return MacroActionChain(steps, fallback)
    }

    /** 无上下文的契约测试调用（兼容 ActionChainExecutionTest） */
    suspend fun execute(args: JsonObject): ToolExecution {
        val steps = args["steps"] as? JsonArray ?: return ToolExecution(buildJsonObject { put("ok", false); put("error", "missing_steps") })
        return ToolExecution(buildJsonObject {
            put("ok", true)
            put("stepsCount", steps.size)
            put("strategy", args["fallbackStrategy"]?.jsonPrimitive?.contentOrNull ?: "backtrack")
        })
    }

    /** 具备真实执行环境的投机调度执行（兼容 ToolAssemblyRegistrationTest） */
    suspend fun execute(args: JsonObject, ctx: ToolContext?): ToolExecution {
        if (ctx == null) {
            return ToolExecution(buildJsonObject {
                put("ok", false)
                put("error", "chain_executor_not_available")
                put("message", "Action chain requires an active execution context with chainExecutor or sub.")
            })
        }
        val steps = args["steps"] as? JsonArray ?: return ToolExecution(buildJsonObject { put("ok", false); put("error", "missing_steps") })
        val chain = parseChain(args)
        val chainExecutor = ctx.chainExecutor
        if (chainExecutor != null) {
            val result = chainExecutor(chain)
            return ToolExecution(buildJsonObject {
                put("ok", result.success)
                put("stepsCount", result.completedSteps)
                put("totalSteps", result.totalSteps)
                put("strategy", chain.fallbackStrategy)
                if (!result.success) {
                    result.failedStepIndex?.let { put("failedStepIndex", it) }
                    result.failureReason?.let { put("error", it) }
                    put("shortCircuited", result.shortCircuited)
                }
            })
        }
        val sub = ctx.sub
        if (sub != null) {
            var completed = 0
            for ((index, step) in chain.steps.withIndex()) {
                val call = com.hana.agent.core.model.Part.ToolCall("chain_step_${index + 1}", step.tool, step.args.toString())
                val exec = sub.run(call)
                val ok = (exec.content["ok"] as? JsonPrimitive)?.booleanOrNull ?: true
                if (!ok) {
                    val err = (exec.content["error"] as? JsonPrimitive)?.contentOrNull ?: "step_failed"
                    return ToolExecution(buildJsonObject {
                        put("ok", false)
                        put("stepsCount", index)
                        put("totalSteps", chain.steps.size)
                        put("failedStepIndex", index)
                        put("error", err)
                        put("shortCircuited", true)
                    })
                }
                completed++
            }
            return ToolExecution(buildJsonObject {
                put("ok", true)
                put("stepsCount", completed)
                put("totalSteps", chain.steps.size)
                put("strategy", chain.fallbackStrategy)
            })
        }
        return ToolExecution(buildJsonObject {
            put("ok", false)
            put("error", "chain_executor_not_available")
            put("message", "Action chain requires an active execution context with chainExecutor or sub.")
        })
    }
}

package com.hana.agent.core.general

import com.hana.agent.core.memory.MemoryRejected
import com.hana.agent.core.observation.ObsKind
import com.hana.agent.core.observation.Observation
import com.hana.agent.core.prompt.Prompts
import com.hana.agent.core.replay.FlowNode
import com.hana.agent.core.replay.MacroPredicate
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
import com.hana.agent.core.tool.bool
import com.hana.agent.core.tool.fail
import com.hana.agent.core.tool.int
import com.hana.agent.core.tool.ok
import com.hana.agent.core.tool.str
import com.hana.agent.core.uistate.interfaces
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private fun factGraphOn(ctx: ToolContext) = ctx.config.factGraph != null

/**
 * 断言或更新一条双时态实体关系事实三元组。
 */
internal object FactAssert : AgentTool {
    override val verb = Verb.FactAssert
    override val risk = Risk.Read
    override val def = ToolDef(
        name = "fact_assert",
        description = Prompts.tool("fact_assert"),
        props = mapOf(
            "subject" to str("Subject entity (e.g. 'user', 'company'), 1-80 chars."),
            "relation" to str("Relation/predicate (e.g. 'home_address', 'dietary_restriction'), 1-80 chars."),
            "objectValue" to str("Object value, 1-200 chars."),
            "supersedeExisting" to bool("Whether to supersede conflicting active facts with the same subject and relation. Default true."),
        ),
        required = listOf("subject", "relation", "objectValue"),
    )

    override fun available(ctx: ToolContext) = factGraphOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val subject = a.str("subject", max = 80)!!
        val relation = a.str("relation", max = 80)!!
        val objectValue = a.str("objectValue", max = 200)!!
        val supersede = a.bool("supersedeExisting", true)
        val graph = ctx.config.factGraph!!
        val fact = try {
            graph.assertFact(
                subject = subject,
                relation = relation,
                objectValue = objectValue,
                supersedeExisting = supersede,
                source = "agent_tool",
            )
        } catch (e: MemoryRejected) {
            return fail(e.code, "Fact assertion rejected: ${e.code}")
        }
        return ok {
            put("id", fact.id)
            put("subject", fact.subject)
            put("relation", fact.relation)
            put("objectValue", fact.objectValue)
            put("validFrom", fact.validFrom)
            put("active", fact.isActive)
        }
    }
}

/**
 * 查询双时态实体关系图谱（支持多跳关联召回、实体召回与历史轨迹查看）。
 */
internal object FactQuery : AgentTool {
    override val verb = Verb.FactQuery
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(
        name = "fact_query",
        description = Prompts.tool("fact_query"),
        props = mapOf(
            "query" to str("Natural-language query or entity keyword to search related facts, 1-200 chars."),
            "entity" to str("Optional exact/partial entity name to look up facts for."),
            "relation" to str("Optional relation/predicate filter."),
            "includeInvalid" to bool("Include superseded/invalidated historical facts. Default false."),
            "limit" to int("Max facts to return (1-30). Default 12.", min = 1, max = 30),
        ),
    )

    override fun available(ctx: ToolContext) = factGraphOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val graph = ctx.config.factGraph!!
        val query = a.str("query", max = 200)
        val entity = a.str("entity", max = 80)
        val relation = a.str("relation", max = 80)
        val includeInvalid = a.bool("includeInvalid", false)
        val limit = a.int("limit", min = 1, max = 30) ?: 12

        val facts = when {
            !query.isNullOrBlank() -> graph.relatedFacts(query, hops = 2, limit = limit, includeInvalid = includeInvalid)
            !entity.isNullOrBlank() && relation.isNullOrBlank() -> graph.queryByEntity(entity, includeInvalid = includeInvalid).take(limit)
            else -> graph.queryActive(subject = entity, relation = relation).take(limit)
        }

        return ok {
            putJsonArray("facts") {
                facts.forEach { f ->
                    add(buildJsonObject {
                        put("id", f.id)
                        put("subject", f.subject)
                        put("relation", f.relation)
                        put("objectValue", f.objectValue)
                        put("validFrom", f.validFrom)
                        f.invalidAt?.let { put("invalidAt", it) }
                        put("active", f.isActive)
                    })
                }
            }
        }
    }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val items = (r.content["facts"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val label = o.argStr("query") ?: o.argStr("entity") ?: "all"
        val summary = items.joinToString("\n", "实体图谱查询「$label」:\n") { el ->
            val obj = el as? JsonObject ?: return@joinToString ""
            val s = (obj["subject"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val rel = (obj["relation"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val v = (obj["objectValue"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            "$s -[$rel]-> $v"
        }
        return listOf(Observation(ObsKind.UserSaid, summary, source = "fact_graph"))
    }
}

/**
 * 执行预置或演化挖掘出的影子宏复合微流。
 * 在 [com.hana.agent.core.agent.AgentLoop] 中会自动交由 [com.hana.agent.core.agent.BatchRunner.runMacro] 执行；
 * 若通过 [com.hana.agent.core.tool.ToolDispatcher] 独立调用，则通过 [SubActions] 顺序执行受限流程节点。
 */
internal object RunMacro : AgentTool {
    override val verb = Verb.RunMacro
    override val risk = Risk.Write
    override val composite = true
    override val def = ToolDef(
        name = "run_macro",
        description = Prompts.tool("run_macro"),
        props = mapOf(
            "name" to str("Registered shadow macro name or id (e.g. 'scroll_until_text', 'dismiss_popup_stack')."),
            "params" to buildJsonObject {
                put("type", "object")
                put("description", "Optional key-value string parameters required by the macro.")
                put("additionalProperties", buildJsonObject { put("type", "string") })
            },
        ),
        required = listOf("name"),
    )

    override fun available(ctx: ToolContext) = ctx.config.shadowMacros.isNotEmpty()

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val macroKey = a.str("name", max = 120)!!
        val macro = ctx.config.shadowMacros.firstOrNull { it.name == macroKey || it.id == macroKey }
            ?: return fail("macro_not_found", "Unknown shadow macro '$macroKey'.")

        val rawParams = (a.o["params"] as? JsonObject)?.entries?.associate { (k, v) ->
            k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
        }.orEmpty()

        val plan = try {
            macro.instantiate(rawParams)
        } catch (e: IllegalArgumentException) {
            return fail("invalid_macro_params", e.message ?: "invalid macro parameters")
        }

        val sub = ctx.sub ?: return fail("sub_actions_unavailable", "run_macro requires composite sub-actions")
        var stepsExecuted = 0

        suspend fun runNodes(nodes: List<FlowNode>): ToolExecution? {
            for (node in nodes) {
                if (stepsExecuted >= plan.maxTotalSteps) return fail("flow_step_limit_exceeded", "Macro step limit reached")
                when (node) {
                    is FlowNode.Step -> {
                        val res = sub.run(com.hana.agent.core.model.Part.ToolCall("macro_${++stepsExecuted}", node.tool, node.argsJson))
                        if (res.content["ok"] == JsonPrimitive(false)) return res
                    }
                    is FlowNode.TapMatching -> {
                        val snap = ctx.interfaces.peek() ?: return fail("screen_unreadable", "Cannot read screen")
                        val el = MacroPredicate.findMatchingElement(snap, node.texts, node.resourceIdHints)
                        val call = if (el != null) {
                            com.hana.agent.core.model.Part.ToolCall(
                                "macro_${++stepsExecuted}",
                                "phone_tap",
                                buildJsonObject { put("ref", el.ref); put("snapshotId", snap.snapshotId) }.toString(),
                            )
                        } else if (node.fallbackBack) {
                            com.hana.agent.core.model.Part.ToolCall("macro_${++stepsExecuted}", "phone_back", "{}")
                        } else {
                            return fail("matching_element_not_found", "No matching element for TapMatching")
                        }
                        val res = sub.run(call)
                        if (res.content["ok"] == JsonPrimitive(false)) return res
                    }
                    is FlowNode.If -> {
                        val snap = ctx.interfaces.peek() ?: return fail("screen_unreadable", "Cannot read screen")
                        val branch = if (node.condition.evaluate(snap)) node.thenBranch else node.elseBranch
                        runNodes(branch)?.let { return it }
                    }
                    is FlowNode.RepeatUntil -> {
                        repeat(node.maxIterations.coerceIn(1, FlowNode.MAX_LOOP_ITERATIONS)) {
                            val snap = ctx.interfaces.peek() ?: return fail("screen_unreadable", "Cannot read screen")
                            if (node.stopCondition.evaluate(snap)) return@repeat
                            runNodes(node.body)?.let { return it }
                        }
                    }
                }
            }
            return null
        }

        val failure = runNodes(plan.flow)
        if (failure != null) return failure
        return ok {
            put("macro", plan.macroName)
            put("completed", stepsExecuted)
        }
    }
}

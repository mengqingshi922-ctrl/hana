package com.hana.agent.core.bus

import com.hana.agent.core.tool.AgentTool
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.Risk
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolDef
import com.hana.agent.core.tool.ToolExecution
import com.hana.agent.core.tool.Verb
import com.hana.agent.core.tool.str
import kotlinx.serialization.json.*

/**
 * register_store 工具：向零拷贝寄存器总线存入语义变量
 */
internal object RegisterStoreTool : AgentTool {
    override val capability: String get() = "phone"
    override val sandboxCallable: Boolean get() = true
    override val verb: Verb = Verb.Collect
    override val risk: Risk = Risk.Read
    override val name: String = "register_store"
    override fun offered(ctx: ToolContext): Boolean = false
    override val def: ToolDef = ToolDef(
        name = "register_store",
        description = "Store text into a zero-copy semantic register (\$reg:key) for cross-app reuse.",
        props = mapOf(
            "key" to str("Register key, e.g. order_id, sms_code"),
            "value" to str("Value string to be saved")
        ),
        required = listOf("key", "value")
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = execute(a.o)

    suspend fun execute(args: JsonObject): ToolExecution {
        val rawKey = args["key"]?.jsonPrimitive?.contentOrNull ?: return ToolExecution(buildJsonObject { put("ok", false); put("error", "missing_key") })
        val rawValue = args["value"]?.jsonPrimitive?.contentOrNull ?: return ToolExecution(buildJsonObject { put("ok", false); put("error", "missing_value") })
        val key = rawKey.replace(Regex("""[\r\n\t\x00-\x1F\x7F]"""), "").trim()
        val value = rawValue.take(12000)
        RegisterBus.put(key, value)
        val normalizedKey = if (key.startsWith(RegisterBus.PREFIX)) key else RegisterBus.PREFIX + key
        return ToolExecution(buildJsonObject {
            put("ok", true)
            put("key", normalizedKey)
            put("length", value.length)
        })
    }
}

/**
 * register_resolve 工具：从零拷贝寄存器总线解析语义变量
 */
internal object RegisterResolveTool : AgentTool {
    override val capability: String get() = "phone"
    override val sandboxCallable: Boolean get() = true
    override val verb: Verb = Verb.Query
    override val risk: Risk = Risk.Read
    override val name: String = "register_resolve"
    override fun offered(ctx: ToolContext): Boolean = false
    override val def: ToolDef = ToolDef(
        name = "register_resolve",
        description = "Resolve content stored in a zero-copy semantic register.",
        props = mapOf(
            "key" to str("Register key to resolve")
        ),
        required = listOf("key")
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = execute(a.o)

    suspend fun execute(args: JsonObject): ToolExecution {
        val rawKey = args["key"]?.jsonPrimitive?.contentOrNull ?: return ToolExecution(buildJsonObject { put("ok", false); put("error", "missing_key") })
        val key = rawKey.replace(Regex("""[\r\n\t\x00-\x1F\x7F]"""), "").trim()
        val value = RegisterBus.get(key)
        val normalizedKey = if (key.startsWith(RegisterBus.PREFIX)) key else RegisterBus.PREFIX + key
        return ToolExecution(buildJsonObject {
            put("ok", value != null)
            put("key", normalizedKey)
            put("value", value ?: "")
            if (value == null) put("error", "register_not_found")
        })
    }
}

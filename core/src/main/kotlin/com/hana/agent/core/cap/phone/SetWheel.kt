package com.hana.agent.core.cap.phone

import com.hana.agent.core.prompt.Prompts
import com.hana.agent.core.tool.ArgError
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.FAILURE_MESSAGES
import com.hana.agent.core.tool.REF
import com.hana.agent.core.tool.Risk
import com.hana.agent.core.tool.SNAP
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolDef
import com.hana.agent.core.tool.ToolExecution
import com.hana.agent.core.tool.Verb
import com.hana.agent.core.tool.argStr
import com.hana.agent.core.tool.str
import com.hana.agent.core.uistate.ActionResult
import com.hana.agent.core.uistate.WheelOutcome
import com.hana.agent.core.uistate.action
import com.hana.agent.core.uistate.screen
import com.hana.agent.core.uistate.withPortRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把一个滚轮选择器(时间、日期、地区……)转到一个值:一次调用在端口里转到位(能输入就输入,能无障碍滚就滚,
 * 不行就按一格多高拖),不用模型一格一格看。转不到时照实报此刻显示的值。
 */
internal object SetWheel : PhoneTool {
    override val verb = Verb.SetWheel
    override val batchable = true
    override val label = "拨滚轮"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def = ToolDef("phone_set_wheel", Prompts.tool("phone_set_wheel"), mapOf(
        "ref" to str("$REF The wheel itself, or the item it currently shows."),
        "snapshotId" to str(SNAP),
        "value" to str("The value the wheel should show, as written on it, e.g. 9, 40, 2026, PM, Beijing."),
    ), listOf("ref", "snapshotId", "value"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val ref = a.ref() ?: throw ArgError("ref", "required")
        val value = a.str("value", max = 60)!!.trim()
        return ctx.withPortRef(ref) { r ->
            val o = ctx.screen.setWheel(r!!.first, r.second, value)
            if (o.error == "ref_expired" || o.error == "no_app_open") return@withPortRef ctx.action(ActionResult.Failed(o.error))
            result(o, value)
        }
    }

    private fun result(o: WheelOutcome, value: String): ToolExecution {
        val body = buildJsonObject {
            put("ok", o.reached)
            if (!o.reached) {
                val code = when (o.error) {
                    "not_a_wheel" -> "not_a_wheel"
                    "at_end" -> "wheel_at_end"
                    "unknown_value" -> "wheel_unreadable"
                    "not_moving" -> "wheel_not_moving"
                    else -> "wheel_not_reached"
                }
                put("error", code)
                put("message", listOfNotNull(FAILURE_MESSAGES.getValue(code), o.hint).joinToString(" "))
            }
            put("target", value)
            o.value?.let { put("value", it) }
            o.from?.let { put("from", it) }
            put("attempts", o.attempts)
            put("method", o.method)
            if (o.reached) o.hint?.let { put("hint", it) }
        }
        return ToolExecution(body, changed = o.attempts > 0 && o.value != o.from)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = o.argStr("value")
}

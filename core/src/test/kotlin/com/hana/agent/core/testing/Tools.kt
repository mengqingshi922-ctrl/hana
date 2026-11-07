package com.hana.agent.core.testing

import com.hana.agent.core.assembly.knownTools
import com.hana.agent.core.assembly.recordedTools
import com.hana.agent.core.cap.sandbox.SANDBOX
import com.hana.agent.core.cap.web.WEB
import com.hana.agent.core.tool.ToolIndex
import com.hana.agent.core.tool.Verb

/** 测试用:Mika 全部工具的声明。 */
internal val TOOLS: ToolIndex get() = knownTools()

/** 测试用:读运行记录的工具声明(改名前的旧名也认得)。 */
internal val RECORDED_TOOLS: ToolIndex get() = recordedTools()

/** 测试用:按工具名查它做的动作。 */
internal fun verb(name: String): Verb? = TOOLS.verb(name)

/** 测试用:能放进 do_steps 的工具名,按定义里的顺序。 */
internal val BATCHABLE: List<String> get() = TOOLS.names.filter { TOOLS[it]!!.batchable }

/** 测试用:网页、沙箱一组的工具名。 */
internal val WEB_TOOLS: Set<String> get() = TOOLS.names.filter { TOOLS[it]!!.capability == WEB }.toSet()
internal val SANDBOX_TOOLS: Set<String> get() = TOOLS.names.filter { TOOLS[it]!!.capability == SANDBOX }.toSet()

internal const val WEB_SCRIPT_TOOL = "web_script"
internal const val INTERACT_TOOL = "web_interact"

/** 测试用:不带调用的回复,就是交给用户的最终回答。 */
internal fun answerReply(text: String) =
    com.hana.agent.core.model.ModelReply(listOf(com.hana.agent.core.model.Part.Text(text)), com.hana.agent.core.model.StopReason.EndTurn, null)

/** 测试用:任务办成了,交出的回答是 [answer],没有核对出问题(核实过,或没有可核对的;来源另测)。 */
internal fun assertAnswered(answer: String, end: com.hana.agent.core.agent.TaskState) {
    val s = end as? com.hana.agent.core.agent.TaskState.Succeeded ?: throw AssertionError("not succeeded: $end")
    org.junit.Assert.assertEquals(answer, s.answer)
    org.junit.Assert.assertTrue("$end", !s.doubtful && s.unconfirmed.isEmpty())
}

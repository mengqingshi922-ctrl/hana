package com.hana.agent.core.eval

import com.hana.agent.core.watchdog.WatchdogVerdict
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClawGUI / AndroidWorld 失败轨迹脱敏沉淀与 EvalTask 导出单元测试。
 */
class FailureTrajectoryExporterTest {

    @Test
    fun testPrivacyRedaction() {
        val rawGoal = "帮用户 Alice (手机号: 13812345678, 邮箱: test.dev@corp.com) 查询身份证 110101199003072345 的社保状态"
        val redacted = FailureTrajectoryExporter.redact(rawGoal)

        assertFalse(redacted.contains("13812345678"))
        assertFalse(redacted.contains("test.dev@corp.com"))
        assertFalse(redacted.contains("110101199003072345"))
        assertTrue(redacted.contains("{REDACTED}"))

        val textWithCode = "当前界面提示：本次操作验证码: 952712，请勿泄露"
        val redactedCode = FailureTrajectoryExporter.redact(textWithCode)
        assertFalse(redactedCode.contains("952712"))
        assertTrue(redactedCode.contains("{REDACTED}"))
    }

    @Test
    fun testExportEvalTaskEntity() {
        val task = FailureTrajectoryExporter.export(
            goal = "转账 500 元给 13987654321，卡号 622202600011223344",
            apps = listOf("com.bank.app"),
            verdict = WatchdogVerdict.FORM_VALIDATION_ERROR,
            failureReason = "收款人姓名不一致，校验未通过",
            lastObservedTexts = listOf("转账失败", "户名与卡号不匹配", "请核对后再试"),
            executedActions = listOf("phone_launch_app", "phone_type", "phone_tap"),
            taskId = "eval_transfer_err_01",
        )

        assertEquals("eval_transfer_err_01", task.id)
        assertFalse(task.goal.contains("13987654321"))
        assertFalse(task.goal.contains("622202600011223344"))
        assertTrue(task.goal.contains("{REDACTED}"))

        assertEquals(listOf("com.bank.app"), task.apps)
        assertEquals(EvalCategory.Barrier, task.category)
        assertEquals(Expect.Failed, task.expect)

        assertTrue(task.checks.isNotEmpty())
        assertTrue(task.checks.first() is Check.AnswerContains)
        val answerCheck = task.checks.first() as Check.AnswerContains
        assertTrue(answerCheck.all.any { it == "转账失败" || it == "户名与卡号不匹配" })

        assertNotNull(task.note)
        assertTrue(task.note!!.contains("FORM_VALIDATION_ERROR"))
        assertTrue(task.note!!.contains("phone_launch_app -> phone_type -> phone_tap"))
    }

    @Test
    fun testExportToJsonAndDeserialize() {
        val jsonStr = FailureTrajectoryExporter.exportToJson(
            goal = "在美团给 13611112222 订一杯奶茶送到公司",
            apps = listOf("com.sankuai.meituan"),
            verdict = WatchdogVerdict.STAGNATION,
            failureReason = "连续3次点击立即结算无响应",
            lastObservedTexts = listOf("商品结算", "配送至：杭州市西湖区"),
            executedActions = listOf("phone_tap", "phone_tap", "phone_tap"),
        )

        assertTrue(jsonStr.contains("\"goal\""))
        assertFalse(jsonStr.contains("13611112222"))
        assertTrue(jsonStr.contains("{REDACTED}"))

        // 验证导出的 JSON 可被标准 kotlinx.serialization 反序列化为 EvalTask
        val jsonParser = Json { ignoreUnknownKeys = true }
        val parsedTask = jsonParser.decodeFromString<EvalTask>(jsonStr)
        assertNotNull(parsedTask)
        assertEquals(listOf("com.sankuai.meituan"), parsedTask.apps)
        assertEquals(Expect.Failed, parsedTask.expect)
    }
}

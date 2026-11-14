package com.hana.agent.core.eval

import com.hana.agent.core.watchdog.WatchdogVerdict
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 失败轨迹上下文输入。
 *
 * @property goal 任务原始目标描述
 * @property apps 涉及的应用包名或能力名
 * @property verdict 看门狗熔断判定结果（可选）
 * @property failureReason 失败的具体原因（可选）
 * @property lastObservedTexts 最终观察到的页面文本集合
 * @property executedActions 已执行的历史动作步骤序列
 * @property taskId 导出的 EvalTask 唯一标识（默认自增时间戳生成）
 * @property category 评测类别（默认 Barrier）
 * @property expect 评测预期状态（默认 Failed）
 */
@Serializable
data class FailureTrajectory(
    val goal: String,
    val apps: List<String>,
    val verdict: WatchdogVerdict? = null,
    val failureReason: String? = null,
    val lastObservedTexts: List<String> = emptyList(),
    val executedActions: List<String> = emptyList(),
    val taskId: String? = null,
    val category: EvalCategory = EvalCategory.Barrier,
    val expect: Expect = Expect.Failed,
)

/**
 * ClawGUI / AndroidWorld 失败轨迹脱敏沉淀导出器：
 * 运行时任务失败（如看门狗熔断、连续表单校验失败、导航漂移死锁等）后，
 * 自动对文本中的手机号、验证码、6位及以上纯数字、身份证号、邮箱等敏感隐私数据进行正则脱敏，
 * 转化为标准 [EvalTask] 与结构化 JSON，便于直接沉淀至 eval/tasks/ 持续补充回归测试集。
 */
object FailureTrajectoryExporter {

    // 正则表达式：身份证号、邮箱、中国11位手机号、6位及以上纯数字、验证码提示字段
    private val ID_CARD_REGEX = Regex("""\b\d{17}[\dXx]\b""")
    private val EMAIL_REGEX = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
    private val PHONE_REGEX = Regex("""\b(?:(?:\+?86)?1[3-9]\d{9})\b""")
    private val SIX_PLUS_DIGITS_REGEX = Regex("""\b\d{6,}\b""")
    private val VERIFICATION_CODE_REGEX = Regex("""(?i)(?:验证码|code|vcode)[:：\s]*([0-9a-zA-Z]{4,6})""")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * 对文本中的手机号、验证码、6位及以上纯数字、身份证号、邮箱进行正则脱敏（替换为 {REDACTED}）。
     */
    fun redact(text: String): String {
        if (text.isBlank()) return text
        var result = text

        // 1. 脱敏验证码对应的值
        result = VERIFICATION_CODE_REGEX.replace(result) { mr ->
            val full = mr.value
            val code = mr.groupValues[1]
            full.replace(code, "{REDACTED}")
        }
        // 2. 邮箱
        result = EMAIL_REGEX.replace(result, "{REDACTED}")
        // 3. 身份证号
        result = ID_CARD_REGEX.replace(result, "{REDACTED}")
        // 4. 手机号
        result = PHONE_REGEX.replace(result, "{REDACTED}")
        // 5. 6位及以上纯数字（银行卡号、长流水号等）
        result = SIX_PLUS_DIGITS_REGEX.replace(result, "{REDACTED}")

        return result
    }

    /**
     * 将包含敏感信息的失败轨迹导出为标准 [EvalTask] 实体对象。
     */
    fun export(trajectory: FailureTrajectory): EvalTask {
        val redactedGoal = redact(trajectory.goal)
        val redactedApps = trajectory.apps.map { redact(it) }.distinct()
        val redactedTexts = trajectory.lastObservedTexts.map { redact(it) }.distinct()
        val redactedActions = trajectory.executedActions.map { redact(it) }

        val taskId = trajectory.taskId ?: "fail_eval_${System.currentTimeMillis()}"

        // 构造确定性断言 Check
        val checks = mutableListOf<Check>()
        val candidateTokens = redactedTexts
            .filter { it.isNotBlank() && !it.contains("{REDACTED}") && it.length in 2..20 }
            .take(2)

        if (candidateTokens.isNotEmpty()) {
            checks.add(Check.AnswerContains(candidateTokens))
        } else {
            checks.add(Check.AskedUser(expected = false))
        }

        val noteBuilder = StringBuilder("Auto-generated from failure trajectory.")
        trajectory.verdict?.let { noteBuilder.append(" Verdict: $it.") }
        trajectory.failureReason?.let { noteBuilder.append(" Reason: ${redact(it)}.") }
        if (redactedActions.isNotEmpty()) {
            noteBuilder.append(" Actions: ").append(redactedActions.joinToString(" -> "))
        }

        return EvalTask(
            id = taskId,
            goal = redactedGoal,
            apps = redactedApps,
            category = trajectory.category,
            expect = trajectory.expect,
            checks = checks,
            note = noteBuilder.toString(),
        )
    }

    /**
     * 快捷参数导出函数。
     */
    fun export(
        goal: String,
        apps: List<String>,
        verdict: WatchdogVerdict? = null,
        failureReason: String? = null,
        lastObservedTexts: List<String> = emptyList(),
        executedActions: List<String> = emptyList(),
        taskId: String? = null,
    ): EvalTask = export(
        FailureTrajectory(
            goal = goal,
            apps = apps,
            verdict = verdict,
            failureReason = failureReason,
            lastObservedTexts = lastObservedTexts,
            executedActions = executedActions,
            taskId = taskId,
        )
    )

    /**
     * 将失败轨迹直接序列化为标准 JSON 字符串。
     */
    fun exportToJson(trajectory: FailureTrajectory): String {
        val task = export(trajectory)
        return json.encodeToString(task)
    }

    /**
     * 快捷 JSON 导出函数。
     */
    fun exportToJson(
        goal: String,
        apps: List<String>,
        verdict: WatchdogVerdict? = null,
        failureReason: String? = null,
        lastObservedTexts: List<String> = emptyList(),
        executedActions: List<String> = emptyList(),
        taskId: String? = null,
    ): String = exportToJson(
        FailureTrajectory(
            goal = goal,
            apps = apps,
            verdict = verdict,
            failureReason = failureReason,
            lastObservedTexts = lastObservedTexts,
            executedActions = executedActions,
            taskId = taskId,
        )
    )
}

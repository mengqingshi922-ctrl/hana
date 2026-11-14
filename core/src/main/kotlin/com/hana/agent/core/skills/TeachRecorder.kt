package com.hana.agent.core.skills

/**
 * 示范动作单步记录。
 *
 * @property seq 步骤序号（从 1 开始递增）
 * @property packageName 操作发生的宿主应用包名
 * @property actionType 动作类型（如 "click", "input", "scroll", "launch", "back" 等）
 * @property targetLabel 操作目标控件的文本、描述或标识
 * @property inputSample 输入的文字样本（若有）；结束录制时将强制脱敏
 * @property timestampMs 记录时间戳（毫秒）
 */
data class TeachActionStep(
    val seq: Int,
    val packageName: String,
    val actionType: String,
    val targetLabel: String,
    val inputSample: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * 示范录制完成产出的草稿技能结果。
 *
 * @property suggestedName 建议生成的技能英文标识名
 * @property markdownBody 包含规范 Frontmatter 与脱敏后步骤说明的 Markdown 文本
 * @property stepCount 录制的有效步骤总数
 * @property appsUsed 本次示范过程中涉及的应用包名列表
 */
data class TeachDraftResult(
    val suggestedName: String,
    val markdownBody: String,
    val stepCount: Int,
    val appsUsed: List<String>
)

/**
 * 「教 Hana 采集成技能」示范采集记录器。
 *
 * 负责收集用户的屏幕示范动作流，并在录制结束时调用 [SkillRedactor] 对文本输入、
 * 控件标识中的具体隐私值（如卡号、手机号、人名、输入文本）进行强脱敏，生成标准 Markdown 技能草稿。
 * 纯 JVM 实现，线程安全。
 */
class TeachRecorder {
    private val lock = Any()
    private val steps = mutableListOf<TeachActionStep>()

    /**
     * 追加记录一步示范动作。
     */
    fun recordStep(
        packageName: String,
        actionType: String,
        targetLabel: String,
        inputSample: String? = null,
        timestampMs: Long = System.currentTimeMillis()
    ): TeachActionStep = synchronized(lock) {
        val nextSeq = steps.size + 1
        val step = TeachActionStep(
            seq = nextSeq,
            packageName = packageName.trim(),
            actionType = actionType.trim(),
            targetLabel = targetLabel.trim(),
            inputSample = inputSample?.trim(),
            timestampMs = timestampMs
        )
        steps.add(step)
        return step
    }

    /**
     * 获取当前已记录的步骤快照。
     */
    fun currentSteps(): List<TeachActionStep> = synchronized(lock) {
        return steps.toList()
    }

    /**
     * 清除所有已记录的动作步骤。
     */
    fun clear(): Unit = synchronized(lock) {
        steps.clear()
    }

    /**
     * 停止采集并生成脱敏的技能草稿。
     *
     * @param suggestedName 建议的技能标识名（英文连字符命名）
     * @param display 技能中文显示名称
     * @return 包含 Markdown 正文的技能草稿构建结果
     */
    fun finishAndBuildDraft(
        suggestedName: String = "custom-teach-skill",
        display: String = "示范录制技能"
    ): TeachDraftResult = synchronized(lock) {
        val capturedSteps = steps.toList()
        val appsUsed = capturedSteps.map { it.packageName }.filter { it.isNotBlank() }.distinct()

        // 1. 搜集用户输入样本作为敏感字面量候选（保留按钮/控件名称，控件内数字/金额仍由 scrubNumbers 兜底脱敏）
        val rawTexts = mutableListOf<String>()
        capturedSteps.forEach { step ->
            step.inputSample?.let { if (it.isNotBlank()) rawTexts.add(it) }
        }

        // 2. 提取需要脱敏的具体字面量
        val literals = SkillRedactor.textLiterals(rawTexts)

        // 3. 构建原始 Markdown 草稿文本
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("name: ${suggestedName.trim().lowercase()}")
        sb.appendLine("display: ${display.trim()}")
        if (appsUsed.isNotEmpty()) {
            sb.appendLine("apps:")
            appsUsed.forEach { app -> sb.appendLine("  - $app") }
        }
        sb.appendLine("learned_from: teach_recording")
        sb.appendLine("source: user")
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## 示范录制技能说明")
        sb.appendLine("由用户示范操作录制生成的技能草稿，执行过程已自动完成隐私脱敏。")
        sb.appendLine()
        sb.appendLine("## 执行步骤")
        if (capturedSteps.isEmpty()) {
            sb.appendLine("（未记录到任何操作步骤）")
        } else {
            capturedSteps.forEach { step ->
                val actionDesc = when (step.actionType.lowercase()) {
                    "click", "tap" -> "点击控件「${step.targetLabel}」"
                    "input", "type" -> "在「${step.targetLabel}」输入「${step.inputSample ?: ""}」"
                    "scroll_down" -> "向下滑动屏幕"
                    "scroll_up" -> "向上滑动屏幕"
                    "launch" -> "打开应用"
                    "back" -> "返回上一级"
                    else -> "在「${step.targetLabel}」执行 ${step.actionType}"
                }
                sb.appendLine("${step.seq}. [${step.packageName}] $actionDesc")
            }
        }

        val rawMarkdown = sb.toString()

        // 4. 调用 SkillRedactor 进行字面量替换与长数字串脱敏
        val redactedMarkdown = SkillRedactor.redact(rawMarkdown, literals)
        val finalMarkdown = SkillRedactor.scrubNumbers(redactedMarkdown, keepDigits = emptySet())

        return TeachDraftResult(
            suggestedName = suggestedName.trim().lowercase(),
            markdownBody = finalMarkdown,
            stepCount = capturedSteps.size,
            appsUsed = appsUsed
        )
    }
}

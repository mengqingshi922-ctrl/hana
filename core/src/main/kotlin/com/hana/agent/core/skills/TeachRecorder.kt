package com.hana.agent.core.skills

import com.hana.agent.core.action.ChainPredicateKind
import com.hana.agent.core.action.MacroStep
import com.hana.agent.core.replay.Anchors
import com.hana.agent.core.route.RouteGraph
import com.hana.agent.core.route.RouteGraphEdge
import com.hana.agent.core.route.RouteGraphNode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 示范动作单步记录。
 *
 * @property seq 步骤序号（从 1 开始递增）
 * @property packageName 操作发生的宿主应用包名
 * @property actionType 动作类型（如 "click", "input", "type_text", "scroll_down", "scroll_up", "launch", "back" 等）
 * @property targetLabel 操作目标控件的文本、描述或标识
 * @property inputSample 输入的文字样本（若有）；结束录制时将强制脱敏
 * @property timestampMs 记录时间戳（毫秒）
 * @property resourceId 控件稳定资源 ID（如 viewIdResourceName，向后兼容默认 null）
 * @property normalizedBounds 控件归一化屏幕坐标 [left, top, right, bottom]（0..999，向后兼容默认 null）
 */
data class TeachActionStep(
    val seq: Int,
    val packageName: String,
    val actionType: String,
    val targetLabel: String,
    val inputSample: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val resourceId: String? = null,
    val normalizedBounds: List<Int>? = null,
)

/**
 * 示范录制自动提炼的参数槽位描述。
 */
data class TeachParamSlot(
    val name: String,
    val stepSeq: Int,
    val targetLabel: String,
    val resourceId: String? = null,
)

/**
 * 可直接导出或转换为 [RouteGraph] 的结构化示范动作步骤描述。
 */
data class TeachStructuredStep(
    val seq: Int,
    val packageName: String,
    val tool: String,
    val actionType: String,
    val targetLabel: String,
    val resourceId: String? = null,
    val normalizedBounds: List<Int>? = null,
    val paramSlot: String? = null,
)

/**
 * 示范录制完成产出的草稿技能结果。
 *
 * @property suggestedName 建议生成的技能英文标识名
 * @property markdownBody 包含规范 Frontmatter 与脱敏后步骤说明的 Markdown 文本
 * @property stepCount 录制的有效步骤总数
 * @property appsUsed 本次示范过程中涉及的应用包名列表
 * @property paramSlots 自动识别并变量化的用户输入槽位列表
 * @property structuredSteps 可导出或转为 [RouteGraph] 的结构化步骤描述
 */
data class TeachDraftResult(
    val suggestedName: String,
    val markdownBody: String,
    val stepCount: Int,
    val appsUsed: List<String>,
    val paramSlots: List<TeachParamSlot> = emptyList(),
    val structuredSteps: List<TeachStructuredStep> = emptyList(),
) {
    /**
     * 将本次示范录制的结构化步骤编译为有向经验拓扑图谱 [RouteGraph]，
     * 便于后续直接纳入经验图谱缓存与回放体系。
     */
    fun toRouteGraph(graphId: String = "teach_$suggestedName"): RouteGraph {
        if (structuredSteps.isEmpty()) {
            val emptyNode = RouteGraphNode(
                nodeId = "n_empty",
                pageSignature = "teach:$suggestedName:empty",
                isTerminal = true,
            )
            return RouteGraph(
                graphId = graphId,
                intentKeyword = suggestedName,
                initialNodeId = emptyNode.nodeId,
                nodes = mapOf(emptyNode.nodeId to emptyNode),
                edges = emptyList(),
            )
        }

        val nodes = LinkedHashMap<String, RouteGraphNode>()
        val edges = mutableListOf<RouteGraphEdge>()

        structuredSteps.forEachIndexed { idx, step ->
            val fromId = "n_$idx"
            if (fromId !in nodes) {
                nodes[fromId] = RouteGraphNode(
                    nodeId = fromId,
                    pageSignature = "${step.packageName}:${step.resourceId ?: step.targetLabel}",
                    anchors = Anchors(
                        packageName = step.packageName.ifBlank { null },
                        texts = listOfNotNull(step.targetLabel.takeIf { it.isNotBlank() }),
                    ),
                    isTerminal = false,
                )
            }

            val toId = "n_${idx + 1}"
            val isLast = idx == structuredSteps.lastIndex
            if (isLast) {
                nodes[toId] = RouteGraphNode(
                    nodeId = toId,
                    pageSignature = "${step.packageName}:terminal",
                    anchors = Anchors(packageName = step.packageName.ifBlank { null }, texts = emptyList()),
                    isTerminal = true,
                )
            }

            val args = buildJsonObject {
                if (step.targetLabel.isNotBlank()) put("label", step.targetLabel)
                step.resourceId?.let { put("resourceId", it) }
                step.normalizedBounds?.takeIf { it.size == 4 }?.let { b ->
                    put("bounds", buildJsonArray { b.forEach { add(JsonPrimitive(it)) } })
                }
                step.paramSlot?.let { slot ->
                    put("text", "{{$slot}}")
                }
                when (step.actionType.lowercase()) {
                    "scroll_down" -> {
                        put("startX", 500)
                        put("startY", 780)
                        put("endX", 500)
                        put("endY", 260)
                    }
                    "scroll_up" -> {
                        put("startX", 500)
                        put("startY", 260)
                        put("endX", 500)
                        put("endY", 780)
                    }
                }
            }

            val predicate = when {
                step.paramSlot != null -> ChainPredicateKind.TEXT_UPDATED
                step.actionType.lowercase() in setOf("click", "tap", "long_press") -> ChainPredicateKind.ELEMENT_APPEARED
                else -> ChainPredicateKind.NONE
            }

            edges += RouteGraphEdge(
                edgeId = "e_${idx + 1}",
                fromNodeId = fromId,
                toNodeId = toId,
                action = MacroStep(
                    tool = step.tool,
                    args = args,
                    predicate = predicate,
                    expectText = step.targetLabel.takeIf { it.isNotBlank() },
                ),
            )
        }

        return RouteGraph(
            graphId = graphId,
            intentKeyword = suggestedName,
            initialNodeId = "n_0",
            nodes = nodes,
            edges = edges,
        )
    }
}

/**
 * 「教 Hana 采集成技能」示范采集记录器。
 *
 * 负责收集用户的屏幕示范动作流，并在录制结束时调用 [SkillRedactor] 对文本输入、
 * 控件标识中的具体隐私值（如卡号、手机号、人名、输入文本）进行强脱敏，生成带参数槽位
 * 与可转 [RouteGraph] 结构化步骤的标准 Markdown 技能草稿。
 * 纯 JVM 实现，线程安全。
 */
class TeachRecorder {
    private val lock = Any()
    private val steps = mutableListOf<TeachActionStep>()

    /**
     * 追加记录一步示范动作。
     * 若连续同控件的文本输入动作（`type_text` / `input` / `type`）间隔小于 [INPUT_MERGE_WINDOW_MS]，
     * 将自动覆盖合并为最终完整文本，避免逐字打字产生冗余步骤。
     */
    fun recordStep(
        packageName: String,
        actionType: String,
        targetLabel: String,
        inputSample: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
        resourceId: String? = null,
        normalizedBounds: List<Int>? = null,
    ): TeachActionStep = synchronized(lock) {
        val cleanPkg = packageName.trim()
        val cleanAction = actionType.trim()
        val cleanLabel = targetLabel.trim()
        val cleanInput = inputSample?.trim()
        val cleanResourceId = resourceId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanBounds = normalizedBounds?.takeIf { it.size == 4 }?.toList()

        val last = steps.lastOrNull()
        if (last != null &&
            isTextInputAction(last.actionType) &&
            isTextInputAction(cleanAction) &&
            last.packageName == cleanPkg &&
            isSameTargetControl(last, cleanLabel, cleanResourceId, cleanBounds) &&
            (timestampMs - last.timestampMs) in 0 until INPUT_MERGE_WINDOW_MS
        ) {
            val merged = last.copy(
                actionType = cleanAction,
                targetLabel = cleanLabel.ifBlank { last.targetLabel },
                inputSample = cleanInput ?: last.inputSample,
                timestampMs = timestampMs,
                resourceId = cleanResourceId ?: last.resourceId,
                normalizedBounds = cleanBounds ?: last.normalizedBounds,
            )
            steps[steps.lastIndex] = merged
            return merged
        }

        val nextSeq = steps.size + 1
        val step = TeachActionStep(
            seq = nextSeq,
            packageName = cleanPkg,
            actionType = cleanAction,
            targetLabel = cleanLabel,
            inputSample = cleanInput,
            timestampMs = timestampMs,
            resourceId = cleanResourceId,
            normalizedBounds = cleanBounds,
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
     * @return 包含 Markdown 正文、参数槽位与结构化步骤描述的技能草稿构建结果
     */
    fun finishAndBuildDraft(
        suggestedName: String = "custom-teach-skill",
        display: String = "示范录制技能"
    ): TeachDraftResult = synchronized(lock) {
        val capturedSteps = steps.toList()
        val cleanName = suggestedName.trim().lowercase()
        val appsUsed = capturedSteps.map { it.packageName }.filter { it.isNotBlank() }.distinct()

        // 1. 搜集用户输入样本作为敏感字面量候选（保留按钮/控件名称，控件内数字/金额仍由 scrubNumbers 兜底脱敏）
        val rawTexts = mutableListOf<String>()
        capturedSteps.forEach { step ->
            step.inputSample?.let { if (it.isNotBlank()) rawTexts.add(it) }
        }

        // 2. 提取需要脱敏的具体字面量
        val literals = SkillRedactor.textLiterals(rawTexts)

        // 3. 自动识别可变量化的用户输入样本，分配参数槽位（input_1, input_2, ...）并构建结构化步骤
        val paramSlots = mutableListOf<TeachParamSlot>()
        val structuredSteps = mutableListOf<TeachStructuredStep>()
        val stepSlotBySeq = HashMap<Int, String>()

        capturedSteps.forEach { step ->
            val safeLabel = scrubText(step.targetLabel, literals)
            val isVariableInput = isTextInputAction(step.actionType) && !step.inputSample.isNullOrBlank()
            val slotName = if (isVariableInput) {
                "input_${paramSlots.size + 1}".also { name ->
                    paramSlots += TeachParamSlot(
                        name = name,
                        stepSeq = step.seq,
                        targetLabel = safeLabel,
                        resourceId = step.resourceId,
                    )
                    stepSlotBySeq[step.seq] = name
                }
            } else {
                null
            }
            structuredSteps += TeachStructuredStep(
                seq = step.seq,
                packageName = step.packageName,
                tool = mapActionToTool(step.actionType),
                actionType = step.actionType,
                targetLabel = safeLabel,
                resourceId = step.resourceId,
                normalizedBounds = step.normalizedBounds,
                paramSlot = slotName,
            )
        }

        // 4. 构建原始 Markdown 策略文档（使用安全令牌保护 {{input_N}} 槽位不被两位数字脱敏误伤）
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("name: $cleanName")
        sb.appendLine("display: ${display.trim()}")
        if (appsUsed.isNotEmpty()) {
            sb.appendLine("apps:")
            appsUsed.forEach { app -> sb.appendLine("  - $app") }
        }
        if (paramSlots.isNotEmpty()) {
            sb.appendLine("params:")
            paramSlots.forEach { slot -> sb.appendLine("  - ${slotToken(slot.name)}") }
        }
        sb.appendLine("learned_from: teach_recording")
        sb.appendLine("source: user")
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## 示范录制技能说明")
        sb.appendLine("由用户示范操作录制生成的技能草稿，执行过程已自动完成隐私脱敏与参数变量化。")
        if (paramSlots.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## 参数槽位")
            paramSlots.forEach { slot ->
                val anchorNote = slot.resourceId?.let { " · 控件ID `$it`" } ?: ""
                sb.appendLine("- `${slotToken(slot.name)}`：第 ${slot.stepSeq} 步「${slot.targetLabel}」的可变输入参数$anchorNote")
            }
        }
        sb.appendLine()
        sb.appendLine("## 执行步骤")
        if (capturedSteps.isEmpty()) {
            sb.appendLine("（未记录到任何操作步骤）")
        } else {
            capturedSteps.forEach { step ->
                val slotHint = stepSlotBySeq[step.seq]?.let { "（参数槽位：${slotToken(it)}）" } ?: ""
                val anchorHint = step.resourceId?.let { " [id=$it]" } ?: ""
                val actionDesc = when (step.actionType.lowercase()) {
                    "click", "tap" -> "点击控件「${step.targetLabel}」$anchorHint"
                    "long_press" -> "长按控件「${step.targetLabel}」$anchorHint"
                    "input", "type", "type_text" -> "在「${step.targetLabel}」输入「${step.inputSample ?: ""}」$slotHint$anchorHint"
                    "scroll_down" -> "向下滑动屏幕$anchorHint"
                    "scroll_up" -> "向上滑动屏幕$anchorHint"
                    "launch" -> "打开应用"
                    "back" -> "返回上一级"
                    else -> "在「${step.targetLabel}」执行 ${step.actionType}$anchorHint"
                }
                sb.appendLine("${step.seq}. [${step.packageName}] $actionDesc")
            }
        }
        if (structuredSteps.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## 结构化步骤（RouteGraph 兼容）")
            structuredSteps.forEach { s ->
                val boundsPart = s.normalizedBounds?.joinToString(",", "[", "]") ?: "-"
                val ridPart = s.resourceId ?: "-"
                val slotPart = s.paramSlot?.let { slotToken(it) } ?: "-"
                sb.appendLine("- step=${s.seq} | tool=${s.tool} | pkg=${s.packageName} | rid=$ridPart | bounds=$boundsPart | slot=$slotPart")
            }
        }

        val rawMarkdown = sb.toString()

        // 5. 调用 SkillRedactor 进行字面量替换与长数字串脱敏，随后还原参数槽位标记
        val redactedMarkdown = SkillRedactor.redact(rawMarkdown, literals)
        val scrubbedMarkdown = SkillRedactor.scrubNumbers(redactedMarkdown, keepDigits = emptySet())
        val finalMarkdown = restoreSlotTokens(scrubbedMarkdown, paramSlots)

        return TeachDraftResult(
            suggestedName = cleanName,
            markdownBody = finalMarkdown,
            stepCount = capturedSteps.size,
            appsUsed = appsUsed,
            paramSlots = paramSlots,
            structuredSteps = structuredSteps,
        )
    }

    private fun scrubText(text: String, literals: Set<String>): String {
        if (text.isBlank()) return text
        val redacted = SkillRedactor.redact(text, literals)
        return SkillRedactor.scrubNumbers(redacted, keepDigits = emptySet())
    }

    private fun slotToken(slotName: String): String {
        // 将数字编码为字母 A-J 并加结束标记，既避免两位数字被 scrubNumbers 误伤，也避免 input_1 与 input_10 前缀冲突
        val encoded = slotName.map { ch ->
            if (ch in '0'..'9') ('A' + (ch - '0')) else ch
        }.joinToString("")
        return "HANA_SLOT_${encoded}_END"
    }

    private fun restoreSlotTokens(markdown: String, slots: List<TeachParamSlot>): String {
        var out = markdown
        slots.forEach { slot ->
            val token = slotToken(slot.name)
            // 参数槽位提示在正文中统一渲染为 {{input_1}} 格式，frontmatter params 中保留槽位名
            out = out.replace("  - $token", "  - ${slot.name}")
            out = out.replace(token, "{{${slot.name}}}")
        }
        return out
    }

    private fun isTextInputAction(actionType: String): Boolean =
        actionType.lowercase() in TEXT_INPUT_ACTIONS

    private fun isSameTargetControl(
        last: TeachActionStep,
        label: String,
        resourceId: String?,
        bounds: List<Int>?,
    ): Boolean {
        if (last.resourceId != null && resourceId != null) {
            if (last.resourceId != resourceId) return false
            return last.normalizedBounds == null || bounds == null || last.normalizedBounds == bounds
        }
        if (last.normalizedBounds != null && bounds != null) {
            return last.normalizedBounds == bounds
        }
        if (last.targetLabel.isNotBlank() && last.targetLabel == label) {
            return true
        }
        // 无 resourceId 与 bounds 时，若控件文案本身就是上一次输入文本的前缀/增量变化，也视作同一输入框连续打字
        val prevInput = last.inputSample.orEmpty()
        return prevInput.isNotEmpty() && last.targetLabel == prevInput && label.startsWith(prevInput)
    }

    private fun mapActionToTool(actionType: String): String = when (actionType.lowercase()) {
        "click", "tap" -> "phone_tap"
        "long_press" -> "phone_long_press"
        "input", "type", "type_text" -> "phone_type_text"
        "scroll_down", "scroll_up" -> "phone_swipe"
        "launch" -> "phone_launch_app"
        "back" -> "phone_back"
        else -> "phone_${actionType.lowercase()}"
    }

    companion object {
        /** 连续同控件输入动作的自动合并时间窗口（毫秒）。 */
        const val INPUT_MERGE_WINDOW_MS = 1_200L
        private val TEXT_INPUT_ACTIONS = setOf("input", "type", "type_text")
    }
}

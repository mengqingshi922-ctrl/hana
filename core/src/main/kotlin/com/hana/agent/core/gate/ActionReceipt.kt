package com.hana.agent.core.gate

import kotlinx.serialization.Serializable

/**
 * 可逆操作类型，供 `:app` 层路由至对应的一键撤销处理器。
 */
@Serializable
enum class UndoActionType {
    /** 取消或删除刚设置/修改的闹钟。 */
    CANCEL_ALARM,
    /** 从购物车移除刚加入的商品。 */
    REMOVE_FROM_CART,
    /** 删除或撤销刚写入的日历日程。 */
    DELETE_CALENDAR_EVENT,
    /** 还原系统开关（如 Wi-Fi、蓝牙、免打扰、定位、亮度等）。 */
    RESTORE_SYSTEM_SETTING,
    /** 其他自定义可逆操作（由 `reversibleParams` 携带参数）。 */
    CUSTOM,
}

/**
 * 单个可逆操作的一键撤销提示，供 `:app` 渲染“外卖小票式”审计回执中的撤销按钮并执行回滚。
 *
 * @property type 可逆操作类型。
 * @property label 展示给用户的撤销按钮文案（例如「撤销 07:30 闹钟」）。
 * @property targetApp 执行该操作及撤销目标所在 App 包名（全局系统设置等场景可为 `null`）。
 * @property reversibleParams 撤销所需的结构化键值参数（如 `alarmId`、`hour`、`minute`、`eventId`、`settingKey`、`previousValue`）。
 */
@Serializable
data class UndoActionHint(
    val type: UndoActionType,
    val label: String,
    val targetApp: String? = null,
    val reversibleParams: Map<String, String> = emptyMap(),
) {
    companion object {
        /** 构造「取消闹钟」撤销提示。 */
        fun alarm(
            label: String,
            hour: Int,
            minute: Int,
            targetApp: String? = "com.android.deskclock",
            alarmId: String? = null,
        ): UndoActionHint = UndoActionHint(
            type = UndoActionType.CANCEL_ALARM,
            label = label,
            targetApp = targetApp,
            reversibleParams = buildMap {
                put("hour", hour.toString())
                put("minute", minute.toString())
                if (!alarmId.isNullOrBlank()) put("alarmId", alarmId)
            },
        )

        /** 构造「移出购物车」撤销提示。 */
        fun cartItem(
            label: String,
            itemName: String,
            targetApp: String,
            itemId: String? = null,
        ): UndoActionHint = UndoActionHint(
            type = UndoActionType.REMOVE_FROM_CART,
            label = label,
            targetApp = targetApp,
            reversibleParams = buildMap {
                put("itemName", itemName)
                if (!itemId.isNullOrBlank()) put("itemId", itemId)
            },
        )

        /** 构造「删除日历日程」撤销提示。 */
        fun calendarEvent(
            label: String,
            title: String,
            startTimeMs: Long,
            targetApp: String? = "com.android.calendar",
            eventId: String? = null,
        ): UndoActionHint = UndoActionHint(
            type = UndoActionType.DELETE_CALENDAR_EVENT,
            label = label,
            targetApp = targetApp,
            reversibleParams = buildMap {
                put("title", title)
                put("startTimeMs", startTimeMs.toString())
                if (!eventId.isNullOrBlank()) put("eventId", eventId)
            },
        )

        /** 构造「还原系统开关」撤销提示。 */
        fun systemSetting(
            label: String,
            settingKey: String,
            previousValue: String,
            targetApp: String? = "com.android.settings",
        ): UndoActionHint = UndoActionHint(
            type = UndoActionType.RESTORE_SYSTEM_SETTING,
            label = label,
            targetApp = targetApp,
            reversibleParams = mapOf(
                "settingKey" to settingKey,
                "previousValue" to previousValue,
            ),
        )
    }
}

/**
 * 审计回执单中的单个关键动作步记录。
 *
 * @property stepIndex 动作序号（从 1 开始递增）。
 * @property actionSummary 面向用户的关键动作摘要。
 * @property targetApp 目标 App 包名或网页站点标识。
 * @property riskTier 该步命中的三级风险门禁等级。
 * @property gateTriggered 是否触发了 L2/L3 人工安全门禁（L1 自动放行默认为 `false`）。
 * @property gateDecision 门禁处理结论（例如 `"auto"`、`"countdown_approved"`、`"slide_approved"`、`"declined"`、`"blocked_toctou"`）。
 * @property fromUntrustedSource 该指令是否源于外部网页/群聊等不可信数据血缘。
 * @property durationMs 该关键步执行耗时（毫秒）。
 * @property timestampMs 该步记录时间戳（毫秒）。
 * @property undoHint 若该步支持一键撤销，附带撤销提示；否则为 `null`。
 */
@Serializable
data class ReceiptStep(
    val stepIndex: Int,
    val actionSummary: String,
    val targetApp: String? = null,
    val riskTier: RiskTier = RiskTier.L1_AUTO,
    val gateTriggered: Boolean = riskTier != RiskTier.L1_AUTO,
    val gateDecision: String = if (riskTier == RiskTier.L1_AUTO) "auto" else "approved",
    val fromUntrustedSource: Boolean = false,
    val durationMs: Long = 0L,
    val timestampMs: Long = 0L,
    val undoHint: UndoActionHint? = null,
)

/**
 * 事后“外卖小票式”审计回执单。
 * 记录任务 ID、总耗时、关键动作步列表（含 L2/L3 门禁触发状态）以及可逆操作的一键撤销提示。
 */
@Serializable
data class ActionReceipt(
    val taskId: String,
    val goal: String = "",
    val totalDurationMs: Long,
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
    val succeeded: Boolean = true,
    val steps: List<ReceiptStep> = emptyList(),
    val undoHints: List<UndoActionHint> = steps.mapNotNull { it.undoHint }.distinct(),
    val touchedApps: List<String> = steps.mapNotNull { it.targetApp?.takeIf(String::isNotBlank) }.distinct(),
    val l2GateCount: Int = steps.count { it.gateTriggered && it.riskTier == RiskTier.L2_SOFT_COUNTDOWN },
    val l3GateCount: Int = steps.count { it.gateTriggered && it.riskTier == RiskTier.L3_HARD_SLIDE },
    val untrustedSourceSteps: Int = steps.count { it.fromUntrustedSource },
) {
    /** 是否触发过任何 L2 或 L3 安全门禁。 */
    val hasGateEvents: Boolean get() = l2GateCount > 0 || l3GateCount > 0

    /** 是否存在至少一项可一键撤销的操作。 */
    val hasUndoActions: Boolean get() = undoHints.isNotEmpty()

    /** 生成纯文本“外卖小票式”可读摘要，便于日志审计或气泡快速预览。 */
    fun formatSummary(): String = buildString {
        appendLine("=== Hana 执行回执 ($taskId) ===")
        if (goal.isNotBlank()) appendLine("目标: $goal")
        appendLine("状态: ${if (succeeded) "已完成" else "未完成"} · 耗时: ${totalDurationMs}ms")
        appendLine("门禁统计: L2 倒计时 $l2GateCount 次 / L3 强授权 $l3GateCount 次 / 不可信源 $untrustedSourceSteps 次")
        if (steps.isNotEmpty()) {
            appendLine("关键动作 (${steps.size}):")
            for (s in steps) {
                val gateFlag = if (s.gateTriggered) "[${s.riskTier.name}:${s.gateDecision}]" else "[${s.riskTier.name}]"
                val appFlag = s.targetApp?.let { " @ $it" } ?: ""
                appendLine("  ${s.stepIndex}. $gateFlag ${s.actionSummary}$appFlag")
            }
        }
        if (undoHints.isNotEmpty()) {
            appendLine("可一键撤销 (${undoHints.size}):")
            for (u in undoHints) {
                appendLine("  - [${u.type.name}] ${u.label}")
            }
        }
    }.trimEnd()
}

/**
 * 线程安全的 [ActionReceipt] 增量构建器，在任务执行过程中收集关键动作与撤销提示，结束时输出冻结回执单。
 */
class ActionReceiptBuilder(
    val taskId: String,
    val goal: String = "",
    private val startedAtMs: Long = System.currentTimeMillis(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val steps = mutableListOf<ReceiptStep>()
    private val extraUndoHints = mutableListOf<UndoActionHint>()

    /** 记录一条关键动作步；若附带 [undoHint]，构建回执时会自动汇总到 [ActionReceipt.undoHints]。 */
    @Synchronized
    fun recordStep(
        actionSummary: String,
        targetApp: String? = null,
        riskTier: RiskTier = RiskTier.L1_AUTO,
        gateTriggered: Boolean = riskTier != RiskTier.L1_AUTO,
        gateDecision: String = if (riskTier == RiskTier.L1_AUTO) "auto" else "approved",
        fromUntrustedSource: Boolean = false,
        durationMs: Long = 0L,
        undoHint: UndoActionHint? = null,
    ): ActionReceiptBuilder {
        val step = ReceiptStep(
            stepIndex = steps.size + 1,
            actionSummary = actionSummary.trim(),
            targetApp = targetApp?.takeIf { it.isNotBlank() },
            riskTier = riskTier,
            gateTriggered = gateTriggered,
            gateDecision = gateDecision,
            fromUntrustedSource = fromUntrustedSource,
            durationMs = durationMs.coerceAtLeast(0L),
            timestampMs = clock(),
            undoHint = undoHint,
        )
        steps += step
        return this
    }

    /** 根据 [ConfirmationRequest] 快速记录一次已触发门禁的关键动作步。 */
    @Synchronized
    fun recordGateRequest(
        request: ConfirmationRequest,
        approved: Boolean,
        gateDecision: String? = null,
        durationMs: Long = 0L,
        undoHint: UndoActionHint? = null,
    ): ActionReceiptBuilder {
        val summary = buildString {
            append(request.action.name)
            if (request.target.isNotBlank()) append(" -> ").append(request.target)
            if (!request.amount.isNullOrBlank()) append(" (").append(request.amount).append(')')
        }
        val decision = gateDecision ?: when {
            !approved -> "declined"
            request.riskTier == RiskTier.L2_SOFT_COUNTDOWN -> "countdown_approved"
            request.riskTier == RiskTier.L3_HARD_SLIDE -> "slide_approved"
            else -> "auto"
        }
        return recordStep(
            actionSummary = summary,
            targetApp = request.app,
            riskTier = request.riskTier,
            gateTriggered = request.riskTier != RiskTier.L1_AUTO,
            gateDecision = decision,
            fromUntrustedSource = request.fromUntrustedSource,
            durationMs = durationMs,
            undoHint = undoHint,
        )
    }

    /** 单独追加一条可逆操作的一键撤销提示。 */
    @Synchronized
    fun addUndoHint(hint: UndoActionHint): ActionReceiptBuilder {
        extraUndoHints += hint
        return this
    }

    /** 当前已记录的关键动作数。 */
    val stepCount: Int @Synchronized get() = steps.size

    /** 生成不可变的 [ActionReceipt] 审计回执单。 */
    @Synchronized
    fun build(
        succeeded: Boolean = true,
        finishedAtMs: Long = clock(),
        totalDurationMs: Long = (finishedAtMs - startedAtMs).coerceAtLeast(0L),
    ): ActionReceipt {
        val snapshotSteps = steps.toList()
        val combinedUndo = (snapshotSteps.mapNotNull { it.undoHint } + extraUndoHints).distinct()
        return ActionReceipt(
            taskId = taskId,
            goal = goal,
            totalDurationMs = totalDurationMs.coerceAtLeast(0L),
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs,
            succeeded = succeeded,
            steps = snapshotSteps,
            undoHints = combinedUndo,
        )
    }
}

package com.hana.agent.core.subgoal

import com.hana.agent.core.tool.ToolExecution
import kotlinx.serialization.Serializable

@Serializable
enum class GoalStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED
}

@Serializable
data class TrackedGoal(
    val id: String,
    val title: String,
    var status: GoalStatus = GoalStatus.PENDING,
    val expectApp: String? = null,
    val expectKeyword: String? = null,
)

/**
 * 闭环子目标生命周期状态机：
 * 负责任务拆解、状态推进、动作切面断言核验与断点自动归档。
 */
class GoalLifecycleTracker(
    private val checkpointManager: SubgoalCheckpointManager? = null,
) {
    private val goals = mutableListOf<TrackedGoal>()
    private var activeIndex = 0
    var consecutiveFailures: Int = 0
        private set

    val currentGoal: TrackedGoal? get() = goals.getOrNull(activeIndex)
    val allGoals: List<TrackedGoal> get() = goals.toList()
    val trackedCount: Int get() = goals.size
    val isAllCompleted: Boolean get() = goals.isNotEmpty() && goals.all { it.status == GoalStatus.COMPLETED }

    fun initialize(task: String, explicitPlan: List<String> = emptyList()) {
        goals.clear()
        activeIndex = 0
        consecutiveFailures = 0
        val items = if (explicitPlan.isNotEmpty()) {
            explicitPlan
        } else {
            val goalParts = task.split(Regex("(?:\\n\\s*\\d+[.、]\\s*|\\s*->\\s*|\\s*(?:然后|接着|再去|最后)\\s*)"))
                .map { it.trim() }
                .filter { it.length >= 2 }
            if (goalParts.size >= 2 && (task.contains("->") || task.contains("\n") || task.length >= 16)) {
                goalParts.take(6)
            } else {
                emptyList()
            }
        }
        goals.addAll(items.mapIndexed { idx, title ->
            TrackedGoal(
                id = "goal_$idx",
                title = title,
                status = if (idx == 0) GoalStatus.IN_PROGRESS else GoalStatus.PENDING
            )
        })
    }

    fun onActionExecuted(action: ToolExecution, currentPackage: String?, pageSignature: Long = 0L, historySize: Int = 0) {
        val current = currentGoal ?: return
        if (action.content["ok"]?.toString() == "false") {
            consecutiveFailures++
            return
        }

        // 依据工具执行结果断言子目标达成
        val satisfied = evaluateSatisfaction(current, action, currentPackage)
        if (satisfied) {
            consecutiveFailures = 0
            current.status = GoalStatus.COMPLETED
            // 稳态断点自动归档
            checkpointManager?.saveCheckpoint(
                SubgoalCheckpoint(
                    checkpointId = "cp_${current.id}",
                    subgoalName = current.title,
                    packageName = currentPackage.orEmpty(),
                    uiSnapshotHash = pageSignature,
                    registers = com.hana.agent.core.bus.RegisterBus.snapshot(),
                    historyMessageCount = historySize
                )
            )
            activeIndex++
            goals.getOrNull(activeIndex)?.status = GoalStatus.IN_PROGRESS
        } else {
            consecutiveFailures++
        }
    }

    fun advanceManual(specifiedIndex: Int? = null, historySize: Int = 0) {
        val current = currentGoal ?: return
        current.status = GoalStatus.COMPLETED
        checkpointManager?.saveCheckpoint(
            SubgoalCheckpoint(
                checkpointId = "cp_${current.id}",
                subgoalName = current.title,
                packageName = "",
                uiSnapshotHash = 0L,
                registers = com.hana.agent.core.bus.RegisterBus.snapshot(),
                historyMessageCount = historySize
            )
        )
        if (specifiedIndex != null) {
            activeIndex = specifiedIndex.coerceIn(0, goals.size)
        } else {
            activeIndex = (activeIndex + 1).coerceAtMost(goals.size)
        }
        goals.getOrNull(activeIndex)?.status = GoalStatus.IN_PROGRESS
        consecutiveFailures = 0
    }

    fun resetFailures() {
        consecutiveFailures = 0
    }

    private fun evaluateSatisfaction(goal: TrackedGoal, action: ToolExecution, currentPackage: String?): Boolean {
        if (goal.expectApp != null && currentPackage == goal.expectApp) return true
        val text = action.content.toString()
        if (goal.expectKeyword != null && text.contains(goal.expectKeyword)) return true
        if (text.contains("SUBGOAL_DONE") || text.contains("subgoal_completed") || text.contains(goal.title)) return true
        if (goal.expectApp != null && currentPackage != null && currentPackage.contains(goal.expectApp)) return true
        return false
    }

    fun formatProgressTree(): String = buildString {
        append("Subgoals: ")
        append(goals.mapIndexed { _, g ->
            val marker = when (g.status) {
                GoalStatus.COMPLETED -> "[✓]"
                GoalStatus.IN_PROGRESS -> "[►]"
                GoalStatus.FAILED -> "[✗]"
                else -> "[ ]"
            }
            "$marker ${g.title}"
        }.joinToString(" -> "))
    }
}

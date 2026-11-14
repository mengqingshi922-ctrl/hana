package com.hana.agent.core.skills

import com.hana.agent.core.agent.FailKind
import com.hana.agent.core.agent.RunOutcome
import com.hana.agent.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证技能升级与停用门槛改进:
 * 1. 试用技能不再因单次 failed 停用，而是与正式技能统一采用 shouldSuspend 门槛;
 * 2. 试用技能仅在 verified 时转为 active;
 * 3. 任务因预算或 Token 耗尽失败时不归咎于技能，判定为 unreviewed。
 */
class SkillOutcomesUpgradeTest {

    @Test
    fun trialSkillDoesNotSuspendOnSingleFailure() {
        val draft = personalSkill("trial-skill", status = SkillStatus.trial)
        // 单次失败后状态仍为 trial
        val once = SkillOutcomes.record(draft, SkillResult.failed, followed = true)
        assertEquals("单次 failed 不应导致试用技能停用", SkillStatus.trial, once.status)
        assertEquals(listOf("failed"), once.outcomes)

        // 连续两次失败触发 shouldSuspend 门槛停用
        val twice = SkillOutcomes.record(once, SkillResult.failed, followed = true)
        assertEquals("连续两次 failed 达到门槛应停用", SkillStatus.suspended, twice.status)
        assertEquals(listOf("failed", "failed"), twice.outcomes)
    }

    @Test
    fun trialSkillOnlyBecomesActiveOnVerified() {
        val draft = personalSkill("trial-skill", status = SkillStatus.trial)
        val afterUnverified = SkillOutcomes.record(draft, SkillResult.unverified, followed = true)
        assertEquals(SkillStatus.trial, afterUnverified.status)

        val afterUnreviewed = SkillOutcomes.record(draft, SkillResult.unreviewed, followed = true)
        assertEquals(SkillStatus.trial, afterUnreviewed.status)

        val afterVerified = SkillOutcomes.record(draft, SkillResult.verified, followed = true)
        assertEquals(SkillStatus.active, afterVerified.status)
    }

    @Test
    fun budgetOrTokenDepletedTreatedAsUnreviewed() {
        // FailKind.Guard 算 unreviewed
        val guardFailed = TaskState.Failed("安全保护", FailKind.Guard)
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(guardFailed, RunOutcome.Unknown))

        // reason 包含中文 "预算"
        val budgetFailed = TaskState.Failed("任务由于预算耗尽中止", FailKind.GaveUp)
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(budgetFailed, RunOutcome.Unknown))

        // reason 包含英文 "budget" (大小写不敏感)
        val budgetEnFailed = TaskState.Failed("Exceeded max Budget limit", FailKind.StepLimit)
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(budgetEnFailed, RunOutcome.Unknown))

        // reason 包含 "token" (大小写不敏感)
        val tokenFailed = TaskState.Failed("Token limit reached for conversation", FailKind.GaveUp)
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(tokenFailed, RunOutcome.Unknown))
    }
}

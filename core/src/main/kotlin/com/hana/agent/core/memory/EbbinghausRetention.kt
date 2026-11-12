package com.hana.agent.core.memory

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * 基于 Ebbinghaus 遗忘曲线的记忆保持度与强化计算器。
 *
 * - 衰减公式：`R = max(0.3, exp(-deltaDays / strength))`
 * - 命中强化：每次被检索命中或使用时提升 [strength]，间隔越久后成功召回强化幅度越大（间隔效应），上限 [MAX_STRENGTH]。
 * - 归档评分：结合保持度、访问/使用频次与置顶标记，供容量达到上限时筛选低分老旧记忆转入冷归档。
 */
object EbbinghausRetention {
    const val MIN_RETENTION = 0.3
    const val DEFAULT_STRENGTH = 1.0
    const val MIN_STRENGTH = 0.25
    const val MAX_STRENGTH = 365.0
    const val MILLIS_PER_DAY = 86_400_000.0
    private const val BASE_REINFORCE_STEP = 1.0
    private const val SPACING_BONUS_FACTOR = 0.5
    private const val PINNED_ARCHIVE_SHIELD = 1_000.0

    /**
     * 计算记忆在当前时刻 [nowMs] 的保持度因子 `R ∈ [0.3, 1.0]`。
     *
     * @param lastTimeMs 最近一次访问/使用/更新时间戳（毫秒）
     * @param nowMs 当前时间戳（毫秒）
     * @param strength 当前记忆稳定度/强度（天）
     */
    fun retention(lastTimeMs: Long, nowMs: Long, strength: Double): Double {
        val safeStrength = strength.coerceAtLeast(MIN_STRENGTH)
        val deltaDays = (nowMs - lastTimeMs).coerceAtLeast(0L).toDouble() / MILLIS_PER_DAY
        return max(MIN_RETENTION, exp(-deltaDays / safeStrength))
    }

    /**
     * 命中/访问后强化记忆强度 [strength]。
     * 经过一定时间间隔后再次被命中，会获得更明显的间隔强化（Spacing Effect）。
     */
    fun reinforce(currentStrength: Double, lastTimeMs: Long?, nowMs: Long): Double {
        val base = currentStrength.coerceAtLeast(MIN_STRENGTH)
        val elapsedDays = if (lastTimeMs != null) {
            (nowMs - lastTimeMs).coerceAtLeast(0L).toDouble() / MILLIS_PER_DAY
        } else {
            0.0
        }
        val spacingBonus = if (elapsedDays >= 1.0) ln(1.0 + elapsedDays) * SPACING_BONUS_FACTOR else 0.0
        return (base + BASE_REINFORCE_STEP + spacingBonus).coerceAtMost(MAX_STRENGTH)
    }

    /**
     * 计算记忆的保留价值分（越低越优先转入冷归档）。
     */
    fun archivalScore(
        lastTimeMs: Long,
        nowMs: Long,
        strength: Double,
        accessCount: Int,
        useCount: Int = 0,
        pinned: Boolean = false,
    ): Double {
        val r = retention(lastTimeMs, nowMs, strength)
        val frequencyBoost = 1.0 + ln(1.0 + (accessCount + useCount).coerceAtLeast(0).toDouble())
        val pinBonus = if (pinned) PINNED_ARCHIVE_SHIELD else 0.0
        return r * frequencyBoost + pinBonus
    }
}

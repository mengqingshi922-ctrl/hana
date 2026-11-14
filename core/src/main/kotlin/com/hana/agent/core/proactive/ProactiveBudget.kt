package com.hana.agent.core.proactive

import java.time.LocalDate
import java.time.ZoneId

/**
 * 主动能力每日预算控制器。
 *
 * 用于控制主动分流与通知理解的判定频次和 Token 消耗：
 * - 每日最大判定次数（超额自动静默跳过）；
 * - 每日 Token 消耗上限；
 * - 跨自然日自动重置计数；
 * - 线程安全。
 *
 * @property maxDailyDecisions 每日最大允许判定次数（默认 100 次）
 * @property maxDailyTokens 每日最大允许消耗 Token 数（默认 50,000）
 */
class ProactiveBudget(
    private val maxDailyDecisions: Int = 100,
    private val maxDailyTokens: Int = 50_000
) {
    private val lock = Any()

    private var currentDate: String = todayDateString()
    private var decisionsUsed: Int = 0
    private var tokensUsed: Int = 0

    /**
     * 判断当前是否仍在预算额度内，可以执行一次主动判定。
     *
     * @param nowMs 当前时间戳毫秒
     * @return true 表示仍在预算额度内，false 表示已超额应静默跳过
     */
    fun canDecide(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        checkAndResetIfNewDay(nowMs)
        return decisionsUsed < maxDailyDecisions && tokensUsed < maxDailyTokens
    }

    /**
     * 记录一次判定及所消耗的 Token。
     *
     * @param tokens 本次判定消耗的估算 Token 数（非负数）
     * @param nowMs 当前时间戳毫秒
     * @return true 表示成功扣减并在额度内；false 表示已超额
     */
    fun recordDecision(tokens: Int = 0, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        checkAndResetIfNewDay(nowMs)
        if (decisionsUsed >= maxDailyDecisions || tokensUsed >= maxDailyTokens) {
            return false
        }
        decisionsUsed++
        tokensUsed += tokens.coerceAtLeast(0)
        return true
    }

    /**
     * 获取当日剩余判定次数。
     */
    fun remainingDecisions(nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        checkAndResetIfNewDay(nowMs)
        return (maxDailyDecisions - decisionsUsed).coerceAtLeast(0)
    }

    /**
     * 获取当日剩余 Token 数。
     */
    fun remainingTokens(nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        checkAndResetIfNewDay(nowMs)
        return (maxDailyTokens - tokensUsed).coerceAtLeast(0)
    }

    /**
     * 手动重置预算计数器。
     */
    fun reset(nowMs: Long = System.currentTimeMillis()): Unit = synchronized(lock) {
        currentDate = toDateString(nowMs)
        decisionsUsed = 0
        tokensUsed = 0
    }

    /**
     * 获取当前预算状态快照。
     */
    fun snapshot(nowMs: Long = System.currentTimeMillis()): BudgetSnapshot = synchronized(lock) {
        checkAndResetIfNewDay(nowMs)
        return BudgetSnapshot(
            date = currentDate,
            decisionsUsed = decisionsUsed,
            maxDailyDecisions = maxDailyDecisions,
            tokensUsed = tokensUsed,
            maxDailyTokens = maxDailyTokens
        )
    }

    private fun checkAndResetIfNewDay(nowMs: Long) {
        val today = toDateString(nowMs)
        if (today != currentDate) {
            currentDate = today
            decisionsUsed = 0
            tokensUsed = 0
        }
    }

    companion object {
        private fun todayDateString(): String = LocalDate.now(ZoneId.systemDefault()).toString()

        private fun toDateString(epochMs: Long): String =
            java.time.Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
    }
}

/**
 * 预算状态快照。
 */
data class BudgetSnapshot(
    val date: String,
    val decisionsUsed: Int,
    val maxDailyDecisions: Int,
    val tokensUsed: Int,
    val maxDailyTokens: Int
)

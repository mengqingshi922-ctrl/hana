package com.hana.agent.core.watchdog

/**
 * 看门狗状态判定
 */
enum class WatchdogVerdict {
    HEALTHY,
    STAGNATION,      // 原地踏步（最近3~4步视觉转移熵差值和 <= 0.05）
    OSCILLATION,     // A-B-A-B 循环震荡死锁
}

/**
 * 三级阶梯自愈策略
 */
enum class SelfHealingTier {
    TIER_1_DISMISS_OBSTACLE,  // 端侧神经核弹窗/广告消除
    TIER_2_SAFETY_BACK,       // 系统级 KEYCODE_BACK 回退
    TIER_3_RESTART_APP,       // 强杀目标应用冷启动对齐
}

/**
 * 页面视觉状态转移熵看门狗
 */
class StateEntropyWatchdog(
    private val stagnationWindow: Int = 4,
    private val oscillationWindow: Int = 8,
) {
    private val frameHistory = mutableListOf<String>()

    /**
     * 记录当前画面的视觉指纹 (Visual Fingerprint)
     */
    fun recordFrame(visualFingerprint: String) {
        frameHistory.add(visualFingerprint)
        val maxKeep = maxOf(stagnationWindow, oscillationWindow) + 4
        if (frameHistory.size > maxKeep) {
            frameHistory.removeAt(0)
        }
    }

    /**
     * 计算两帧指纹间的归一化差异度 [0.0 ~ 1.0]
     */
    fun diff(f1: String, f2: String): Double {
        if (f1 == f2) return 0.0
        if (f1.isEmpty() || f2.isEmpty()) return 1.0
        val maxLen = maxOf(f1.length, f2.length)
        if (maxLen > 256) {
            val set1 = f1.chunked(4).mapIndexed { idx, s -> "$idx:$s" }.toSet()
            val set2 = f2.chunked(4).mapIndexed { idx, s -> "$idx:$s" }.toSet()
            val intersect = set1.intersect(set2).size
            val union = set1.union(set2).size
            return if (union == 0) 0.0 else 1.0 - (intersect.toDouble() / union)
        }
        val dp = IntArray(f2.length + 1) { it }
        for (i in 1..f1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..f2.length) {
                val temp = dp[j]
                dp[j] = if (f1[i - 1] == f2[j - 1]) prev
                else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = temp
            }
        }
        val rawDist = dp[f2.length]
        val rawRatio = rawDist.toDouble() / maxLen
        // 短指纹（< 60 字符）单字符微扰抗噪（如倒计时跳秒、光标闪烁），防止误判为大幅度跳变
        val effectiveDiff = if (rawDist <= 1 && maxLen < 60) {
            rawRatio * 0.33
        } else {
            rawRatio
        }
        return effectiveDiff.coerceIn(0.0, 1.0)
    }

    /**
     * 评估当前页面状态序列，返回看门狗健康裁定
     */
    fun evaluate(): WatchdogVerdict {
        if (frameHistory.size < 2) return WatchdogVerdict.HEALTHY

        // 1. 原地踏步检测 (Stagnation): 最近 stagnationWindow 帧之间转移差值和 <= 0.05 或平均单步 <= 0.018
        if (frameHistory.size >= stagnationWindow) {
            val recent = frameHistory.takeLast(stagnationWindow)
            var diffSum = 0.0
            for (i in 0 until recent.size - 1) {
                diffSum += diff(recent[i], recent[i + 1])
            }
            val avgDiff = diffSum / (recent.size - 1)
            if (diffSum <= 0.05 || avgDiff <= 0.018) {
                return WatchdogVerdict.STAGNATION
            }
        }

        // 2. 循环震荡检测 (Oscillation): 支持识别多轮复杂周期 P = 2, 3, 4 死锁
        val maxPeriod = 4
        for (period in 2..maxPeriod) {
            val needed = period * 2
            if (frameHistory.size >= needed) {
                val window = frameHistory.takeLast(needed)
                var isPeriodic = true
                for (i in 0 until period) {
                    if (diff(window[i], window[i + period]) > 0.05) {
                        isPeriodic = false
                        break
                    }
                }
                if (isPeriodic) {
                    // 确保周期内部存在状态变化（排除纯原地踏步）
                    val hasInternalVariation = (0 until period - 1).any { i ->
                        diff(window[i], window[i + 1]) > 0.03
                    } || diff(window[period - 1], window[0]) > 0.03
                    if (hasInternalVariation) {
                        return WatchdogVerdict.OSCILLATION
                    }
                }
            }
        }

        return WatchdogVerdict.HEALTHY
    }

    /**
     * 根据连续故障次数推荐阶梯自愈策略
     */
    fun recommendHealing(consecutiveFailures: Int): SelfHealingTier {
        return when {
            consecutiveFailures <= 1 -> SelfHealingTier.TIER_1_DISMISS_OBSTACLE
            consecutiveFailures == 2 -> SelfHealingTier.TIER_2_SAFETY_BACK
            else -> SelfHealingTier.TIER_3_RESTART_APP
        }
    }

    /**
     * 重置看门狗历史状态
     */
    fun reset() {
        frameHistory.clear()
    }
}

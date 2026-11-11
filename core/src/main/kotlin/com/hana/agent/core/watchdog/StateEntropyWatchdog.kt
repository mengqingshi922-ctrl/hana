package com.hana.agent.core.watchdog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 结构化页面状态指纹：杜绝纯 hashCode() 散列雪崩效应，
 * 通过结构化维度（包名、Activity、可交互组件数、核心文本标签、感知哈希）
 * 保持真实物理界面的平滑数学连续性。
 */
data class StructuralFrameFingerprint(
    val packageName: String,
    val activityName: String = "",
    val dominantTextSignatures: List<String> = emptyList(),
    val interactiveElementCount: Int = 0,
    val surfaceDigest: String = "",
) {
    fun toNormalizedSignature(): String {
        val texts = dominantTextSignatures.take(5).joinToString(",")
        val base = buildString {
            append(packageName)
            if (activityName.isNotBlank()) append("/$activityName")
            if (interactiveElementCount > 0) append("#$interactiveElementCount")
            if (texts.isNotBlank()) append(":[$texts]")
            if (surfaceDigest.isNotBlank()) append("@$surfaceDigest")
        }
        return if (base.length > 120) base.take(120) else base
    }

    companion object {
        fun from(packageName: String?, rawContent: Any?): String {
            val pkg = packageName.orEmpty()
            if (rawContent == null) return pkg

            if (rawContent is JsonObject) {
                val activity = (rawContent["activity"] as? JsonPrimitive)?.content
                    ?: (rawContent["activityName"] as? JsonPrimitive)?.content
                    ?: ""
                val screenObj = rawContent["screen"] as? JsonObject
                val elements = (rawContent["elements"] as? JsonArray) ?: (screenObj?.get("elements") as? JsonArray)
                val count = elements?.size
                    ?: (rawContent["elementCount"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: (screenObj?.get("elementCount") as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: 0
                val dominantTexts = mutableListOf<String>()
                elements?.forEach { el ->
                    if (el is JsonObject) {
                        val t = (el["text"] as? JsonPrimitive)?.content
                            ?: (el["name"] as? JsonPrimitive)?.content
                            ?: (el["label"] as? JsonPrimitive)?.content
                        if (!t.isNullOrBlank() && dominantTexts.size < 5) {
                            dominantTexts.add(t.trim().take(15))
                        }
                    }
                }
                val snapshotId = (rawContent["screenshotId"] as? JsonPrimitive)?.content
                    ?: (rawContent["snapshotId"] as? JsonPrimitive)?.content
                    ?: (screenObj?.get("screenshotId") as? JsonPrimitive)?.content
                    ?: (rawContent["aHash"] as? JsonPrimitive)?.content
                    ?: ""
                if (elements != null || activity.isNotBlank() || snapshotId.isNotBlank() || count > 0) {
                    return StructuralFrameFingerprint(
                        packageName = pkg,
                        activityName = activity,
                        dominantTextSignatures = dominantTexts,
                        interactiveElementCount = count,
                        surfaceDigest = snapshotId.take(16),
                    ).toNormalizedSignature()
                }

                val summary = rawContent.entries.take(4).joinToString(";") { (k, v) ->
                    val vStr = (v as? JsonPrimitive)?.content ?: v.toString()
                    "$k=${vStr.take(20)}"
                }
                val base = if (pkg.isNotEmpty()) "$pkg:{$summary}" else "{$summary}"
                return if (base.length > 120) base.take(120) else base
            }

            val str = rawContent.toString().trim()
            val cleanStr = str.filter { it.isLetterOrDigit() || it == ':' || it == '/' || it == '_' || it == '-' || it == '=' || it == ';' }
            val truncated = if (cleanStr.length > 60) cleanStr.take(60) else cleanStr
            val base = if (pkg.isNotEmpty()) "$pkg:$truncated" else truncated
            return if (base.length > 120) base.take(120) else base
        }
    }
}

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

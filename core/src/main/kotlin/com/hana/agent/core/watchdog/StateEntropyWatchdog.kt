package com.hana.agent.core.watchdog

import com.hana.agent.core.tool.Verb
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
    STAGNATION,            // 原地踏步（最近3~4步视觉转移熵差值和 <= 0.05）
    OSCILLATION,           // A-B-A-B 循环震荡死锁
    NAVIGATION_DRIFT,      // 连续导航漂移（连续 4+ 步纯 swipe/back/wait 无业务交互）
    FORM_VALIDATION_ERROR, // 表单提交后停滞且出现报错红字/异常新增文案
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
 * 单步动作后置预期。
 * @property actionTool 刚执行的动作工具名（如 phone_tap, phone_swipe 等）
 * @property expectScreenChange 预期操作后屏幕是否应当发生可见变化
 * @property expectedTextTokens 预期操作后屏幕上应当出现的文本关键词 tokens
 * @property expectedPackage 预期操作后应当停留在的目标应用包名
 */
data class PostActionExpectation(
    val actionTool: String,
    val expectScreenChange: Boolean = true,
    val expectedTextTokens: List<String> = emptyList(),
    val expectedPackage: String? = null,
)

/**
 * MadeAgents 分层反思校验评估结果。
 * @property met 动作后置预期是否全部达成
 * @property mismatchReason 未达成预期的具体原因（若达成则为 null）
 * @property suggestedRecovery 建议触发的自愈阶梯级别（若达成则为 null）
 */
data class StepReflectionResult(
    val met: Boolean,
    val mismatchReason: String? = null,
    val suggestedRecovery: SelfHealingTier? = null,
)

/**
 * 带视觉样式线索的表单文本观测条目（颜色十六进制或红字标记由端侧 OCR / 视觉通道提供）
 */
data class FormTextObservation(
    val text: String,
    val colorHex: String? = null,
    val isRedOrErrorColor: Boolean = false,
    val bounds: List<Int> = emptyList(),
)

/**
 * 表单提交停滞后提取到的报错上下文告警
 */
data class FormValidationAlert(
    val errorTexts: List<String>,
    val prompt: String,
    val isRedHighlighted: Boolean,
)

/**
 * 三联防跑飞闭环综合诊断报告
 */
data class TripleWatchdogReport(
    val verdict: WatchdogVerdict,
    val healingTier: SelfHealingTier? = null,
    val navigationDriftPrompt: String? = null,
    val formValidationAlert: FormValidationAlert? = null,
    val guidance: String? = null,
)

/**
 * 页面视觉状态转移熵看门狗（三联防跑飞闭环：停滞/震荡 + 连续导航漂移 + 视觉表单报错自纠）
 */
class StateEntropyWatchdog(
    private val stagnationWindow: Int = 4,
    private val oscillationWindow: Int = 8,
    private val navigationDriftWindow: Int = 4,
) {
    private val frameHistory = mutableListOf<String>()
    private val recentNavActions = mutableListOf<String>()
    private var consecutiveNavActions: Int = 0
    private var lastFormValidationAlert: FormValidationAlert? = null
    private var lastScreenTexts: Set<String> = emptySet()

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
     * 记录单步动作以追踪连续导航漂移：
     * 连续 [navigationDriftWindow] 步（默认 4 步）纯 swipe/back/wait 且无任何业务交互时触发漂移告警。
     */
    fun recordAction(toolName: String, verb: Verb? = null, isBusinessInteraction: Boolean? = null) {
        val pureNav = if (isBusinessInteraction != null) {
            !isBusinessInteraction
        } else {
            isNavigationOnlyAction(toolName, verb)
        }
        if (pureNav) {
            consecutiveNavActions++
            recentNavActions += toolName
            if (recentNavActions.size > navigationDriftWindow + 4) {
                recentNavActions.removeAt(0)
            }
        } else {
            consecutiveNavActions = 0
            recentNavActions.clear()
            // 发生新的业务交互（如重新输入或点击其他控件）时清除上一轮已消费的表单报错锁定
            lastFormValidationAlert = null
        }
    }

    /**
     * 当前是否处于连续导航漂移状态（连续 4+ 步纯 swipe/back/wait 无业务交互）。
     */
    fun detectNavigationDrift(): Boolean = consecutiveNavActions >= navigationDriftWindow

    /**
     * 返回连续导航漂移时的立即纠偏提醒文案；未触发时返回 null。
     */
    fun navigationDriftPrompt(): String? {
        if (!detectNavigationDrift()) return null
        val trail = recentNavActions.takeLast(navigationDriftWindow).joinToString(" -> ")
        return "【连续导航漂移纠偏】已连续 $consecutiveNavActions 步执行纯导航/等待动作（$trail），期间无任何实质业务交互。" +
            "请立即停止盲目滑动、返回或空等，重新核对当前界面控件树与目标路径，改用搜索直达、精准点击或说明受阻原因。"
    }

    /**
     * 检测表单提交后停滞且出现报错红字/异常新增文案变化，并提取报错上下文。
     */
    fun detectFormValidationError(
        beforeTexts: Collection<String>,
        afterTexts: Collection<String>,
        observations: List<FormTextObservation> = emptyList(),
        stagnated: Boolean = true,
    ): FormValidationAlert? {
        if (!stagnated) {
            lastFormValidationAlert = null
            return null
        }
        val beforeSet = beforeTexts.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val afterList = afterTexts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val redObsTexts = observations
            .filter { obs -> obs.text.isNotBlank() && (obs.isRedOrErrorColor || isRedColorHex(obs.colorHex)) }
            .map { it.text.trim() }
            .distinct()

        val newlyAppearedRed = redObsTexts.filter { it !in beforeSet }
        val newlyAppearedTexts = afterList.filter { it !in beforeSet }
        val newlyAppearedErrorCopy = newlyAppearedTexts.filter(::looksLikeErrorCopy)

        val extracted = linkedSetOf<String>()
        extracted.addAll(newlyAppearedRed)
        extracted.addAll(newlyAppearedErrorCopy)
        // 若提交前已存在红字但本次新增提示未能命中关键词，仍保留新增的红字提示；若无新增红字但红字本身属于报错文案也提取出来
        if (extracted.isEmpty()) {
            extracted.addAll(redObsTexts.filter(::looksLikeErrorCopy))
        }
        // 兜底：若页面停滞且仅有少数新增短提示文案（如特定 App 自定义错误 toast/校验条），且红字或异常语义命中
        if (extracted.isEmpty()) {
            return null
        }

        val finalErrors = extracted.take(5)
        val hasRed = newlyAppearedRed.isNotEmpty() || redObsTexts.any { it in finalErrors }
        val prompt = buildString {
            append("【视觉表单报错自纠】表单操作后页面停滞未跳转，检测到")
            if (hasRed) append("红字/")
            append("异常校验提示：")
            append(finalErrors.joinToString("；"))
            append("。请根据上述具体报错信息修正对应输入框参数或补全必填项后再重新提交。")
        }
        return FormValidationAlert(
            errorTexts = finalErrors,
            prompt = prompt,
            isRedHighlighted = hasRed,
        ).also { lastFormValidationAlert = it }
    }

    /**
     * 记录当前界面文本序列并（在指定提交后停滞时）自动检测表单报错变化。
     */
    fun recordScreenTexts(
        currentTexts: Collection<String>,
        isPostSubmit: Boolean = false,
        stagnated: Boolean = true,
        observations: List<FormTextObservation> = emptyList(),
    ): FormValidationAlert? {
        val normalized = currentTexts.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val alert = if (isPostSubmit) {
            detectFormValidationError(lastScreenTexts, normalized, observations, stagnated)
        } else {
            null
        }
        lastScreenTexts = normalized
        return alert
    }

    /**
     * MadeAgents 分层步后反思校验：
     * 在动作执行后立即审计界面状态是否达成预期（屏幕变化、预期文案、目标包名），
     * 补充循环级状态熵监控，在单步偏离时快速推荐自愈手段。
     */
    fun evaluatePostAction(
        expectation: PostActionExpectation,
        actualChanged: Boolean,
        actualTexts: List<String>,
        actualPackage: String?,
    ): StepReflectionResult {
        // 1. 预期屏幕产生变化，但实际界面完全未改变
        if (expectation.expectScreenChange && !actualChanged) {
            val reason = "Screen state did not change as expected after ${expectation.actionTool}"
            return StepReflectionResult(
                met = false,
                mismatchReason = reason,
                suggestedRecovery = SelfHealingTier.TIER_1_DISMISS_OBSTACLE,
            )
        }

        // 2. 预期留在特定包名，但发生意料之外的应用跳转/漂移
        if (expectation.expectedPackage != null && actualPackage != null && expectation.expectedPackage != actualPackage) {
            val reason = "Package mismatch: expected ${expectation.expectedPackage}, but got $actualPackage"
            return StepReflectionResult(
                met = false,
                mismatchReason = reason,
                suggestedRecovery = SelfHealingTier.TIER_2_SAFETY_BACK,
            )
        }

        // 3. 预期关键文本 tokens 是否全部出现在实际界面中
        if (expectation.expectedTextTokens.isNotEmpty()) {
            val missingTokens = expectation.expectedTextTokens.filter { expected ->
                actualTexts.none { actual -> actual.contains(expected, ignoreCase = true) }
            }
            if (missingTokens.isNotEmpty()) {
                val hasErrorCopy = actualTexts.any { looksLikeErrorCopy(it) }
                val reason = "Expected text tokens not found: $missingTokens"
                val recovery = if (hasErrorCopy) {
                    SelfHealingTier.TIER_1_DISMISS_OBSTACLE
                } else {
                    SelfHealingTier.TIER_2_SAFETY_BACK
                }
                return StepReflectionResult(
                    met = false,
                    mismatchReason = reason,
                    suggestedRecovery = recovery,
                )
            }
        }

        // 全部预期均已满足
        return StepReflectionResult(met = true, mismatchReason = null, suggestedRecovery = null)
    }

    /**
     * 综合评估三联防跑飞状态并生成结构化闭环诊断报告。
     */
    fun evaluateTripleLoop(consecutiveFailures: Int = 0): TripleWatchdogReport {
        val verdict = evaluate()
        return when (verdict) {
            WatchdogVerdict.FORM_VALIDATION_ERROR -> {
                val alert = lastFormValidationAlert
                TripleWatchdogReport(
                    verdict = verdict,
                    formValidationAlert = alert,
                    guidance = alert?.prompt ?: "【视觉表单报错自纠】检测到表单校验报错，请修正输入后重试。",
                )
            }
            WatchdogVerdict.NAVIGATION_DRIFT -> {
                val prompt = navigationDriftPrompt()
                TripleWatchdogReport(
                    verdict = verdict,
                    healingTier = recommendHealing(consecutiveFailures),
                    navigationDriftPrompt = prompt,
                    guidance = prompt,
                )
            }
            WatchdogVerdict.STAGNATION -> TripleWatchdogReport(
                verdict = verdict,
                healingTier = recommendHealing(consecutiveFailures),
                guidance = "【自愈认知反思】连续操作未能产生界面有效状态改变（停滞死锁），请更换路径或重新观察界面。",
            )
            WatchdogVerdict.OSCILLATION -> TripleWatchdogReport(
                verdict = verdict,
                healingTier = recommendHealing(consecutiveFailures),
                guidance = "【自愈认知反思】检测到界面在多状态间往复循环震荡，请放弃当前循环路径并采用替代方案。",
            )
            WatchdogVerdict.HEALTHY -> TripleWatchdogReport(verdict = WatchdogVerdict.HEALTHY)
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
        if (lastFormValidationAlert != null) return WatchdogVerdict.FORM_VALIDATION_ERROR
        if (detectNavigationDrift()) return WatchdogVerdict.NAVIGATION_DRIFT
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
        recentNavActions.clear()
        consecutiveNavActions = 0
        lastFormValidationAlert = null
        lastScreenTexts = emptySet()
    }

    companion object {
        private val PURE_NAV_VERBS = setOf(Verb.Swipe, Verb.Back, Verb.Wait)
        private val PURE_NAV_TOOLS = setOf(
            "swipe", "phone_swipe", "back", "phone_back", "wait", "phone_wait", "phone_sleep", "sleep",
        )
        private val ERROR_KEYWORDS = listOf(
            "错误", "失败", "无效", "不正确", "格式", "必填", "不能为空", "请输入", "请选择",
            "超限", "不足", "已过期", "验证码", "太短", "太长", "不一致", "不支持", "异常",
            "警告", "请检查", "不合法", "未通过", "不能超过", "至少", "最多", "有误", "被占用",
            "error", "invalid", "required", "failed", "incorrect", "mismatch",
            "too short", "too long", "must be", "cannot be empty", "please enter", "wrong",
        )

        internal fun isNavigationOnlyAction(toolName: String, verb: Verb? = null): Boolean {
            if (verb != null && verb in PURE_NAV_VERBS) return true
            return toolName.lowercase() in PURE_NAV_TOOLS
        }

        internal fun looksLikeErrorCopy(text: String): Boolean {
            val low = text.trim().lowercase()
            if (low.isEmpty()) return false
            return ERROR_KEYWORDS.any { it in low }
        }

        internal fun isRedColorHex(hex: String?): Boolean {
            if (hex.isNullOrBlank()) return false
            val clean = hex.trim().removePrefix("#")
            val rgb = when (clean.length) {
                6 -> clean
                8 -> clean.substring(2) // strip alpha AA
                else -> return false
            }
            val r = rgb.substring(0, 2).toIntOrNull(16) ?: return false
            val g = rgb.substring(2, 4).toIntOrNull(16) ?: return false
            val b = rgb.substring(4, 6).toIntOrNull(16) ?: return false
            return r >= 160 && (r - g) >= 60 && (r - b) >= 60
        }
    }
}

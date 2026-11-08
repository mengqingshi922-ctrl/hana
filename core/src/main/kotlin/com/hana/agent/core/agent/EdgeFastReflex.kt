package com.hana.agent.core.agent

import com.hana.agent.core.model.Author
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.ModelReply
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import com.hana.agent.core.model.StopReason
import com.hana.agent.core.model.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 端侧 1ms 规则反射直通引擎 (Edge Fast-Path Reflex Engine)
 *
 * 工业级 Phone Agent 的关键核心：
 * 绝不把手机当作大语言模型推理机（避免死占 1.5GB 内存且发热卡顿），
 * 而是作为毫秒级“动作神经脊髓反射”：
 * 1. 干扰/系统弹窗闪电直通：在无障碍树中以 1ms 命中“允许”、“跳过”、“跳过广告”、“知道了”、“开心收下”、“残忍拒绝”、“确定”等阻断弹窗，
 *    不耗费任何网络 RTT，0 Token，直接生成物理点击；
 * 2. 状态停滞与环路熔断 (Anti-Loop Breaker)：
 *    连续 2 步点击同一区域且画面无变化时，强制触发物理返回 (phone_back) 或主模型接管，从根本上物理掐死 92 步盲目死循环。
 */
object EdgeFastReflex {
    const val ROLE_REFLEX = "reflex"

    // 1. 绝对高置信度直接阻断词（权限授权、广告跳过、营销浮层；正常业务页面绝不会以此命名业务操作）
    private val HIGH_CONFIDENCE_DISMISS_WORDS = setOf(
        // 系统权限与安全授权
        "允许", "始终允许", "仅在使用中允许", "同意并继续", "同意并使用", "我已阅读并同意", "同意并授权",
        // 广告与营销快速跳过
        "跳过", "跳过广告", "跳过引导", "关闭广告", "知道了并关闭", "朕知道了", "开心收下", "残忍拒绝",
        "稍后再说", "以后再说", "暂不更新", "暂不升级", "取消更新", "暂不开启", "忽略本次"
    )

    // 2. 上下文限定词（必须伴随明确的系统/权限/更新弹窗上下文，且页面为轻量浮层时才允许点击）
    private val CONTEXT_SENSITIVE_WORDS = setOf(
        "好的", "知道了", "我知道了", "放弃", "进入应用", "立即体验", "直接进入"
    )

    // 明确的弹窗与权限特征识别词（杜绝宽泛日常词）
    private val DIALOG_CONTEXT_CLUES = listOf(
        "权限申请", "权限提示", "获取权限", "想要访问", "隐私政策", "服务协议",
        "青少年模式", "未成年人防沉迷", "版本更新", "发现新版本", "新版本上线"
    )

    // 精确匹配的关闭图标/符号
    private val EXACT_CLOSE_LABELS = setOf("close", "cancel", "dismiss", "x", "X", "✕", "✖")

    data class ReflexResult(val reply: ModelReply, val reason: String)

    /**
     * 同步规则检查（供非协程上下文与纯规则场景使用）。
     */
    fun inspect(history: List<Message>, userGoal: String, step: Int): ReflexResult? {
        val userGoalLower = userGoal.lowercase()

        // 1. 核心环路熔断 (Anti-Loop Breaker)：防止同点盲目连击死循环
        val loopBreak = checkLoopBreak(history)
        if (loopBreak != null) return loopBreak

        // 2. 阻断弹窗与干扰拦截闪电直通
        val latestToolResult = history.asReversed().flatMap { m ->
            m.parts.filterIsInstance<Part.ToolResult>()
        }.firstOrNull() ?: return null

        val content = latestToolResult.content.orEmpty()
        if (!content.contains("elements") && !content.contains("screen")) return null

        val (targetRef, targetCoord, label) = findObstacleElement(content, userGoalLower) ?: return null
        return buildReflexResult(targetRef, targetCoord, label, "⚡ 端侧闪电直通: 自动关闭阻断弹窗【$label】", "obstacle_popup: $label")
    }

    /**
     * 协程感知检查（支持规则直通 + MNN-Pocket 端侧指针消歧二级火箭）。
     */
    suspend fun inspect(
        history: List<Message>,
        userGoal: String,
        step: Int,
        edgePort: com.hana.agent.core.edge.EdgeDecisionPort,
    ): ReflexResult? {
        // 第一级：1ms 确定性规则反射直通
        val ruleResult = inspect(history, userGoal, step)
        if (ruleResult != null) return ruleResult

        // 第二级：端侧 MNN-Pocket-0.3B-Dec 单步非自回归指针消歧
        if (!edgePort.isReady) return null

        val userGoalLower = userGoal.lowercase()
        val latestToolResult = history.asReversed().flatMap { m ->
            m.parts.filterIsInstance<Part.ToolResult>()
        }.firstOrNull() ?: return null

        val content = latestToolResult.content.orEmpty()
        if (!content.contains("elements") && !content.contains("screen")) return null

        val (targetRef, targetCoord, label) = findObstacleElementMnn(content, userGoalLower, edgePort) ?: return null
        return buildReflexResult(targetRef, targetCoord, label, "⚡ 端侧 MNN 指针直通: 自动关闭阻断弹窗【$label】", "mnn_obstacle_popup: $label")
    }

    private fun buildReflexResult(
        targetRef: String?,
        targetCoord: Pair<Int, Int>?,
        label: String,
        actionReason: String,
        resultReason: String,
    ): ReflexResult {
        val args = buildJsonObject {
            if (targetRef != null) put("ref", targetRef)
            if (targetCoord != null) {
                put("x", targetCoord.first)
                put("y", targetCoord.second)
            }
            put("reason", actionReason)
        }

        val toolCall = Part.ToolCall(
            id = "reflex_${System.currentTimeMillis()}",
            name = "phone_tap",
            argsJson = args.toString()
        )

        val reply = ModelReply(
            parts = listOf(
                Part.Text(actionReason),
                toolCall
            ),
            stopReason = StopReason.ToolUse,
            usage = Usage(inputTokens = 0, outputTokens = 0, cachedInputTokens = 0)
        )

        return ReflexResult(reply, resultReason)
    }

    /**
     * 连续重复动作死循环熔断器 (Anti-Loop Breaker)：
     * 若最近两次动作都是点击同一目标/相近坐标，且界面结果未发生有效改变（例如 changed: false），
     * 判定为界面卡死或假按钮欺骗，端侧直接强力介入执行 phone_back 脱困，物理终结死循环。
     */
    private fun checkLoopBreak(history: List<Message>): ReflexResult? {
        val recentCalls = history.filter { it.role == Role.Assistant }
            .flatMap { it.parts.filterIsInstance<Part.ToolCall>() }
            .takeLast(2)

        if (recentCalls.size < 2) return null
        val (c1, c2) = recentCalls[0] to recentCalls[1]
        if (c1.name != "phone_tap" || c2.name != "phone_tap") return null

        // 解析两轮点击的 ref 与坐标
        val ref1 = extractRefOrCoord(c1.argsJson)
        val ref2 = extractRefOrCoord(c2.argsJson)
        if (ref1 == null || ref2 == null || ref1 != ref2) return null

        // 检查最新一次工具调用结果是否没有产生变化（排除明确的报错/需要确认/风控拦截）
        val latestTool = history.asReversed().flatMap { m ->
            m.parts.filterIsInstance<Part.ToolResult>()
        }.firstOrNull() ?: return null

        if (latestTool.isError) return null
        val content = latestTool.content.orEmpty()
        if (content.contains("\"error\"") || content.contains("confirmation_required") || content.contains("hand_to_user")) return null

        val notChanged = content.contains("\"changed\":false") || content.contains("\"changed\": false") || content.contains("\"effect\":\"no_effect\"")

        if (notChanged) {
            val backCall = Part.ToolCall(
                id = "breaker_${System.currentTimeMillis()}",
                name = "phone_back",
                argsJson = "{}"
            )
            val reply = ModelReply(
                parts = listOf(
                    Part.Text("🛡️ 端侧熔断: 检测到连续同位置点击无效，自动触发物理返回尝试脱困"),
                    backCall
                ),
                stopReason = StopReason.ToolUse,
                usage = Usage(inputTokens = 0, outputTokens = 0, cachedInputTokens = 0)
            )
            return ReflexResult(reply, "anti_loop_breaker")
        }
        return null
    }

    private fun extractRefOrCoord(argsJson: String): String? {
        return try {
            val json = Json.parseToJsonElement(argsJson) as? JsonObject ?: return null
            val ref = (json["ref"] as? JsonPrimitive)?.contentOrNull
            if (!ref.isNullOrBlank()) return "ref:$ref"
            val x = (json["x"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val y = (json["y"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            if (x != null && y != null) {
                // 网格化坐标（容差 20 像素）
                val gridX = x / 25
                val gridY = y / 25
                return "coord:$gridX,$gridY"
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun findObstacleElement(content: String, userGoalLower: String): Triple<String?, Pair<Int, Int>?, String>? {
        return try {
            val json = Json.parseToJsonElement(content) as? JsonObject ?: return null
            val screenObj = json["screen"] as? JsonObject ?: json
            val elements = (screenObj["elements"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null

            // 全局文本与弹窗上下文特征分析
            val allTexts = elements.mapNotNull {
                (it["name"] as? JsonPrimitive)?.contentOrNull
                    ?: (it["text"] as? JsonPrimitive)?.contentOrNull
                    ?: (it["desc"] as? JsonPrimitive)?.contentOrNull
            }.joinToString(" ")
            // 只有当存在明确弹窗提示词，且总元素数量较少（典型浮层弹窗）时才认为处于弹窗上下文，防止在大业务页面误伤
            val hasDialogContext = DIALOG_CONTEXT_CLUES.any { allTexts.contains(it, ignoreCase = true) } && elements.size <= 20

            // 优先查找符合阻断特征的可点击控件
            for (elem in elements) {
                val clickable = (elem["clickable"] as? JsonPrimitive)?.contentOrNull == "true"
                val rawLabel = (elem["name"] as? JsonPrimitive)?.contentOrNull
                    ?: (elem["text"] as? JsonPrimitive)?.contentOrNull
                    ?: (elem["desc"] as? JsonPrimitive)?.contentOrNull
                    ?: (elem["description"] as? JsonPrimitive)?.contentOrNull
                    ?: ""
                val cleanLabel = rawLabel.trim()
                if (cleanLabel.isEmpty()) continue

                // 核心防误伤：如果用户的目标本身就包含了该词（如用户明确说“点取消”、“不要点允许”），则绝不自动拦截
                if (cleanLabel.length >= 2 && userGoalLower.contains(cleanLabel.lowercase())) {
                    continue
                }

                // 尺寸安全约束：全屏或过大容器（例如宽 > 850 且 高 > 800）绝不能当作普通点击按钮
                val bounds = (elem["bounds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
                if (bounds != null && bounds.size >= 4) {
                    val w = bounds[2] - bounds[0]
                    val h = bounds[3] - bounds[1]
                    if (w > 850 && h > 800) continue
                }

                val isHighConfidence = cleanLabel in HIGH_CONFIDENCE_DISMISS_WORDS ||
                    HIGH_CONFIDENCE_DISMISS_WORDS.any { cleanLabel.startsWith(it) && cleanLabel.length <= it.length + 4 }
                val isContextSensitive = hasDialogContext && (cleanLabel in CONTEXT_SENSITIVE_WORDS ||
                    CONTEXT_SENSITIVE_WORDS.any { cleanLabel == it || (cleanLabel.startsWith(it) && cleanLabel.length <= it.length + 3) } ||
                    cleanLabel in EXACT_CLOSE_LABELS)

                if (clickable && (isHighConfidence || isContextSensitive)) {
                    val ref = (elem["ref"] as? JsonPrimitive)?.contentOrNull
                    val coords = if (bounds != null && bounds.size >= 4) {
                        Pair((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2)
                    } else null

                    if (ref != null || coords != null) {
                        return Triple(ref, coords, cleanLabel)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findObstacleElementMnn(
        content: String,
        userGoalLower: String,
        edgePort: com.hana.agent.core.edge.EdgeDecisionPort,
    ): Triple<String?, Pair<Int, Int>?, String>? {
        return try {
            val json = Json.parseToJsonElement(content) as? JsonObject ?: return null
            val screenObj = json["screen"] as? JsonObject ?: json
            val elements = (screenObj["elements"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null

            // 仅在轻量浮层/疑似弹窗（候选 <= 18）中生效，避免在全量业务页面越界
            if (elements.size > 18) return null

            val allTexts = elements.mapNotNull {
                (it["name"] as? JsonPrimitive)?.contentOrNull
                    ?: (it["text"] as? JsonPrimitive)?.contentOrNull
                    ?: (it["desc"] as? JsonPrimitive)?.contentOrNull
            }.joinToString(" ")

            val candidates = elements.mapNotNull { elem ->
                val clickable = (elem["clickable"] as? JsonPrimitive)?.contentOrNull == "true"
                if (!clickable) return@mapNotNull null
                val ref = (elem["ref"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val rawLabel = (elem["name"] as? JsonPrimitive)?.contentOrNull
                    ?: (elem["text"] as? JsonPrimitive)?.contentOrNull
                    ?: (elem["desc"] as? JsonPrimitive)?.contentOrNull
                    ?: (elem["description"] as? JsonPrimitive)?.contentOrNull
                    ?: ""
                val cleanLabel = rawLabel.trim()
                if (cleanLabel.length >= 2 && userGoalLower.contains(cleanLabel.lowercase())) return@mapNotNull null
                val bounds = (elem["bounds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }?.toIntArray()
                if (bounds != null && bounds.size >= 4) {
                    val w = bounds[2] - bounds[0]
                    val h = bounds[3] - bounds[1]
                    if (w > 850 && h > 800) return@mapNotNull null
                }
                val role = (elem["role"] as? JsonPrimitive)?.contentOrNull ?: "Button"
                com.hana.agent.core.edge.EdgeDecisionPort.EdgeCandidate(ref, role, cleanLabel, bounds)
            }

            if (candidates.isEmpty() || candidates.size > 18) return null

            val pickedRef = edgePort.pickObstacleDismissRef(
                userGoal = userGoalLower,
                dialogClues = allTexts.take(300),
                candidates = candidates,
                confidenceThreshold = 0.85f,
            ) ?: return null

            val matchedElem = elements.firstOrNull { (it["ref"] as? JsonPrimitive)?.contentOrNull == pickedRef } ?: return null
            val rawLabel = (matchedElem["name"] as? JsonPrimitive)?.contentOrNull
                ?: (matchedElem["text"] as? JsonPrimitive)?.contentOrNull
                ?: (matchedElem["desc"] as? JsonPrimitive)?.contentOrNull
                ?: "关闭"
            val bounds = (matchedElem["bounds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
            val coords = if (bounds != null && bounds.size >= 4) {
                Pair((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2)
            } else null

            Triple(pickedRef, coords, rawLabel.trim())
        } catch (_: Exception) {
            null
        }
    }
}

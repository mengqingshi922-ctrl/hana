package com.wangzan101.hana.core.agent

import com.wangzan101.hana.core.model.Author
import com.wangzan101.hana.core.model.Message
import com.wangzan101.hana.core.model.ModelReply
import com.wangzan101.hana.core.model.Part
import com.wangzan101.hana.core.model.Role
import com.wangzan101.hana.core.model.StopReason
import com.wangzan101.hana.core.model.Usage
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

    // 常见阻断/确认关键词（命中且用户目标未明确要求与之相悖时，秒级直通点击）
    private val POPUP_FAST_KEYWORDS = listOf(
        // 权限与安全弹窗
        "允许", "始终允许", "仅在使用中允许", "同意并继续", "同意",
        // 广告与营销阻断
        "跳过", "跳过广告", "知道了", "开心收下", "残忍拒绝", "稍后再说", "不感兴趣", "关闭广告", "继续播放",
        "进入主页", "立即体验", "开启体验", "进入应用", "跳过引导", "先逛逛", "我知道了", "暂不开启", "放弃",
        // 更新与版本弹窗
        "以后再说", "暂不更新", "取消", "取消更新"
    )

    // 精确匹配的图标/文字
    private val EXACT_CLOSE_LABELS = setOf("关闭", "close", "cancel", "dismiss", "x", "X", "✕", "✖")

    data class ReflexResult(val reply: ModelReply, val reason: String)

    /**
     * 检查当前历史中的最新屏幕与执行轨迹，判断是否可以触发端侧闪电反射。
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

        // 构造端侧 1ms 闪电直通的物理点击工具调用
        val args = buildJsonObject {
            if (targetRef != null) put("ref", targetRef)
            if (targetCoord != null) {
                put("x", targetCoord.first)
                put("y", targetCoord.second)
            }
            put("reason", "⚡ 端侧闪电直通: 自动关闭阻断弹窗【$label】")
        }

        val toolCall = Part.ToolCall(
            id = "reflex_${System.currentTimeMillis()}",
            name = "phone_tap",
            argsJson = args.toString()
        )

        val reply = ModelReply(
            parts = listOf(
                Part.Text("⚡ 端侧直通: 自动关闭干扰弹窗【$label】"),
                toolCall
            ),
            stopReason = StopReason.ToolUse,
            usage = Usage(inputTokens = 0, outputTokens = 0, cachedInputTokens = 0)
        )

        return ReflexResult(reply, "obstacle_popup: $label")
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

        // 检查最新一次工具调用结果是否没有产生变化
        val latestTool = history.asReversed().flatMap { m ->
            m.parts.filterIsInstance<Part.ToolResult>()
        }.firstOrNull()

        val content = latestTool?.content.orEmpty()
        val notChanged = content.contains("\"changed\":false") || content.contains("\"changed\": null") || !content.contains("\"changed\":true")

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

                val isExactClose = cleanLabel in EXACT_CLOSE_LABELS
                val isKeywordHit = POPUP_FAST_KEYWORDS.any { cleanLabel == it || (cleanLabel.startsWith(it) && cleanLabel.length <= it.length + 3) }

                if (clickable && (isExactClose || isKeywordHit)) {
                    val ref = (elem["ref"] as? JsonPrimitive)?.contentOrNull
                    val bounds = (elem["bounds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
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
}

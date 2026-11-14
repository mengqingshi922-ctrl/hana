package com.hana.agent.ui.components.mascot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 灵动小精灵注视焦点坐标（归一化屏幕空间 `[0f, 1f]`，带过期时间戳）。
 */
data class GazeTarget(
    val normX: Float,
    val normY: Float,
    val expiresAtMs: Long,
)

/**
 * 跨组件视线追踪总线：
 * 支持用户触摸拖拽或 Agent 定位目标控件时推送归一化坐标 `(normX, normY)`，
 * 到期后自动失效并交由渲染层平滑回落至自然待机游移。
 */
object MascotGazeBus {
    private val _target = MutableStateFlow<GazeTarget?>(null)
    val target: StateFlow<GazeTarget?> = _target.asStateFlow()

    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    /**
     * 让小精灵注视归一化屏幕位置 `(normX, normY)`，持续 [ttlMs] 毫秒。
     */
    fun focusAt(normX: Float, normY: Float, ttlMs: Long = 1500L) {
        val safeX = if (normX.isFinite()) normX.coerceIn(0f, 1f) else 0.5f
        val safeY = if (normY.isFinite()) normY.coerceIn(0f, 1f) else 0.5f
        val safeTtl = ttlMs.coerceAtLeast(0L)
        val expiresAt = nowMsProvider() + safeTtl
        _target.value = GazeTarget(
            normX = safeX,
            normY = safeY,
            expiresAtMs = expiresAt,
        )
    }

    /**
     * 清除当前注视焦点，恢复自然待机游移。
     */
    fun clear() {
        _target.value = null
    }

    /**
     * 获取当前尚未过期的注视目标；若已过期返回 `null`。
     */
    fun activeTarget(nowMs: Long = nowMsProvider()): GazeTarget? {
        val current = _target.value ?: return null
        return if (nowMs < current.expiresAtMs) current else null
    }

    internal fun resetForTesting() {
        nowMsProvider = { System.currentTimeMillis() }
        _target.value = null
    }
}

/**
 * 跨组件流式情绪与隐私护盾总线：
 * - [privacyShieldActive] 为 `true` 时优先级最高（如接管输入密码/验证码时切换捂眼隐私护盾）；
 * - [overrideEmotionId] 用于大语言模型流式回复内联标签或胶囊提示实时驱动 32 态微表情。
 */
object StreamEmotionBus {
    private val _overrideEmotionId = MutableStateFlow<String?>(null)
    val overrideEmotionId: StateFlow<String?> = _overrideEmotionId.asStateFlow()

    private val _privacyShieldActive = MutableStateFlow(false)
    val privacyShieldActive: StateFlow<Boolean> = _privacyShieldActive.asStateFlow()

    /**
     * 设置流式情绪覆盖 ID（支持 `"00"`~`"41"` 或语义别名如 `"happy"`；传 `null` 清除覆盖）。
     */
    fun setStreamEmotion(id: String?) {
        if (id.isNullOrBlank()) {
            _overrideEmotionId.value = null
            return
        }
        _overrideEmotionId.value = InlineEmotionTagParser.resolveEmotionId(id)
    }

    /**
     * 开启或关闭接管态捂眼隐私护盾。
     */
    fun setPrivacyShield(active: Boolean) {
        _privacyShieldActive.value = active
    }

    internal fun resetForTesting() {
        _overrideEmotionId.value = null
        _privacyShieldActive.value = false
    }
}

/**
 * 内联情绪标签流式解析与剥离器（借鉴 Open-LLM-VTuber 协议）：
 * 从流式输出片段中识别并剥离 `[emo:00]`~`[emo:41]` 或 `[happy]` / `[shy]` 等标签，
 * 映射为 [MascotData] 的 2 位情绪 ID，彻底激活全量 32 套微表情。
 */
object InlineEmotionTagParser {
    private val VALID_IDS: Set<String> by lazy {
        MascotData.emotions.mapTo(HashSet()) { it.id }
    }

    private val ALIAS_TO_ID: Map<String, String> = mapOf(
        // 00: 睡眠
        "sleep" to "00", "sleepy" to "00", "zzz" to "00", "asleep" to "00", "睡眠" to "00", "困" to "00",
        // 01: 唤醒
        "wake" to "01", "wakeup" to "01", "wake_up" to "01", "唤醒" to "01",
        // 02: 待机放空
        "idle" to "02", "neutral" to "02", "calm" to "02", "normal" to "02", "待机" to "02", "平静" to "02",
        // 03: 好奇
        "curious" to "03", "wonder" to "03", "interested" to "03", "好奇" to "03",
        // 04: 发呆
        "daydream" to "04", "daze" to "04", "blank" to "04", "发呆" to "04",
        // 05: 加载苏醒
        "loading_wake" to "05", "booting" to "05",
        // 06: 休眠
        "dormant" to "06", "nap" to "06", "rest" to "06", "休眠" to "06",
        // 07: 抖动唤醒
        "shake_wake" to "07", "startle" to "07",
        // 10: 开心
        "happy" to "10", "joy" to "10", "smile" to "10", "cheerful" to "10",
        "glad" to "10", "delighted" to "10", "excited" to "10", "开心" to "10", "高兴" to "10", "笑" to "10",
        // 11: 疑惑
        "doubt" to "11", "puzzled" to "11", "hmm" to "11", "question" to "11", "疑惑" to "11",
        // 12: 失落
        "sad" to "12", "down" to "12", "upset" to "12", "disappointed" to "12",
        "unhappy" to "12", "失落" to "12", "难过" to "12",
        // 13: 惊讶
        "surprised" to "13", "shocked" to "13", "wow" to "13", "amazed" to "13",
        "astonished" to "13", "惊讶" to "13", "震惊" to "13",
        // 14: 害羞
        "shy" to "14", "blush" to "14", "embarrassed" to "14", "bashful" to "14",
        "love" to "14", "affection" to "14", "害羞" to "14", "脸红" to "14",
        // 15: 疲惫
        "tired" to "15", "exhausted" to "15", "weary" to "15", "fatigue" to "15", "疲惫" to "15", "累" to "15",
        // 16: 专注
        "focused" to "16", "concentrate" to "16", "serious" to "16", "focus" to "16", "专注" to "16", "认真" to "16",
        // 17: 慌张
        "panicked" to "17", "panic" to "17", "flustered" to "17", "nervous" to "17",
        "anxious" to "17", "慌张" to "17", "紧张" to "17",
        // 18: 无奈
        "helpless" to "18", "sigh" to "18", "awkward" to "18", "speechless" to "18", "无奈" to "18", "摊手" to "18",
        // 19: 满意
        "satisfied" to "19", "proud" to "19", "content" to "19", "smug" to "19",
        "wink" to "19", "满意" to "19", "得意" to "19",
        // 20: 困惑
        "confused" to "20", "perplexed" to "20", "baffled" to "20", "sorry" to "20",
        "apologetic" to "20", "困惑" to "20", "抱歉" to "20",
        // 21: 生气
        "angry" to "21", "mad" to "21", "annoyed" to "21", "furious" to "21", "生气" to "21", "愤怒" to "21",
        // 30: 思考中
        "thinking" to "30", "think" to "30", "pondering" to "30", "reasoning" to "30", "思考" to "30",
        // 31: 接收任务
        "received" to "31", "start" to "31", "ready" to "31", "accept" to "31", "接收任务" to "31",
        // 32: 处理中忙碌
        "busy" to "32", "working" to "32", "processing" to "32", "忙碌" to "32", "处理中" to "32",
        // 33: 任务完成
        "done" to "33", "complete" to "33", "completed" to "33", "success" to "33",
        "celebrate" to "33", "confetti" to "33", "完成" to "33", "成功" to "33",
        // 34: 出错
        "error" to "34", "fail" to "34", "failed" to "34", "alert" to "34", "出错" to "34", "失败" to "34",
        // 35: 等待输入
        "waiting" to "35", "wait" to "35", "listening" to "35", "等待" to "35",
        // 36: 联网加载
        "loading" to "36", "network" to "36", "fetching" to "36", "联网" to "36",
        // 37: 复述回忆
        "recalling" to "37", "recall" to "37", "memory" to "37", "remember" to "37", "回忆" to "37",
        // 38: 拒绝/受限
        "refuse" to "38", "denied" to "38", "restricted" to "38", "拒绝" to "38",
        // 39: 输出回复
        "speaking" to "39", "replying" to "39", "output" to "39", "回复" to "39",
        // 40: 检索资料
        "searching" to "40", "search" to "40", "exploring" to "40", "browsing" to "40", "检索" to "40", "搜索" to "40",
        // 41: 停止终止
        "stopped" to "41", "stop" to "41", "cancelled" to "41", "terminated" to "41", "停止" to "41", "终止" to "41",
    )

    // 匹配 [emo:00]~[emo:41] / [emo:happy] 以及独立 [happy]/[shy]/[开心]（排除 Markdown 链接 [text](url)）
    private val EMO_PREFIX_REGEX = Regex(
        """\[\s*emo\s*:\s*([A-Za-z0-9_\-\u4e00-\u9fff]+)\s*]""",
        RegexOption.IGNORE_CASE,
    )
    private val NAMED_TAG_REGEX = Regex(
        """\[\s*([A-Za-z_\-\u4e00-\u9fff]+)\s*](?!\s*[\(:])""",
    )

    /**
     * 将标签原始值（如 `"14"`、`14`、`"happy"`、`"害羞"`）规范化为 [MascotData] 的 2 位有效 ID。
     */
    fun resolveEmotionId(rawTag: String): String? {
        val key = rawTag.trim().lowercase()
        if (key.isEmpty()) return null
        if (key in VALID_IDS) return key
        if (key.length == 1 && key[0].isDigit()) {
            val padded = "0$key"
            if (padded in VALID_IDS) return padded
        }
        return ALIAS_TO_ID[key]
    }

    /**
     * 从文本中解析并剥离所有内联情绪标签，返回 `(剥离后的干净文本, 解析出的最新 2 位情绪 ID)`。
     * 若未检测到合法情绪标签，原文原样返回且情绪 ID 为 `null`。
     */
    fun stripAndExtract(text: String): Pair<String, String?> {
        if (text.isEmpty() || '[' !in text) return text to null

        val hits = mutableListOf<Pair<IntRange, String>>()

        for (match in EMO_PREFIX_REGEX.findAll(text)) {
            val resolved = resolveEmotionId(match.groupValues[1])
            if (resolved != null) {
                hits += match.range to resolved
            }
        }

        for (match in NAMED_TAG_REGEX.findAll(text)) {
            val insideRange = hits.any { match.range.first >= it.first.first && match.range.last <= it.first.last }
            if (insideRange) continue
            val resolved = resolveEmotionId(match.groupValues[1])
            if (resolved != null) {
                hits += match.range to resolved
            }
        }

        if (hits.isEmpty()) return text to null

        // 按文本出现顺序排序，确保多标签混用时严格取末尾最新情绪
        hits.sortBy { it.first.first }
        var extractedId: String? = null
        val sb = StringBuilder(text.length)
        var cursor = 0
        for ((range, emotionId) in hits) {
            extractedId = emotionId
            if (range.first > cursor) {
                sb.append(text, cursor, range.first)
            }
            cursor = range.last + 1
            // 剥离标签后顺带吸收紧随其后的单个水平空格，避免残留多余双空格或首部空格
            val prevIsBoundary = sb.isEmpty() || sb.last() == '\n' || sb.last() == ' ' || sb.last() == '\t'
            if (prevIsBoundary) {
                while (cursor < text.length && (text[cursor] == ' ' || text[cursor] == '\t')) {
                    cursor++
                }
            }
        }
        if (cursor < text.length) {
            sb.append(text, cursor, text.length)
        }

        return sb.toString().trimStart(' ', '\t') to extractedId
    }
}

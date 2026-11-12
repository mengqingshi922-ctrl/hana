package com.hana.agent.core.security

import com.hana.agent.core.bus.RegisterBus
import com.hana.agent.core.privacy.PocketPrivacyGuard
import com.hana.agent.core.uistate.UiElement
import com.hana.agent.core.uistate.VisibleTextLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 隐私敏感实体类别。
 */
enum class SensitiveCategory(val labelZh: String, val priority: Int) {
    PASSWORD("密码", 0),
    SECRET_TOKEN("密钥令牌", 1),
    ID_CARD("身份证", 2),
    BANK_CARD("银行卡", 3),
    VERIFY_CODE("验证码", 4),
    PHONE("手机号", 5),
    EMAIL("邮箱", 6),
    NAME("姓名", 7),
    ADDRESS("地址", 8),
    AMOUNT("金额", 9),
}

/**
 * 视觉打码样式：
 * - [PIXELATE_BLUR]：局部像素块马赛克/模糊，保留页面布局结构同时不可逆抹除字符笔划；
 * - [SOLID_MASK]：高对比深色遮罩覆盖（适合密码框、密钥或强制全遮挡场景）。
 */
enum class MaskStyle {
    PIXELATE_BLUR,
    SOLID_MASK,
}

/**
 * 截图送往云端 VLM 前的本地坐标打码区域（0–999 归一化坐标 [left, top, right, bottom]）。
 */
data class RedactionRegion(
    val bounds: List<Int>,
    val category: SensitiveCategory,
    val style: MaskStyle = MaskStyle.PIXELATE_BLUR,
    val confidence: Float = 1.0f,
    val sourceTextPreview: String = "",
) {
    init {
        require(bounds.size == 4) { "bounds must be [left, top, right, bottom]" }
    }
}

/**
 * 文本混合隐私脱敏结果。
 */
data class HybridSanitizeResult(
    val sanitizedText: String,
    val replacedCount: Int,
    val maskedEntities: Map<String, String>,
    val detectedCategories: Set<SensitiveCategory>,
)

/**
 * 上下文敏感实体识别扩展接口：允许端侧 Tiny-NER（如 MNN / LiteRT 模型）注入非结构化实体 spans。
 */
fun interface ContextEntityRecognizer {
    data class Span(
        val startInclusive: Int,
        val endExclusive: Int,
        val category: SensitiveCategory,
        val confidence: Float = 0.9f,
    )

    fun detect(text: String, packageName: String?): List<Span>
}

/**
 * 混合隐私防火墙 (Hybrid Privacy Firewall)
 *
 * 在现有 [PocketPrivacyGuard] 基础上升级为三层纵深防御：
 * 1. **规则层（Regex）**：覆盖身份证、银行卡、手机号、邮箱、API Key/Token、密码口令、带前缀验证码等；
 * 2. **上下文实体识别层（Context NER）**：结合上下文线索（如相邻标签、密码控件语义、独立 4-8 位 OTP、带空格银行卡号 Luhn 校验、非固定句式收款人/口令）补全正则盲区，并统一写入 [RegisterBus] 本地寄存器；
 * 3. **视觉坐标打码规划与像素抹除层**：根据本机 OCR 识别框列表 ([VisibleTextLine]) 与无障碍密码控件 ([UiElement])，精准定位密码/验证码/银行卡/身份证区域，在截图上传云端 VLM 前对像素缓冲区执行本地马赛克模糊或深色遮罩。
 */
object HybridPrivacyFirewall {
    private val counter = AtomicInteger(1)

    @Volatile
    private var customRecognizer: ContextEntityRecognizer? = null

    fun setContextRecognizer(recognizer: ContextEntityRecognizer?) {
        customRecognizer = recognizer
    }

    @Synchronized
    fun reset() {
        counter.set(1)
        PocketPrivacyGuard.reset()
    }

    fun isSensitivePackage(packageName: String?): Boolean =
        PocketPrivacyGuard.isSensitivePackage(packageName)

    /**
     * 对纯文本执行「正则 + 敏感上下文实体识别」双通道脱敏，并将明文存入 [RegisterBus]。
     */
    @Synchronized
    fun sanitizeText(
        rawText: String,
        packageName: String? = null,
        force: Boolean = false,
    ): HybridSanitizeResult {
        if (rawText.isBlank()) {
            return HybridSanitizeResult(rawText, 0, emptyMap(), emptySet())
        }
        val sensitiveApp = isSensitivePackage(packageName)
        val hasHighRiskCue = HIGH_RISK_TEXT_TRIGGER.containsMatchIn(rawText)
        if (!force && !sensitiveApp && !hasHighRiskCue) {
            return HybridSanitizeResult(rawText, 0, emptyMap(), emptySet())
        }

        val candidates = mutableListOf<DetectedSpan>()

        // 通道 A：高精度结构化正则
        collectRegexSpans(rawText, candidates)

        // 通道 B：敏感上下文实体识别（独立 OTP、Luhn 空格卡号、邻近口令/密码、非固定句式人名/地址）
        collectContextEntitySpans(rawText, candidates)

        // 通道 C：可插拔端侧 NER
        customRecognizer?.detect(rawText, packageName)?.forEach { span ->
            if (span.startInclusive in 0 until span.endExclusive && span.endExclusive <= rawText.length) {
                candidates += DetectedSpan(
                    range = span.startInclusive until span.endExclusive,
                    category = span.category,
                    value = rawText.substring(span.startInclusive, span.endExclusive),
                )
            }
        }

        if (candidates.isEmpty()) {
            return HybridSanitizeResult(rawText, 0, emptyMap(), emptySet())
        }

        // 去重与区间冲突消解：按类别优先级升序、长度降序
        val resolved = resolveNonOverlapping(candidates)
        val entityMap = LinkedHashMap<String, String>()
        val valueToKey = HashMap<String, String>()
        val categories = LinkedHashSet<SensitiveCategory>()
        val sb = StringBuilder()
        var cursor = 0
        var replaced = 0

        for (span in resolved) {
            if (span.range.first < cursor) continue
            sb.append(rawText, cursor, span.range.first)
            val key = valueToKey.getOrPut(span.value) {
                val id = "hpf_${counter.getAndIncrement()}"
                RegisterBus.put(id, span.value)
                entityMap[id] = span.value
                id
            }
            sb.append("<${span.category.labelZh}:${RegisterBus.PREFIX}$key>")
            categories.add(span.category)
            replaced++
            cursor = span.range.last + 1
        }
        sb.append(rawText, cursor, rawText.length)

        return HybridSanitizeResult(
            sanitizedText = sb.toString(),
            replacedCount = replaced,
            maskedEntities = entityMap,
            detectedCategories = categories,
        )
    }

    /**
     * 对无障碍树或工具输出 JSON 执行递归脱敏。
     */
    fun sanitizeScreenJson(
        jsonString: String,
        packageName: String? = null,
        force: Boolean = false,
    ): String {
        if (jsonString.isBlank()) return jsonString
        val shouldScan = force ||
            isSensitivePackage(packageName) ||
            jsonString.contains("\"secret\"", ignoreCase = true) ||
            HIGH_RISK_TEXT_TRIGGER.containsMatchIn(jsonString)
        if (!shouldScan) return jsonString

        return try {
            val root = Json.parseToJsonElement(jsonString)
            sanitizeJsonElement(root, packageName, force).toString()
        } catch (_: Exception) {
            sanitizeText(jsonString, packageName, force).sanitizedText
        }
    }

    /**
     * 根据本机 OCR 识别框列表 ([ocrLines]) 与无障碍控件 ([uiElements]) 规划截图发送云端 VLM 前的打码坐标区域。
     *
     * 重点覆盖场景：
     * - 单行或跨行（标签在左/上、值在右/下）的密码、验证码（4~8 位数字/字母）、银行卡号、身份证号；
     * - 标记为 `secret = true` 的无障碍密码输入框；
     * - 敏感 App 下的手机号、邮箱、Token 与高敏感实付/密码框区域。
     */
    fun planVisualRedactions(
        ocrLines: List<VisibleTextLine>,
        uiElements: List<UiElement> = emptyList(),
        packageName: String? = null,
        force: Boolean = false,
    ): List<RedactionRegion> {
        val regions = ArrayList<RedactionRegion>()
        val sensitiveApp = force || isSensitivePackage(packageName)

        // 1. 无障碍密码输入框强制深色遮罩
        for (el in uiElements) {
            if (el.secret && el.bounds.size == 4 && isValidNormalizedBox(el.bounds)) {
                regions += RedactionRegion(
                    bounds = padBounds(el.bounds, pad = 4),
                    category = SensitiveCategory.PASSWORD,
                    style = MaskStyle.SOLID_MASK,
                    confidence = 1.0f,
                    sourceTextPreview = "[secret_field]",
                )
            }
        }

        if (ocrLines.isEmpty()) return mergeNearbyRegions(regions)

        // 按屏幕视觉阅读顺序（从上到下、从左到右）排序 OCR 行，便于跨行上下文推断
        val indexedLines = ocrLines
            .filter { it.bounds.size == 4 && isValidNormalizedBox(it.bounds) && it.text.isNotBlank() }
            .sortedWith(compareBy<VisibleTextLine> { it.bounds[1] }.thenBy { it.bounds[0] })

        for (i in indexedLines.indices) {
            val line = indexedLines[i]
            val text = line.text.trim()
            val prev = indexedLines.getOrNull(i - 1)
            val next = indexedLines.getOrNull(i + 1)

            val prevProvidesOtpContext = prev != null &&
                isSpatiallyAdjacent(prev.bounds, line.bounds) &&
                OTP_CONTEXT_CUE.containsMatchIn(prev.text)
            val prevProvidesPasswordContext = prev != null &&
                isSpatiallyAdjacent(prev.bounds, line.bounds) &&
                PASSWORD_CONTEXT_CUE.containsMatchIn(prev.text)
            val prevProvidesCardContext = prev != null &&
                isSpatiallyAdjacent(prev.bounds, line.bounds) &&
                CARD_CONTEXT_CUE.containsMatchIn(prev.text)
            val nextProvidesOtpContext = next != null &&
                isSpatiallyAdjacent(line.bounds, next.bounds) &&
                OTP_CONTEXT_CUE.containsMatchIn(next.text)

            val category = classifyOcrLine(
                text = text,
                sensitiveApp = sensitiveApp,
                otpContext = prevProvidesOtpContext || nextProvidesOtpContext,
                passwordContext = prevProvidesPasswordContext,
                cardContext = prevProvidesCardContext,
            )

            if (category != null) {
                val style = when (category) {
                    SensitiveCategory.PASSWORD, SensitiveCategory.SECRET_TOKEN -> MaskStyle.SOLID_MASK
                    else -> MaskStyle.PIXELATE_BLUR
                }
                regions += RedactionRegion(
                    bounds = padBounds(line.bounds, pad = 4),
                    category = category,
                    style = style,
                    confidence = line.confidence ?: 0.95f,
                    sourceTextPreview = "<${category.labelZh}>",
                )
            }
        }

        return mergeNearbyRegions(regions)
    }

    /**
     * 纯 Kotlin 像素缓冲区本地打码实现（可直接用于 JVM 单元测试与无 Canvas 场景）：
     * 对每个 [RedactionRegion] 对应的像素矩形区域执行块状马赛克均值模糊 ([MaskStyle.PIXELATE_BLUR]) 或深色实体遮罩 ([MaskStyle.SOLID_MASK])。
     */
    fun redactPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        regions: List<RedactionRegion>,
        blockSize: Int = DEFAULT_MOSAIC_BLOCK_PX,
        maskColor: Int = DEFAULT_MASK_COLOR,
    ): IntArray {
        if (width <= 0 || height <= 0 || pixels.size < width * height || regions.isEmpty()) {
            return pixels
        }
        val effectiveBlock = max(4, blockSize)
        val out = pixels.copyOf()

        for (region in regions) {
            val l = toPx(region.bounds[0], width)
            val t = toPx(region.bounds[1], height)
            val r = toPx(region.bounds[2], width)
            val b = toPx(region.bounds[3], height)
            if (r < l || b < t) continue

            when (region.style) {
                MaskStyle.SOLID_MASK -> {
                    for (y in t..b) {
                        val rowOffset = y * width
                        for (x in l..r) {
                            out[rowOffset + x] = maskColor
                        }
                    }
                }
                MaskStyle.PIXELATE_BLUR -> {
                    var by = t
                    while (by <= b) {
                        val blockBottom = min(b, by + effectiveBlock - 1)
                        var bx = l
                        while (bx <= r) {
                            val blockRight = min(r, bx + effectiveBlock - 1)
                            var sumA = 0L
                            var sumR = 0L
                            var sumG = 0L
                            var sumB = 0L
                            var count = 0
                            for (y in by..blockBottom) {
                                val rowOffset = y * width
                                for (x in bx..blockRight) {
                                    val c = out[rowOffset + x]
                                    sumA += (c ushr 24) and 0xFF
                                    sumR += (c ushr 16) and 0xFF
                                    sumG += (c ushr 8) and 0xFF
                                    sumB += c and 0xFF
                                    count++
                                }
                            }
                            if (count > 0) {
                                val avg = (((sumA / count).toInt() and 0xFF) shl 24) or
                                    (((sumR / count).toInt() and 0xFF) shl 16) or
                                    (((sumG / count).toInt() and 0xFF) shl 8) or
                                    ((sumB / count).toInt() and 0xFF)
                                for (y in by..blockBottom) {
                                    val rowOffset = y * width
                                    for (x in bx..blockRight) {
                                        out[rowOffset + x] = avg
                                    }
                                }
                            }
                            bx += effectiveBlock
                        }
                        by += effectiveBlock
                    }
                }
            }
        }
        return out
    }

    private fun classifyOcrLine(
        text: String,
        sensitiveApp: Boolean,
        otpContext: Boolean,
        passwordContext: Boolean,
        cardContext: Boolean,
    ): SensitiveCategory? {
        if (PASSWORD_INLINE_REGEX.containsMatchIn(text) ||
            (passwordContext && !PASSWORD_CONTEXT_CUE.matches(text) && text.length in 3..32)
        ) {
            return SensitiveCategory.PASSWORD
        }
        if (TOKEN_REGEX.containsMatchIn(text)) {
            return SensitiveCategory.SECRET_TOKEN
        }
        if (ID_CARD_REGEX.containsMatchIn(text)) {
            return SensitiveCategory.ID_CARD
        }
        if (VERIFY_CODE_REGEX.containsMatchIn(text) ||
            (otpContext && STANDALONE_OTP_REGEX.matches(text))
        ) {
            return SensitiveCategory.VERIFY_CODE
        }
        if (BANK_CARD_FLEX_REGEX.findAll(text).any { isLikelyBankCard(it.value, cardContext || sensitiveApp) }) {
            return SensitiveCategory.BANK_CARD
        }
        if (PHONE_REGEX.containsMatchIn(text) && (sensitiveApp || PHONE_CONTEXT_CUE.containsMatchIn(text))) {
            return SensitiveCategory.PHONE
        }
        if (EMAIL_REGEX.containsMatchIn(text) && sensitiveApp) {
            return SensitiveCategory.EMAIL
        }
        return null
    }

    private fun collectRegexSpans(rawText: String, out: MutableList<DetectedSpan>) {
        // 密码 / 口令
        PASSWORD_INLINE_REGEX.findAll(rawText).forEach { m ->
            m.groups[1]?.let { out += DetectedSpan(it.range, SensitiveCategory.PASSWORD, it.value) }
        }
        // API Key / Bearer Token
        TOKEN_REGEX.findAll(rawText).forEach { m ->
            val g = m.groups[1] ?: m.groups[0]
            if (g != null) out += DetectedSpan(g.range, SensitiveCategory.SECRET_TOKEN, g.value)
        }
        // 18 位身份证
        ID_CARD_REGEX.findAll(rawText).forEach { m ->
            m.groups[1]?.let { out += DetectedSpan(it.range, SensitiveCategory.ID_CARD, it.value) }
        }
        // 银行卡号（连续 16~19 位或每 4 位空格/横杠分隔）
        BANK_CARD_FLEX_REGEX.findAll(rawText).forEach { m ->
            if (isLikelyBankCard(m.value, hasCardContext = true)) {
                out += DetectedSpan(m.range, SensitiveCategory.BANK_CARD, m.value)
            }
        }
        // 显式标签验证码
        VERIFY_CODE_REGEX.findAll(rawText).forEach { m ->
            m.groups[1]?.let { out += DetectedSpan(it.range, SensitiveCategory.VERIFY_CODE, it.value) }
        }
        // 手机号
        PHONE_REGEX.findAll(rawText).forEach { m ->
            m.groups[1]?.let { out += DetectedSpan(it.range, SensitiveCategory.PHONE, it.value) }
        }
        // 邮箱
        EMAIL_REGEX.findAll(rawText).forEach { m ->
            out += DetectedSpan(m.range, SensitiveCategory.EMAIL, m.value)
        }
        // 姓名
        NAME_FIELD_REGEX.findAll(rawText).forEach { m ->
            m.groups[1]?.let { out += DetectedSpan(it.range, SensitiveCategory.NAME, it.value) }
        }
        // 地址
        ADDRESS_REGEX.findAll(rawText).forEach { m ->
            if (m.value.length >= 6) {
                out += DetectedSpan(m.range, SensitiveCategory.ADDRESS, m.value)
            }
        }
        // 金额
        AMOUNT_REGEX.findAll(rawText).forEach { m ->
            val g = m.groups[1] ?: m.groups[2]
            if (g != null && g.value.isNotEmpty()) {
                out += DetectedSpan(g.range, SensitiveCategory.AMOUNT, g.value)
            }
        }
    }

    private fun collectContextEntitySpans(rawText: String, out: MutableList<DetectedSpan>) {
        // 1. 非固定标签的上下文验证码（如 "您的动态数字为 839201，请勿泄露"、"OTP is 529104"）
        if (OTP_CONTEXT_CUE.containsMatchIn(rawText)) {
            CONTEXT_OTP_VALUE_REGEX.findAll(rawText).forEach { m ->
                m.groups[1]?.let { out += DetectedSpan(it.range, SensitiveCategory.VERIFY_CODE, it.value) }
            }
        }

        // 2. 上下文关联人名（如 "转账给李雷"、"向韩梅梅汇款"、"收款方 王小明"）
        CONTEXT_PERSON_REGEX.findAll(rawText).forEach { m ->
            m.groups[1]?.let { g ->
                val name = g.value
                if (name.length in 2..4 && name !in NON_PERSON_STOPWORDS) {
                    out += DetectedSpan(g.range, SensitiveCategory.NAME, name)
                }
            }
        }

        // 3. 掩码卡号/证件号提示（如 "6222****8899"）在银行/支付上下文也脱敏
        if (CARD_CONTEXT_CUE.containsMatchIn(rawText)) {
            MASKED_CARD_REGEX.findAll(rawText).forEach { m ->
                out += DetectedSpan(m.range, SensitiveCategory.BANK_CARD, m.value)
            }
        }
    }

    private fun resolveNonOverlapping(spans: List<DetectedSpan>): List<DetectedSpan> {
        val sorted = spans.sortedWith(
            compareBy<DetectedSpan> { it.category.priority }
                .thenByDescending { it.range.last - it.range.first }
        )
        val kept = ArrayList<DetectedSpan>()
        for (candidate in sorted) {
            val overlaps = kept.any { existing ->
                max(existing.range.first, candidate.range.first) <= min(existing.range.last, candidate.range.last)
            }
            if (!overlaps) kept += candidate
        }
        kept.sortBy { it.range.first }
        return kept
    }

    private fun sanitizeJsonElement(element: JsonElement, packageName: String?, force: Boolean): JsonElement {
        return when (element) {
            is JsonObject -> {
                val newMap = LinkedHashMap<String, JsonElement>(element.size)
                val isSecretNode = (element["secret"] as? JsonPrimitive)?.content == "true"
                for ((k, v) in element) {
                    newMap[k] = if (v is JsonPrimitive && v.isString && (k in SENSITIVE_JSON_KEYS || isSecretNode)) {
                        if (isSecretNode && k in setOf("text", "name", "value") && v.content.isNotBlank()) {
                            val key = "hpf_${counter.getAndIncrement()}"
                            RegisterBus.put(key, v.content)
                            JsonPrimitive("<${SensitiveCategory.PASSWORD.labelZh}:${RegisterBus.PREFIX}$key>")
                        } else {
                            JsonPrimitive(sanitizeText(v.content, packageName, force = true).sanitizedText)
                        }
                    } else {
                        sanitizeJsonElement(v, packageName, force)
                    }
                }
                JsonObject(newMap)
            }
            is JsonArray -> JsonArray(element.map { sanitizeJsonElement(it, packageName, force) })
            is JsonPrimitive -> if (element.isString) {
                JsonPrimitive(sanitizeText(element.content, packageName, force).sanitizedText)
            } else {
                element
            }
        }
    }

    /** 判定银行卡号：排除纯手机号/身份证子串，16~19 位数字执行 Luhn 校验或卡号上下文确认。 */
    internal fun isLikelyBankCard(raw: String, hasCardContext: Boolean): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.length !in 16..19) return false
        if (digits.length == 18 && ID_CARD_REGEX.matches(digits)) return false
        return luhnValid(digits) || (hasCardContext && digits.startsWith("62") || digits.startsWith("4") || digits.startsWith("5"))
    }

    internal fun luhnValid(digits: String): Boolean {
        if (digits.any { !it.isDigit() }) return false
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    private fun isValidNormalizedBox(b: List<Int>): Boolean =
        b.size == 4 && b[0] in 0..999 && b[1] in 0..999 && b[2] in 0..999 && b[3] in 0..999 && b[2] > b[0] && b[3] > b[1]

    private fun padBounds(b: List<Int>, pad: Int): List<Int> = listOf(
        (b[0] - pad).coerceIn(0, 999),
        (b[1] - pad).coerceIn(0, 999),
        (b[2] + pad).coerceIn(0, 999),
        (b[3] + pad).coerceIn(0, 999),
    )

    private fun isSpatiallyAdjacent(a: List<Int>, b: List<Int>): Boolean {
        val verticalGap = max(0, b[1] - a[3])
        val sameRow = min(a[3], b[3]) >= max(a[1], b[1]) - 8
        val horizontalGap = max(0, b[0] - a[2])
        return (sameRow && horizontalGap <= 260) || (verticalGap <= 90 && absCenterDelta(a, b) <= 300)
    }

    private fun absCenterDelta(a: List<Int>, b: List<Int>): Int {
        val ca = (a[0] + a[2]) / 2
        val cb = (b[0] + b[2]) / 2
        return kotlin.math.abs(ca - cb)
    }

    private fun mergeNearbyRegions(regions: List<RedactionRegion>): List<RedactionRegion> {
        if (regions.size <= 1) return regions
        val merged = ArrayList<RedactionRegion>()
        for (r in regions) {
            val idx = merged.indexOfFirst { existing ->
                existing.category == r.category &&
                    max(existing.bounds[0], r.bounds[0]) <= min(existing.bounds[2], r.bounds[2]) + 4 &&
                    max(existing.bounds[1], r.bounds[1]) <= min(existing.bounds[3], r.bounds[3]) + 4
            }
            if (idx >= 0) {
                val e = merged[idx]
                merged[idx] = e.copy(
                    bounds = listOf(
                        min(e.bounds[0], r.bounds[0]),
                        min(e.bounds[1], r.bounds[1]),
                        max(e.bounds[2], r.bounds[2]),
                        max(e.bounds[3], r.bounds[3]),
                    ),
                    style = if (e.style == MaskStyle.SOLID_MASK || r.style == MaskStyle.SOLID_MASK) MaskStyle.SOLID_MASK else MaskStyle.PIXELATE_BLUR,
                    confidence = max(e.confidence, r.confidence),
                )
            } else {
                merged += r
            }
        }
        return merged
    }

    private fun toPx(norm: Int, size: Int): Int =
        (norm.coerceIn(0, 999) * (size - 1) / 999.0).roundToInt().coerceIn(0, size - 1)

    private data class DetectedSpan(
        val range: IntRange,
        val category: SensitiveCategory,
        val value: String,
    )

    private const val DEFAULT_MOSAIC_BLOCK_PX = 12
    private const val DEFAULT_MASK_COLOR = 0xFF111827.toInt()

    private val PHONE_REGEX = Regex("""(?<!\d)(?:(?:\+?86)?\s*)?(1[3-9]\d{9})(?!\d)""")
    private val ID_CARD_REGEX = Regex("""(?<!\d)([1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx])(?!\w)""")
    private val BANK_CARD_FLEX_REGEX = Regex("""(?<!\d)(?:\d{4}[-\s]?){3,4}\d{3,4}(?!\d)""")
    private val MASKED_CARD_REGEX = Regex("""(?<!\d)\d{4,6}\*{4,10}\d{4}(?!\d)""")
    private val VERIFY_CODE_REGEX = Regex("""(?:(?:验证码|动态码|校验码|安全码|取件码|确认码|OTP)\s*[:：是为]?\s*)(\d{4,8})(?!\d)""", RegexOption.IGNORE_CASE)
    private val STANDALONE_OTP_REGEX = Regex("""\d{4,8}""")
    private val CONTEXT_OTP_VALUE_REGEX = Regex("""(?<!\d)(\d{4,8})(?!\d)""")
    private val PASSWORD_INLINE_REGEX = Regex("""(?:(?:密码|支付密码|登录密码|口令|PIN码?|password|passcode|pwd)\s*[:：是为]?\s*)([^\s，,。;；]{4,32})""", RegexOption.IGNORE_CASE)
    private val TOKEN_REGEX = Regex("""\b(?:sk-[A-Za-z0-9_-]{16,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})\b|Bearer\s+([A-Za-z0-9._\-]{16,})""")
    private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val AMOUNT_REGEX = Regex("""(?:(?:工资|薪资|实付|转账|余额|合计|总额)\s*[:：]?\s*(?:¥|￥|RMB|\$)?\s*)(\d+(?:\.\d{1,2})?)|(?:(?:¥|￥|RMB|\$)\s*)(\d+(?:\.\d{1,2})?)""")
    private val ADDRESS_REGEX = Regex("""(?:[\u4e00-\u9fa5]{2,8}(?:省|市|区|县|镇|街道|路|弄|巷|号院|大厦|小区|单元|室|栋|幢|号)){2,}""")
    private val NAME_FIELD_REGEX = Regex("""(?:(?:姓名|收件人|收货人|联系人|发件人|户名|持卡人)\s*[:：]?\s*)([\u4e00-\u9fa5]{2,4})(?![\u4e00-\u9fa5])""")
    private val CONTEXT_PERSON_REGEX = Regex("""(?:(?:转账给|汇款给|发给|转给|收款方|收款人|打款给)\s*[:：]?\s*)([\u4e00-\u9fa5]{2,4})(?![\u4e00-\u9fa5])""")

    private val OTP_CONTEXT_CUE = Regex("""验证码|动态码|校验码|安全码|OTP|verification\s*code|passcode""", RegexOption.IGNORE_CASE)
    private val PASSWORD_CONTEXT_CUE = Regex("""密码|口令|password|passcode|pin码?|pwd""", RegexOption.IGNORE_CASE)
    private val CARD_CONTEXT_CUE = Regex("""银行卡|储蓄卡|信用卡|卡号|card\s*number""", RegexOption.IGNORE_CASE)
    private val PHONE_CONTEXT_CUE = Regex("""手机|电话|联系方式|phone|tel""", RegexOption.IGNORE_CASE)
    private val HIGH_RISK_TEXT_TRIGGER = Regex("""验证码|密码|口令|银行卡|身份证|sk-[A-Za-z0-9_-]{16,}|Bearer\s+[A-Za-z0-9._\-]{16,}""", RegexOption.IGNORE_CASE)

    private val NON_PERSON_STOPWORDS = setOf("对方", "好友", "朋友", "商户", "商家", "银行", "客服", "本人", "自己", "公司")
    private val SENSITIVE_JSON_KEYS = setOf("text", "name", "desc", "description", "content", "hint", "title", "value", "extracted")
}

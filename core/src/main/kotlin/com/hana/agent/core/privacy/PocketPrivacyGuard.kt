package com.hana.agent.core.privacy

import com.hana.agent.core.bus.RegisterBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端侧零泄漏隐私防火墙 (Pocket Privacy Guard)
 *
 * 紧密结合 v5.0 RegisterBus 跨平面零拷贝语义寄存器：
 * 当处于敏感 App（微信、短信、银行、支付宝、备忘录等）时，扫描界面可见文本，识别并提取敏感隐私实体（真实姓名、
 * 手机号、身份证、银行卡、详细地址、薪水金额、验证码），并在端侧将其存入 RegisterBus 本地内存 ($reg:priv_N)。
 * 发往云端主脑的无障碍树与对话历史只保留语义脱敏占位符（如 "<手机号:$reg:priv_1>"），
 * 当主脑规划并触发物理打字 (phone_type_text) 时，经 RegisterBus.resolve() 在端侧无感还原为真实明文。
 */
object PocketPrivacyGuard {
    private val counter = AtomicInteger(1)

    // 常见高危敏感应用包名特征（社交私聊、即时通讯、短信、电子钱包、各大银行、便签备忘录）
    private val SENSITIVE_PACKAGE_PREFIXES = setOf(
        "com.tencent.mm",              // 微信
        "com.tencent.mobileqq",         // QQ
        "com.eg.android.AlipayGphone",   // 支付宝
        "com.android.mms",              // 系统短信
        "com.google.android.apps.messaging", // 谷歌信息
        "com.samsung.android.messaging",
        "com.coloros.mms",
        "com.hihonor.mms",
        "com.miui.mms",
        "com.vivo.mms",
        "com.miui.notes",               // 小米便签
        "com.huawei.notepad",           // 华为备忘录
        "com.samsung.android.app.notes",
        "com.android.notes",
        "com.youdao.note",              // 有道云笔记
    )

    private val SENSITIVE_PACKAGE_KEYWORDS = listOf(
        "bank", "wallet", "alipay", "pay", "money", "credit", "finance", "mms", "messaging", "notes"
    )

    // 正则特征提取
    private val PHONE_REGEX = Regex("""(?<!\d)(?:(?:\+?86)?\s*)?(1[3-9]\d{9})(?!\d)""")
    private val ID_CARD_REGEX = Regex("""(?<!\d)([1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx])(?!\w)""")
    private val BANK_CARD_REGEX = Regex("""(?<!\d)([1-9]\d{15,18})(?!\d)""")
    private val VERIFY_CODE_REGEX = Regex("""(?:(?:验证码|动态码|校验码|安全码)\s*[:：是为]?\s*)(\d{4,6})(?!\d)""")
    private val SALARY_OR_AMOUNT_REGEX = Regex("""(?:(?:工资|薪资|实付|转账|余额|合计|总额)\s*[:：]?\s*(?:¥|￥|RMB|\$)?\s*)(\d+(?:\.\d{1,2})?)|(?:(?:¥|￥|RMB|\$)\s*)(\d+(?:\.\d{1,2})?)""")
    private val ADDRESS_REGEX = Regex("""(?:[\u4e00-\u9fa5]{2,8}(?:省|市|区|县|镇|街道|路|弄|巷|号院|大厦|小区|单元|室|栋|幢|号)){2,}""")
    private val NAME_FIELD_REGEX = Regex("""(?:(?:姓名|收件人|收货人|联系人|发件人|户名|持卡人)\s*[:：]?\s*)([\u4e00-\u9fa5]{2,4})(?![\u4e00-\u9fa5])""")

    /**
     * 重置隐私防火墙寄存器计数（通常随任务生命周期重置）。
     */
    @Synchronized
    fun reset() {
        counter.set(1)
    }

    /**
     * 判定目标包名是否属于隐私敏感 App。
     */
    fun isSensitivePackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        if (SENSITIVE_PACKAGE_PREFIXES.any { pkg.startsWith(it.lowercase()) }) return true
        return SENSITIVE_PACKAGE_KEYWORDS.any { pkg.contains(it) }
    }

    data class SanitizedResult(
        val sanitizedText: String,
        val replacedCount: Int,
        val maskedEntities: Map<String, String>,
    )

    private data class EntitySpan(
        val range: IntRange,
        val label: String,
        val value: String,
        val priority: Int,
    )

    /**
     * 对单段文本执行隐私脱敏与 RegisterBus 寄存。
     */
    @Synchronized
    fun sanitize(
        rawText: String,
        packageName: String? = null,
        force: Boolean = false,
    ): SanitizedResult {
        if (!force && !isSensitivePackage(packageName)) {
            return SanitizedResult(rawText, 0, emptyMap())
        }
        if (rawText.isBlank()) {
            return SanitizedResult(rawText, 0, emptyMap())
        }

        val spans = mutableListOf<EntitySpan>()

        // 1. 身份证号 (18 位) - 优先级 1
        ID_CARD_REGEX.findAll(rawText).forEach { m ->
            val g = m.groups[1]
            if (g != null) {
                spans += EntitySpan(g.range, "身份证", g.value, priority = 1)
            }
        }

        // 2. 银行卡号 (16~19 位) - 优先级 2
        BANK_CARD_REGEX.findAll(rawText).forEach { m ->
            val g = m.groups[1]
            if (g != null) {
                spans += EntitySpan(g.range, "银行卡", g.value, priority = 2)
            }
        }

        // 3. 手机号 (11 位) - 优先级 3
        PHONE_REGEX.findAll(rawText).forEach { m ->
            val g = m.groups[1]
            if (g != null) {
                spans += EntitySpan(g.range, "手机号", g.value, priority = 3)
            }
        }

        // 4. 验证码 - 优先级 4
        VERIFY_CODE_REGEX.findAll(rawText).forEach { m ->
            val g = m.groups[1]
            if (g != null) {
                spans += EntitySpan(g.range, "验证码", g.value, priority = 4)
            }
        }

        // 5. 姓名关联字段 - 优先级 5
        NAME_FIELD_REGEX.findAll(rawText).forEach { m ->
            val g = m.groups[1]
            if (g != null) {
                spans += EntitySpan(g.range, "姓名", g.value, priority = 5)
            }
        }

        // 6. 详细地址 - 优先级 6
        ADDRESS_REGEX.findAll(rawText).forEach { m ->
            if (m.value.length >= 6) {
                spans += EntitySpan(m.range, "地址", m.value, priority = 6)
            }
        }

        // 7. 薪资与核心金额 - 优先级 7
        SALARY_OR_AMOUNT_REGEX.findAll(rawText).forEach { m ->
            val g = m.groups[1] ?: m.groups[2]
            if (g != null && g.value.isNotEmpty()) {
                spans += EntitySpan(g.range, "金额", g.value, priority = 7)
            }
        }

        // 按优先级升序（数值越小优先级越高），若相同则按区间长度降序排序
        val sortedByPriority = spans.sortedWith(
            compareBy<EntitySpan> { it.priority }
                .thenByDescending { it.range.last - it.range.first }
        )

        val nonOverlapping = mutableListOf<EntitySpan>()
        for (candidate in sortedByPriority) {
            val overlaps = nonOverlapping.any { existing ->
                maxOf(existing.range.first, candidate.range.first) <= minOf(existing.range.last, candidate.range.last)
            }
            if (!overlaps) {
                nonOverlapping.add(candidate)
            }
        }

        // 按起始坐标排序以支持顺序构建文本
        nonOverlapping.sortBy { it.range.first }

        val entityMap = mutableMapOf<String, String>()
        val valueToKey = mutableMapOf<String, String>()
        val sb = StringBuilder()
        var lastIdx = 0
        var replaced = 0

        for (span in nonOverlapping) {
            sb.append(rawText.substring(lastIdx, span.range.first))
            val key = valueToKey.getOrPut(span.value) {
                val newKey = "priv_${counter.getAndIncrement()}"
                RegisterBus.put(newKey, span.value)
                entityMap[newKey] = span.value
                newKey
            }
            sb.append("<${span.label}:${RegisterBus.PREFIX}$key>")
            replaced++
            lastIdx = span.range.last + 1
        }
        sb.append(rawText.substring(lastIdx))

        return SanitizedResult(sb.toString(), replaced, entityMap)
    }

    /**
     * 针对文字的便捷脱敏调用。
     */
    fun sanitizeText(text: String, packageName: String? = null, force: Boolean = false): String {
        return sanitize(text, packageName, force).sanitizedText
    }

    /**
     * 对无障碍树或工具输出的 JSON 字符串进行递归脱敏。
     */
    fun sanitizeScreenJson(
        jsonString: String,
        packageName: String? = null,
        force: Boolean = false,
    ): String {
        if (!force && !isSensitivePackage(packageName)) return jsonString
        if (jsonString.isBlank()) return jsonString

        return try {
            val root = Json.parseToJsonElement(jsonString)
            val sanitized = sanitizeJsonElement(root, packageName, force)
            sanitized.toString()
        } catch (_: Exception) {
            // 解析失败时降级为对普通文本全文脱敏
            sanitizeText(jsonString, packageName, force)
        }
    }

    private fun sanitizeJsonElement(element: JsonElement, packageName: String?, force: Boolean): JsonElement {
        return when (element) {
            is JsonObject -> {
                val newMap = mutableMapOf<String, JsonElement>()
                for ((k, v) in element) {
                    newMap[k] = if (k in SENSITIVE_JSON_KEYS && v is JsonPrimitive && v.isString) {
                        JsonPrimitive(sanitizeText(v.content, packageName, force = true))
                    } else {
                        sanitizeJsonElement(v, packageName, force)
                    }
                }
                JsonObject(newMap)
            }
            is JsonArray -> {
                JsonArray(element.map { sanitizeJsonElement(it, packageName, force) })
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    JsonPrimitive(sanitizeText(element.content, packageName, force))
                } else {
                    element
                }
            }
        }
    }

    private val SENSITIVE_JSON_KEYS = setOf(
        "text", "name", "desc", "description", "content", "hint", "title", "value", "extracted"
    )
}

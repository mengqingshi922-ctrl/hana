package com.hana.agent.proactive

import android.content.ClipboardManager
import android.content.Context
import java.security.MessageDigest
import kotlin.math.abs

/**
 * 剪贴板“任意门”识别出的意图类别。
 */
enum class ClipboardIntentKind {
    /** 商品口令或电商链接 -> 推荐「后台三平台比价」。 */
    ECOMMERCE_PRODUCT,

    /** 地址、商圈或餐厅信息 -> 推荐「查路线与团购」。 */
    ADDRESS_OR_RESTAURANT,

    /** 长文本或外文段落 -> 推荐「提炼三点摘要」。 */
    LONG_OR_FOREIGN_TEXT,
}

/**
 * 剪贴板“任意门”生成的非侵入式建议胶囊数据模型（遵循 Defer-by-Default，不主动抢焦点）。
 *
 * @property id 基于安全摘要生成的去重标识
 * @property intent 识别出的剪贴板意图类型
 * @property pillTitle 胶囊/芯片上的短标题（如「检测到商品链接」）
 * @property recommendedActionLabel 一键推荐操作文案（如「后台三平台比价」）
 * @property previewSnippet 脱敏截断后的短预览文本
 * @property recommendedPrompt 用户点击推荐动作后下发给 Hana 的完整任务指令
 * @property detectedAtMs 侦测时间戳（毫秒）
 * @property nonIntrusive 是否严格保持非侵入轻提示语义（默认 true）
 */
data class ClipboardPortalSuggestion(
    val id: String,
    val intent: ClipboardIntentKind,
    val pillTitle: String,
    val recommendedActionLabel: String,
    val previewSnippet: String,
    val recommendedPrompt: String,
    val detectedAtMs: Long,
    val nonIntrusive: Boolean = true,
)

/**
 * 剪贴板“任意门”轻量意图侦测器。
 *
 * 安全红线：
 * 1. 隐私敏感文本（密码、API Key、私钥、JWT、助记词、验证码、银行卡/身份证号等）一律拦截返回 null；
 * 2. 仅在安全且非隐私文本上匹配商品口令/电商链接、地址/餐厅、长文/外文三类高价值意图；
 * 3. 默认去重防止相同剪贴板内容重复触发轻提示。
 */
class ClipboardPortalDetector(
    private val dedupeWindowMs: Long = DEFAULT_DEDUPE_WINDOW_MS,
) {
    private var lastHash: String? = null
    private var lastDetectedMs: Long = 0L

    /**
     * 检查剪贴板文本并在未去重命中时返回非侵入建议；若内容为空、含隐私敏感信息或近期已提示过则返回 null。
     */
    @Synchronized
    fun inspect(rawText: String?, nowMs: Long = System.currentTimeMillis()): ClipboardPortalSuggestion? {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return null
        val hash = sha256Short(text)
        if (hash == lastHash && (nowMs - lastDetectedMs) in 0L until dedupeWindowMs) {
            return null
        }
        val suggestion = detect(text, nowMs) ?: return null
        lastHash = hash
        lastDetectedMs = nowMs
        return suggestion
    }

    /**
     * 安全读取 Android 系统剪贴板并尝试侦测（后台无焦点或系统限制读取时静默返回 null）。
     */
    fun inspectSystemClipboard(context: Context, nowMs: Long = System.currentTimeMillis()): ClipboardPortalSuggestion? {
        val text = runCatching {
            val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip ?: return@runCatching null
            if (clip.itemCount <= 0) return@runCatching null
            clip.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
        return inspect(text, nowMs)
    }

    fun resetDedupe() {
        synchronized(this) {
            lastHash = null
            lastDetectedMs = 0L
        }
    }

    companion object {
        const val ACTION_COMPARE_PRICES = "后台三平台比价"
        const val ACTION_ROUTE_AND_DEALS = "查路线与团购"
        const val ACTION_SUMMARIZE_THREE = "提炼三点摘要"

        private const val DEFAULT_DEDUPE_WINDOW_MS = 5 * 60_000L
        private const val MAX_SNIPPET_CHARS = 48
        private const val MAX_PROMPT_CONTEXT_CHARS = 600

        // 常见敏感凭据与密钥前缀
        private val SECRET_PREFIX_REGEX = Regex(
            pattern = """(-----BEGIN [A-Z ]*PRIVATE KEY-----|sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|gho_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z\-_]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|Bearer\s+[A-Za-z0-9\-._~+/]+=*|eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})""",
        )

        // 敏感密码/私钥/验证码关键字
        private val SENSITIVE_KEYWORDS = listOf(
            "password", "passwd", "passcode", "pin码", "支付密码", "交易密码",
            "登录密码", "开机密码", "wifi密码", "密码是", "密码为", "密码:", "密码：",
            "私钥", "private key", "secret key", "助记词", "mnemonic", "seed phrase",
            "动态口令", "验证码", "校验码", "verification code", "otp",
        )

        // 银行卡号（16~19位连续或空格分组数字）与 18 位中国身份证号
        private val BANK_CARD_REGEX = Regex("""(?<!\d)(?:\d{4}[\s-]?){3}\d{4,7}(?!\d)""")
        private val ID_CARD_REGEX = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")
        private val PURE_OTP_REGEX = Regex("""^\d{4,8}$""")

        // 强密码特征：无空白、含大小写/数字/符号混合的独立长串（排除含 http 链接或淘口令）
        private val PASSWORD_TOKEN_REGEX = Regex("""^[A-Za-z0-9!@#\$%^&*()_+\-=\[\]{};':"\\|,.<>/?]{8,36}$""")

        // 电商链接与商品口令特征
        private val ECOMMERCE_URL_REGEX = Regex(
            """https?://(?:[a-z0-9-]+\.)*(?:taobao\.com|tmall\.com|tb\.cn|jd\.com|3\.cn|pinduoduo\.com|yangkeduo\.com|vip\.com|dewu\.com|amazon\.[a-z.]{2,6}|amzn\.to)(?:/[^\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val TAO_TOKEN_REGEX = Regex("""[￥$₳₴€£][A-Za-z0-9]{7,16}[￥$₳₴€£]""")
        private val ECOMMERCE_HINTS = listOf(
            "打开淘宝", "打开手机淘宝", "手淘", "打开京东", "打开拼多多", "淘口令",
            "fu置", "緮置", "复制此段", "下单立减", "券后价", "到手价", "拼单", "旗舰店",
        )

        // 地图链接与地址/餐厅特征
        private val MAP_URL_REGEX = Regex(
            """https?://(?:[a-z0-9-]+\.)*(?:amap\.com|map\.baidu\.com|maps\.apple\.com|maps\.google\.com|dianping\.com|meituan\.com)(?:/[^\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val ADDRESS_REGION_REGEX = Regex("""(?:省|市|区|县|新区)""")
        private val ADDRESS_STREET_REGEX = Regex("""(?:路|街|大道|巷|弄|号|大厦|广场|中心|写字楼|园区|小区|地铁站)""")
        private val RESTAURANT_HINTS = listOf(
            "餐厅", "火锅", "烤肉", "日料", "烧烤", "菜馆", "食府", "酒楼",
            "咖啡馆", "茶室", "门店地址", "营业时间", "人均¥", "人均￥", "人均", "大众点评",
        )

        // 英文单词与日韩外文识别
        private val ENGLISH_WORD_REGEX = Regex("""[A-Za-z]{2,}""")
        private val JAPANESE_KANA_REGEX = Regex("""[\u3040-\u309F\u30A0-\u30FF]""")
        private val KOREAN_HANGUL_REGEX = Regex("""[\uAC00-\uD7AF]""")
        private val PURE_URL_ONLY_REGEX = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)

        /**
         * 纯无状态意图侦测入口（供单元测试与上层业务直接复用）。
         */
        fun detect(rawText: String?, nowMs: Long = System.currentTimeMillis()): ClipboardPortalSuggestion? {
            val text = rawText?.trim().orEmpty()
            if (text.length < 4) return null
            if (isSensitiveOrUnsafe(text)) return null

            val id = "clip_${sha256Short(text)}"
            val snippet = buildSafeSnippet(text)
            val safeContext = text.take(MAX_PROMPT_CONTEXT_CHARS)

            // 1. 商品口令 / 电商链接 -> 后台三平台比价
            if (isEcommerceProduct(text)) {
                return ClipboardPortalSuggestion(
                    id = id,
                    intent = ClipboardIntentKind.ECOMMERCE_PRODUCT,
                    pillTitle = "检测到商品或口令",
                    recommendedActionLabel = ACTION_COMPARE_PRICES,
                    previewSnippet = snippet,
                    recommendedPrompt = "请帮我在后台对以下商品做主流电商三平台比价并汇总到手价与优惠：$safeContext",
                    detectedAtMs = nowMs,
                    nonIntrusive = true,
                )
            }

            // 2. 地址 / 餐厅 -> 查路线与团购
            if (isAddressOrRestaurant(text)) {
                return ClipboardPortalSuggestion(
                    id = id,
                    intent = ClipboardIntentKind.ADDRESS_OR_RESTAURANT,
                    pillTitle = "检测到地点或餐厅",
                    recommendedActionLabel = ACTION_ROUTE_AND_DEALS,
                    previewSnippet = snippet,
                    recommendedPrompt = "请帮我查询这个地点的通勤路线，并顺便看看附近餐厅或商家是否有团购优惠：$safeContext",
                    detectedAtMs = nowMs,
                    nonIntrusive = true,
                )
            }

            // 3. 长文 / 外文 -> 提炼三点摘要
            if (isLongOrForeignText(text)) {
                return ClipboardPortalSuggestion(
                    id = id,
                    intent = ClipboardIntentKind.LONG_OR_FOREIGN_TEXT,
                    pillTitle = "检测到长文或外文",
                    recommendedActionLabel = ACTION_SUMMARIZE_THREE,
                    previewSnippet = snippet,
                    recommendedPrompt = "请帮我用简体中文提炼以下内容的核心三点摘要（若是外文请先翻译关键信息）：\n$safeContext",
                    detectedAtMs = nowMs,
                    nonIntrusive = true,
                )
            }

            return null
        }

        /**
         * 隐私敏感内容防线：严禁将密码、令牌、私钥、验证码、身份证、银行卡等暴露给主动建议层。
         */
        internal fun isSensitiveOrUnsafe(text: String): Boolean {
            if (PURE_OTP_REGEX.matches(text)) return true
            if (SECRET_PREFIX_REGEX.containsMatchIn(text)) return true
            if (ID_CARD_REGEX.containsMatchIn(text)) return true
            // 仅在纯短文本或包含数字分组且非长文报道时拦截银行卡号
            if (text.length <= 64 && BANK_CARD_REGEX.containsMatchIn(text)) return true

            val lower = text.lowercase()
            if (SENSITIVE_KEYWORDS.any { lower.contains(it) }) return true

            // 独立强密码字符串特征：无空格、非 URL、同时包含字母+数字+特殊字符
            if (!text.startsWith("http", ignoreCase = true) &&
                !TAO_TOKEN_REGEX.containsMatchIn(text) &&
                PASSWORD_TOKEN_REGEX.matches(text)
            ) {
                val hasLetter = text.any { it.isLetter() }
                val hasDigit = text.any { it.isDigit() }
                val hasSymbol = text.any { !it.isLetterOrDigit() }
                if (hasLetter && hasDigit && hasSymbol) return true
            }
            return false
        }

        private fun isEcommerceProduct(text: String): Boolean {
            if (ECOMMERCE_URL_REGEX.containsMatchIn(text)) return true
            if (TAO_TOKEN_REGEX.containsMatchIn(text)) return true
            val hintHits = ECOMMERCE_HINTS.count { text.contains(it, ignoreCase = true) }
            if (hintHits >= 1 && (text.contains("http", ignoreCase = true) || text.length >= 12)) return true
            return false
        }

        private fun isAddressOrRestaurant(text: String): Boolean {
            if (MAP_URL_REGEX.containsMatchIn(text)) return true
            // 避免把长篇资讯误判为地址
            if (text.length > 260) return false
            val hasRegion = ADDRESS_REGION_REGEX.containsMatchIn(text)
            val hasStreet = ADDRESS_STREET_REGEX.containsMatchIn(text)
            if (hasRegion && hasStreet) return true
            val restaurantHits = RESTAURANT_HINTS.count { text.contains(it, ignoreCase = true) }
            if (restaurantHits >= 2) return true
            if (restaurantHits >= 1 && (hasStreet || hasRegion || text.contains("号") || text.contains("店"))) return true
            return false
        }

        private fun isLongOrForeignText(text: String): Boolean {
            if (PURE_URL_ONLY_REGEX.matches(text)) return false
            // 长文本：>= 120 字符，或 >= 80 字符且包含多句标点
            val sentenceBreaks = text.count { it in charArrayOf('。', '！', '？', '.', '!', '?') }
            if (text.length >= 120 || (text.length >= 80 && sentenceBreaks >= 2)) return true

            // 外文段落：含日韩字符 >= 6 个且总长 >= 16，或连续英文词 >= 5 个且总长 >= 28
            val kanaCount = JAPANESE_KANA_REGEX.findAll(text).count()
            val hangulCount = KOREAN_HANGUL_REGEX.findAll(text).count()
            if ((kanaCount >= 6 || hangulCount >= 6) && text.length >= 16) return true

            val englishWords = ENGLISH_WORD_REGEX.findAll(text).count()
            if (englishWords >= 5 && text.length >= 28) return true
            return false
        }

        private fun buildSafeSnippet(text: String): String {
            val singleLine = text.replace(Regex("""\s+"""), " ").trim()
                // 避免预览片断直接露出手机号等长数字串
                .replace(Regex("""\d{7,}"""), "******")
            return if (singleLine.length > MAX_SNIPPET_CHARS) {
                singleLine.take(MAX_SNIPPET_CHARS) + "…"
            } else {
                singleLine
            }
        }

        private fun sha256Short(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(abs(it.toInt()) and 0xff) }
        }
    }
}

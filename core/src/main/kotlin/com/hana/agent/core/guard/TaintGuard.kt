package com.hana.agent.core.guard

import com.hana.agent.core.privacy.PocketPrivacyGuard
import com.hana.agent.core.risk.IrrevAction
import com.hana.agent.core.tool.LinkPolicy
import com.hana.agent.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 隐私数据源类型：用于标记会话在执行过程中接触过的敏感信息源。
 */
enum class TaintSource(val wire: String, val labelZh: String) {
    CLIPBOARD("clipboard", "剪贴板"),
    CONTACTS("contacts", "通讯录"),
    SMS("sms", "短信"),
    NOTIFICATION("notification", "系统通知"),
    SENSITIVE_APP("sensitive_app", "敏感应用"),
}

/**
 * 触发污点流阻断校验的外发类动作类型。
 */
enum class OutboundActionKind(val wire: String, val labelZh: String) {
    SEND_MESSAGE("send_message", "发送消息/内容"),
    SHARE("share", "跨应用分享"),
    OPEN_NON_WHITELIST_URL("open_non_whitelist_url", "打开非白名单 URL"),
    OPEN_WORLD_MCP("open_world_mcp", "开放世界 MCP 工具"),
}

/**
 * 单条污点接触记录，保留触发来源、摘要与时间戳以便确认卡片展示审计依据。
 */
data class TaintRecord(
    val source: TaintSource,
    val detail: String,
    val packageName: String? = null,
    val recordedAtMs: Long = System.currentTimeMillis(),
)

/**
 * 会话维度的数据污点状态追踪器。
 * 一旦当前会话读取过隐私数据源（剪贴板/通讯录/短信/通知/敏感包名），即不可逆地标记污点（除非显式清理会话），
 * 并支持记录用户针对特定外发动作的一次性明确授权令牌。
 */
class SessionTaint(val sessionId: String) {
    private val lock = Any()
    private val recordsList = ArrayList<TaintRecord>()
    private val sourceSet = LinkedHashSet<TaintSource>()
    private val oneShotTokens = LinkedHashSet<String>()
    private val oneShotActionScopes = LinkedHashSet<String>()

    val isTainted: Boolean
        get() = synchronized(lock) { sourceSet.isNotEmpty() }

    val sources: Set<TaintSource>
        get() = synchronized(lock) { sourceSet.toSet() }

    val records: List<TaintRecord>
        get() = synchronized(lock) { recordsList.toList() }

    var lastTaintedAtMs: Long? = null
        private set

    fun markTainted(source: TaintSource, detail: String, packageName: String? = null, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            sourceSet.add(source)
            lastTaintedAtMs = nowMs
            val normalizedDetail = detail.trim().take(MAX_DETAIL_CHARS)
            val duplicate = recordsList.lastOrNull()?.let {
                it.source == source && it.detail == normalizedDetail && it.packageName == packageName
            } == true
            if (!duplicate) {
                if (recordsList.size >= MAX_RECORDS) {
                    recordsList.removeAt(0)
                }
                recordsList.add(TaintRecord(source, normalizedDetail, packageName, nowMs))
            }
        }
    }

    /** 登记一次针对特定外发动作或拦截令牌的用户明确授权（消费后失效）。 */
    fun grantOnce(actionKind: OutboundActionKind, target: String? = null) {
        synchronized(lock) {
            oneShotActionScopes.add(scopeKey(actionKind, target))
        }
    }

    /** 凭拦截结果下发的 [approvalToken] 登记一次性授权。 */
    fun grantToken(approvalToken: String) {
        if (approvalToken.isBlank()) return
        synchronized(lock) {
            oneShotTokens.add(approvalToken)
        }
    }

    internal fun consumeAuthorization(actionKind: OutboundActionKind, target: String?): Boolean = synchronized(lock) {
        val exact = scopeKey(actionKind, target)
        if (oneShotActionScopes.remove(exact)) return true
        val wildcard = scopeKey(actionKind, null)
        if (oneShotActionScopes.remove(wildcard)) return true
        return false
    }

    internal fun consumeToken(approvalToken: String?): Boolean {
        if (approvalToken.isNullOrBlank()) return false
        return synchronized(lock) { oneShotTokens.remove(approvalToken) }
    }

    private fun scopeKey(kind: OutboundActionKind, target: String?): String =
        "${kind.wire}|${target?.trim()?.lowercase().orEmpty()}"

    companion object {
        private const val MAX_RECORDS = 64
        private const val MAX_DETAIL_CHARS = 160
    }
}

/**
 * 命中数据污点外发规则时生成的拦截信号，要求用户明确授权确认后方可执行。
 */
data class TaintBlockResult(
    val sessionId: String,
    val actionKind: OutboundActionKind,
    val toolName: String,
    val target: String?,
    val taintSources: Set<TaintSource>,
    val evidence: List<TaintRecord>,
    val reason: String,
    val approvalToken: String = UUID.randomUUID().toString(),
    val requiresExplicitConfirmation: Boolean = true,
) {
    fun toUserPrompt(): String {
        val sourceLabels = taintSources.joinToString("、") { it.labelZh }
        val targetHint = target?.takeIf { it.isNotBlank() }?.let { "（目标: $it）" }.orEmpty()
        return "当前会话已读取隐私数据（$sourceLabels），即将执行外发操作「${actionKind.labelZh}」$targetHint，需用户明确授权确认后才能继续。"
    }
}

/**
 * 污点守卫检查结论：放行或返回需要用户明确确认的拦截信号 [TaintBlockResult]。
 */
sealed interface TaintCheckOutcome {
    data object Allowed : TaintCheckOutcome
    data class Blocked(val block: TaintBlockResult) : TaintCheckOutcome
}

/**
 * 当调用方选择异常驱动拦截流时抛出的安全拦截异常，携带结构化 [TaintBlockResult]。
 */
class TaintBlockedException(val block: TaintBlockResult) : SecurityException(block.reason)

/**
 * 待检测的外发类动作上下文。
 */
data class OutboundActionRequest(
    val sessionId: String,
    val toolName: String,
    val verb: Verb? = null,
    val args: JsonObject = JsonObject(emptyMap()),
    val currentPackage: String? = null,
    val irrevAction: IrrevAction? = null,
    val isOpenWorldMcp: Boolean = false,
    val targetOverride: String? = null,
    val approvalToken: String? = null,
)

/**
 * 跨会话数据污点动作拦截器 (Taint Guard)
 *
 * 核心安全逻辑：
 * 1. 追踪会话内读取过的隐私数据源（剪贴板、通讯录、短信、通知、敏感应用包名）；
 * 2. 识别外发动作（发送消息、跨应用分享、打开非白名单 URL、调用开放世界 MCP 工具）；
 * 3. 若会话已被污染且无有效一次性授权，立即产生 [TaintBlockResult]（或抛出 [TaintBlockedException]），
 *    防止间接提示词注入或越权动作将敏感隐私数据静默外泄。
 */
class TaintGuard(
    whitelistedDomains: Set<String> = DEFAULT_WHITELIST_DOMAINS,
    whitelistedSchemes: Set<String> = DEFAULT_WHITELIST_SCHEMES,
    whitelistedAppPackages: Set<String> = emptySet(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions = ConcurrentHashMap<String, SessionTaint>()
    private val domainWhitelist = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(whitelistedDomains.map { it.trim().lowercase() }.filter { it.isNotEmpty() })
    }
    private val schemeWhitelist = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(whitelistedSchemes.map { it.trim().lowercase() }.filter { it.isNotEmpty() })
    }
    private val packageWhitelist = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(whitelistedAppPackages.map { it.trim().lowercase() }.filter { it.isNotEmpty() })
    }

    /** 获取或创建指定会话的污点追踪对象。 */
    fun session(sessionId: String): SessionTaint =
        sessions.computeIfAbsent(sessionId) { SessionTaint(it) }

    /** 清理已完成或销毁的会话污点状态。 */
    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    /** 清空全部会话污点状态（通常用于测试复位）。 */
    fun resetAll() {
        sessions.clear()
    }

    fun addWhitelistedDomain(domain: String) {
        val d = domain.trim().lowercase().removePrefix(".")
        if (d.isNotEmpty()) domainWhitelist.add(d)
    }

    fun addWhitelistedScheme(scheme: String) {
        val s = scheme.trim().lowercase().removeSuffix(":")
        if (s.isNotEmpty()) schemeWhitelist.add(s)
    }

    fun addWhitelistedPackage(packageName: String) {
        val p = packageName.trim().lowercase()
        if (p.isNotEmpty()) packageWhitelist.add(p)
    }

    /** 显式记录当前会话读取了某类隐私数据源。 */
    fun recordPrivacyRead(
        sessionId: String,
        source: TaintSource,
        detail: String,
        packageName: String? = null,
    ) {
        session(sessionId).markTainted(source, detail, packageName, clock())
    }

    /**
     * 自动识别一次工具读取/观察行为是否触及隐私数据源：
     * - 剪贴板读取工具或参数；
     * - 通讯录 / 联系人应用包名或 URI；
     * - 短信应用包名或 SMS/MMS 语义；
     * - 系统通知事件 (`phone_event` kind="notification" 等)；
     * - [PocketPrivacyGuard.isSensitivePackage] 覆盖的银行、支付、社交、便签等敏感应用。
     */
    fun observeRead(
        sessionId: String,
        toolName: String,
        args: JsonObject = JsonObject(emptyMap()),
        packageName: String? = null,
        observedText: String? = null,
    ): Set<TaintSource> {
        val detected = detectTaintSources(toolName, args, packageName, observedText)
        if (detected.isEmpty()) return emptySet()
        val taint = session(sessionId)
        val detail = buildString {
            append(toolName)
            packageName?.takeIf { it.isNotBlank() }?.let { append(" @ ").append(it) }
        }
        val now = clock()
        detected.forEach { src -> taint.markTainted(src, detail, packageName, now) }
        return detected
    }

    /** 对外发拦截令牌或动作范围授予一次性明确确认。 */
    fun authorizeOnce(sessionId: String, actionKind: OutboundActionKind, target: String? = null) {
        session(sessionId).grantOnce(actionKind, target)
    }

    /** 使用拦截结果签发的 [approvalToken] 完成确认授权。 */
    fun authorizeByToken(sessionId: String, approvalToken: String) {
        session(sessionId).grantToken(approvalToken)
    }

    /**
     * 评估外发动作请求：若命中污点且无有效明确授权，返回 [TaintCheckOutcome.Blocked]；否则返回 [TaintCheckOutcome.Allowed]。
     */
    fun checkOutbound(request: OutboundActionRequest): TaintCheckOutcome {
        val taint = sessions[request.sessionId] ?: return TaintCheckOutcome.Allowed
        if (!taint.isTainted) return TaintCheckOutcome.Allowed

        val classified = classifyOutbound(request) ?: return TaintCheckOutcome.Allowed
        val (kind, target) = classified

        if (taint.consumeToken(request.approvalToken) || taint.consumeAuthorization(kind, target)) {
            return TaintCheckOutcome.Allowed
        }

        val sources = taint.sources
        val evidence = taint.records.takeLast(5)
        val reason = buildString {
            append("taint_blocked: ")
            append(kind.wire)
            append(" blocked because session ")
            append(request.sessionId)
            append(" read private sources [")
            append(sources.joinToString(",") { it.wire })
            append("]")
            if (!target.isNullOrBlank()) {
                append(" target=").append(target)
            }
        }
        return TaintCheckOutcome.Blocked(
            TaintBlockResult(
                sessionId = request.sessionId,
                actionKind = kind,
                toolName = request.toolName,
                target = target,
                taintSources = sources,
                evidence = evidence,
                reason = reason,
            )
        )
    }

    /**
     * 严格拦截入口：命中污点且未经确认时直接抛出 [TaintBlockedException]（内含 [TaintBlockResult]）。
     */
    @Throws(TaintBlockedException::class)
    fun enforceOrThrow(request: OutboundActionRequest) {
        when (val outcome = checkOutbound(request)) {
            is TaintCheckOutcome.Allowed -> Unit
            is TaintCheckOutcome.Blocked -> throw TaintBlockedException(outcome.block)
        }
    }

    internal fun detectTaintSources(
        toolName: String,
        args: JsonObject,
        packageName: String?,
        observedText: String?,
    ): Set<TaintSource> {
        val found = LinkedHashSet<TaintSource>()
        val toolLower = toolName.lowercase()
        val pkgLower = packageName?.trim()?.lowercase().orEmpty()
        val argsText = args.entries.joinToString(" ") { (k, v) ->
            "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v.toString()}"
        }.lowercase()

        // 1. 剪贴板数据源
        if (CLIPBOARD_TOKENS.any { it in toolLower || it in argsText }) {
            found.add(TaintSource.CLIPBOARD)
        }

        // 2. 通讯录数据源
        if (pkgLower in CONTACT_PACKAGES ||
            CONTACT_TOKENS.any { it in pkgLower || it in toolLower || it in argsText }
        ) {
            found.add(TaintSource.CONTACTS)
        }

        // 3. 短信数据源
        if (pkgLower in SMS_PACKAGES ||
            SMS_TOKENS.any { it in pkgLower || it in toolLower || it in argsText }
        ) {
            found.add(TaintSource.SMS)
        }

        // 4. 通知数据源
        val eventKind = (args["kind"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (eventKind == "notification" ||
            NOTIFICATION_TOKENS.any { it in toolLower || it in argsText } ||
            pkgLower == "com.android.systemui" && observedText?.contains("通知") == true
        ) {
            found.add(TaintSource.NOTIFICATION)
        }

        // 5. 敏感包名（微信/QQ/支付宝/银行/便签等）
        if (!packageName.isNullOrBlank() && PocketPrivacyGuard.isSensitivePackage(packageName)) {
            found.add(TaintSource.SENSITIVE_APP)
        }

        return found
    }

    internal fun classifyOutbound(request: OutboundActionRequest): Pair<OutboundActionKind, String?>? {
        val toolLower = request.toolName.lowercase()
        val args = request.args

        // 1. 跨应用分享
        if (request.verb == Verb.ShareText ||
            request.verb == Verb.ShareFile ||
            toolLower == "phone_share_text" ||
            toolLower == "share_file" ||
            toolLower.contains("share")
        ) {
            val target = request.targetOverride
                ?: argStr(args, "package")
                ?: argStr(args, "target")
                ?: argStr(args, "path")
                ?: request.currentPackage
            return OutboundActionKind.SHARE to target
        }

        // 2. 开放世界 MCP 工具
        if (request.isOpenWorldMcp || (toolLower.startsWith("mcp_") && isOpenWorldMcpArgsOrDefault(request))) {
            val target = request.targetOverride ?: request.toolName
            return OutboundActionKind.OPEN_WORLD_MCP to target
        }

        // 3. 打开非白名单 URL
        val uri = argStr(args, "uri") ?: argStr(args, "url")
        val action = argStr(args, "action")
        val pkg = argStr(args, "package")
        if (request.verb == Verb.OpenLink ||
            request.verb == Verb.Navigate ||
            toolLower == "phone_open_link" ||
            toolLower == "web_navigate" ||
            uri != null
        ) {
            // 系统设置动作属于本地配置页跳转，不视为 URL 外发
            if (uri == null && action != null && action in LinkPolicy.SETTINGS_ACTIONS) {
                return null
            }
            if (uri != null && !isUrlWhitelisted(uri, pkg)) {
                return OutboundActionKind.OPEN_NON_WHITELIST_URL to uri
            }
            return null
        }

        // 4. 发送消息 / 对外提交类动作
        val isExplicitSendVerb = request.verb == Verb.PhoneComment ||
            request.verb == Verb.PhoneSocialInteract
        val isSendToolName = SEND_TOOL_NAMES.any { it in toolLower }
        val isSendIrrev = request.irrevAction == IrrevAction.send || request.irrevAction == IrrevAction.submit
        val isSendPressEnter = request.verb == Verb.TypeText &&
            (args["pressEnter"] as? JsonPrimitive)?.booleanOrNull == true &&
            request.irrevAction == IrrevAction.send

        if (isExplicitSendVerb || isSendToolName || isSendIrrev || isSendPressEnter) {
            val target = request.targetOverride
                ?: request.currentPackage
                ?: argStr(args, "to")
                ?: argStr(args, "recipient")
                ?: argStr(args, "ref")
            return OutboundActionKind.SEND_MESSAGE to target
        }

        return null
    }

    private fun isOpenWorldMcpArgsOrDefault(request: OutboundActionRequest): Boolean {
        // 若显式调用了 MCP 工具且未标明本地只读，按开放世界属性判定（由外部调用方传 isOpenWorldMcp 更精确）
        return request.irrevAction != null || (request.args["openWorld"] as? JsonPrimitive)?.booleanOrNull == true
    }

    /**
     * 判断给定 URL / URI 是否在安全白名单内。
     * - localhost / 127.0.0.1 / ::1 默认允许；
     * - 命中 [domainWhitelist]（精确匹配或子域名匹配）允许；
     * - 非 http(s) 自定义 scheme（如 `intent:`/`https:` 外协议）仅在 [schemeWhitelist] 或目标包在 [packageWhitelist] 时允许。
     */
    fun isUrlWhitelisted(rawUrl: String, targetPackage: String? = null): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return true
        val parsed = runCatching { URI(trimmed) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase() ?: Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(trimmed)?.groupValues?.get(1)?.lowercase()
        if (scheme == null) return false

        if (scheme == "http" || scheme == "https") {
            val host = parsed?.host?.lowercase()?.trimEnd('.') ?: return false
            if (host in LOCAL_HOSTS) return true
            if (domainWhitelist.any { allowed -> host == allowed || host.endsWith(".$allowed") }) {
                return true
            }
            return false
        }

        if (scheme in schemeWhitelist) return true
        val pkgLower = targetPackage?.trim()?.lowercase()
        if (pkgLower != null && pkgLower in packageWhitelist) return true
        return false
    }

    private fun argStr(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1")

        val DEFAULT_WHITELIST_DOMAINS: Set<String> = setOf(
            "localhost",
            "127.0.0.1",
        )

        val DEFAULT_WHITELIST_SCHEMES: Set<String> = setOf(
            "about",
        )

        private val CLIPBOARD_TOKENS = listOf("clipboard", "read_clip", "get_clip", "paste_read", "剪贴板")
        private val CONTACT_TOKENS = listOf("contacts", "contact_list", "addressbook", "通讯录", "联系人")
        private val SMS_TOKENS = listOf("sms", "mms", "inbox", "短信", "收件箱")
        private val NOTIFICATION_TOKENS = listOf("notification", "notifications", "通知栏", "系统通知")
        private val SEND_TOOL_NAMES = listOf("send_message", "send_sms", "send_email", "phone_comment", "phone_social_interact", "reply_message")

        private val CONTACT_PACKAGES = setOf(
            "com.android.contacts",
            "com.google.android.contacts",
            "com.samsung.android.app.contacts",
            "com.miui.contacts",
            "com.huawei.contacts",
            "com.coloros.contacts",
            "com.vivo.contacts",
        )

        private val SMS_PACKAGES = setOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.coloros.mms",
            "com.hihonor.mms",
            "com.miui.mms",
            "com.vivo.mms",
        )
    }
}

package com.hana.agent.proactive

import android.content.Context
import com.hana.agent.core.proactive.DigestItem
import com.hana.agent.core.proactive.DigestRollup
import com.hana.agent.core.proactive.IncomingNotice
import com.hana.agent.core.proactive.NoticeDeduplicator
import com.hana.agent.core.proactive.NoticeTriager
import com.hana.agent.core.proactive.ProactiveBudget
import com.hana.agent.core.proactive.ProactiveDigestStore
import com.hana.agent.core.proactive.TriageResult
import com.hana.agent.settings.FeatureFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId

/**
 * 悬浮胶囊角标轻提示数据。
 *
 * @property id 提示标识符
 * @property packageName 关联应用包名
 * @property title 标题
 * @property previewText 简略预览内容（可能已脱敏）
 * @property timestampMs 时间戳
 * @property triage 分流类型
 */
data class NoticeBadgeHint(
    val id: String,
    val packageName: String,
    val title: String,
    val previewText: String,
    val timestampMs: Long,
    val triage: TriageResult
)

/**
 * 主动能力单例运行时协调器。
 *
 * 核心设计原则：
 * 1. 绝对克制：不擅自弹窗打断前台操作、不擅自语音开口；
 * 2. 严格受控：只有在 [FeatureFlags.proactiveEnabled] 开启且目标应用在白名单中时才执行处理；
 * 3. 预算防刷：严格遵循 [ProactiveBudget] 的日频次上限；
 * 4. 去重保真：经过 [NoticeDeduplicator] 窗口去重；
 * 5. 状态同步：通过 [badgeHints] 以不可变流形式对外通知胶囊小圆点或角标轻提示。
 */
class ProactiveRuntime internal constructor(
    private val context: Context,
    private val prefs: ProactivePrefs = ProactivePrefs.get(context),
    private val flags: FeatureFlags = FeatureFlags.get(context),
) {

    private val budget = ProactiveBudget(maxDailyDecisions = 60, maxDailyTokens = 30_000)
    private val deduplicator = NoticeDeduplicator(maxCacheSize = 200)
    private val triager = NoticeTriager()
    private val digestStore = ProactiveDigestStore(maxItemsPerRollup = 150)
    private val clipboardDetector = ClipboardPortalDetector()

    private val _badgeHints = MutableStateFlow<List<NoticeBadgeHint>>(emptyList())
    val badgeHints: StateFlow<List<NoticeBadgeHint>> = _badgeHints.asStateFlow()

    private val _clipboardSuggestion = MutableStateFlow<ClipboardPortalSuggestion?>(null)
    val clipboardSuggestion: StateFlow<ClipboardPortalSuggestion?> = _clipboardSuggestion.asStateFlow()

    init {
        // 冷启动时自动从 SharedPreferences 恢复已保存的日摘要 JSON（防损坏兜底）
        restoreDigestFromPrefs()
    }

    /**
     * 接收并处理新到达的通知。
     *
     * @param notice 提取后的通知数据结构
     */
    fun onNoticeReceived(notice: IncomingNotice) {
        // 1. 检查特性开关与偏好设置开关
        if (!flags.proactiveEnabled() && !prefs.isProactiveEnabled()) {
            return
        }

        // 2. 检查包名白名单（默认必须显式勾选允许）
        if (!prefs.isPackageAllowed(notice.packageName)) {
            return
        }

        // 3. 去重检查（5 分钟时间窗口去重）
        if (deduplicator.isDuplicate(notice)) {
            return
        }

        // 4. 预算配额检查
        if (!budget.canDecide()) {
            return
        }

        // 5. 规则分流判定
        val triageResult = triager.triage(notice)

        // 6. 根据分流结果进行处理
        when (triageResult) {
            TriageResult.IGNORE -> {
                // 彻底忽略，不消耗实际预算，不打扰
            }

            TriageResult.SILENT_DIGEST -> {
                // 静默沉淀入日摘要
                budget.recordDecision(tokens = 10)
                recordToDigest(notice, triageResult)
            }

            TriageResult.CAPSULE_BADGE_HINT -> {
                // 高优先级事件：消耗预算、存入摘要并点亮胶囊角标
                budget.recordDecision(tokens = 20)
                recordToDigest(notice, triageResult)
                pushBadgeHint(notice, triageResult)
            }
        }
    }

    /**
     * 用户点击或滑开已查看的角标提示。
     */
    fun dismissHint(id: String) {
        val current = _badgeHints.value
        _badgeHints.value = current.filter { it.id != id }
    }

    /**
     * 清空所有当前角标轻提示。
     */
    fun clearHints() {
        _badgeHints.value = emptyList()
    }

    /**
     * 非侵入式检查剪贴板文本，命中安全意图时生成轻提示建议。
     */
    fun onClipboardText(rawText: String?, nowMs: Long = System.currentTimeMillis()): ClipboardPortalSuggestion? {
        if (!flags.proactiveEnabled() && !prefs.isProactiveEnabled()) return null
        val suggestion = clipboardDetector.inspect(rawText, nowMs)
        if (suggestion != null) {
            _clipboardSuggestion.value = suggestion
        }
        return suggestion
    }

    /**
     * 安全检查系统剪贴板并更新非侵入式建议（无权限或敏感文本静默忽略）。
     */
    fun checkSystemClipboard(nowMs: Long = System.currentTimeMillis()): ClipboardPortalSuggestion? {
        if (!flags.proactiveEnabled() && !prefs.isProactiveEnabled()) return null
        val suggestion = clipboardDetector.inspectSystemClipboard(context, nowMs)
        if (suggestion != null) {
            _clipboardSuggestion.value = suggestion
        }
        return suggestion
    }

    /**
     * 忽略当前剪贴板“任意门”轻提示。
     */
    fun dismissClipboardSuggestion() {
        _clipboardSuggestion.value = null
    }

    fun getTodayDigest(today: String = LocalDate.now(ZoneId.systemDefault()).toString()): DigestRollup? =
        digestStore.getRollup(today)

    fun getDigestStore(): ProactiveDigestStore = digestStore

    fun getBudget(): ProactiveBudget = budget

    private fun pushBadgeHint(notice: IncomingNotice, triage: TriageResult) {
        val preview = if (prefs.isMaskSensitiveEnabled()) {
            maskSensitiveText(notice.text)
        } else {
            notice.text
        }

        val hint = NoticeBadgeHint(
            id = notice.id,
            packageName = notice.packageName,
            title = notice.title,
            previewText = preview,
            timestampMs = notice.postTimeMs,
            triage = triage
        )

        val current = _badgeHints.value.toMutableList()
        // 保持最多 5 条最新的轻提示
        current.removeAll { it.id == hint.id }
        current.add(0, hint)
        if (current.size > 5) {
            _badgeHints.value = current.take(5)
        } else {
            _badgeHints.value = current
        }
    }

    private fun recordToDigest(notice: IncomingNotice, triage: TriageResult) {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        val category = when (triage) {
            TriageResult.CAPSULE_BADGE_HINT -> "IMPORTANT"
            else -> "GENERAL"
        }
        val summaryText = if (prefs.isMaskSensitiveEnabled()) {
            maskSensitiveText(notice.text).take(120)
        } else {
            notice.text.take(120)
        }
        val item = DigestItem(
            id = notice.id,
            timestampMs = notice.postTimeMs,
            category = category,
            title = notice.title,
            summary = summaryText,
            sourcePackage = notice.packageName
        )
        digestStore.addItem(today, item)
        persistDigestToPrefs()
    }

    private fun persistDigestToPrefs() {
        runCatching {
            val today = LocalDate.now(ZoneId.systemDefault()).toString()
            digestStore.purgeOlderThan(retentionDays = DIGEST_RETENTION_DAYS, today = today)
            prefs.setDigestJson(digestStore.exportToJson())
        }
    }

    private fun restoreDigestFromPrefs() {
        val saved = prefs.getDigestJson()
        if (saved.isBlank()) return
        runCatching {
            digestStore.loadFromJson(saved)
            val today = LocalDate.now(ZoneId.systemDefault()).toString()
            digestStore.purgeOlderThan(retentionDays = DIGEST_RETENTION_DAYS, today = today)
        }
    }

    private fun maskSensitiveText(text: String): String {
        // 掩码常见手机号、长数字等
        return text.replace(Regex("\\d{6,}"), "******")
    }

    companion object {
        private const val DIGEST_RETENTION_DAYS = 7

        @Volatile
        private var instance: ProactiveRuntime? = null

        fun get(context: Context): ProactiveRuntime = getInstance(context)

        fun getInstance(context: Context): ProactiveRuntime {
            return instance ?: synchronized(this) {
                instance ?: ProactiveRuntime(context.applicationContext).also { instance = it }
            }
        }
    }
}

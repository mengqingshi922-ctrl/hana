package com.hana.agent.core.proactive

import java.util.concurrent.ConcurrentHashMap

/**
 * 接收到的通知数据结构。
 *
 * @property id 通知唯一标识符
 * @property packageName 产生通知的应用包名
 * @property appLabel 应用展示名称
 * @property title 通知标题
 * @property text 通知正文内容
 * @property postTimeMs 通知发出时间戳（毫秒）
 */
data class IncomingNotice(
    val id: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTimeMs: Long = System.currentTimeMillis()
)

/**
 * 通知分流优先级结果。
 */
enum class TriageResult {
    /** 彻底忽略（静默丢弃：广告、营销、验证码、系统噪音通知） */
    IGNORE,

    /** 静默沉淀入日/周摘要池（不弹出、不打扰用户） */
    SILENT_DIGEST,

    /** 胶囊角标轻提示（高优先级重要事件：快递、日程、会议、紧急消息） */
    CAPSULE_BADGE_HINT
}

/**
 * 通知去重器。
 *
 * 维护内存时间窗口去重缓存，防止短时间内相同通知被重复判定与触发。
 */
class NoticeDeduplicator(
    private val maxCacheSize: Int = 300
) {
    private val cache = ConcurrentHashMap<String, Long>()

    /**
     * 判断是否为时间窗口内的重复通知。若不是重复通知，则记录进缓存。
     *
     * @param notice 接收到的通知
     * @param windowMs 去重时间窗口（毫秒，默认 5 分钟）
     * @param nowMs 当前时间戳（毫秒）
     * @return true 表示为重复通知应丢弃；false 表示非重复通知
     */
    fun isDuplicate(
        notice: IncomingNotice,
        windowMs: Long = 300_000L,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        purgeExpired(nowMs, windowMs)

        val key = generateDeduplicateKey(notice)
        val lastSeen = cache[key]
        if (lastSeen != null && (nowMs - lastSeen) < windowMs) {
            return true
        }

        if (cache.size >= maxCacheSize) {
            // 容量超限时简单淘汰最旧的记录
            val oldestKey = cache.entries.minByOrNull { it.value }?.key
            if (oldestKey != null) {
                cache.remove(oldestKey)
            }
        }

        cache[key] = nowMs
        return false
    }

    /**
     * 清空去重缓存。
     */
    fun clear() {
        cache.clear()
    }

    private fun generateDeduplicateKey(notice: IncomingNotice): String {
        return "${notice.packageName}::${notice.title.trim()}::${notice.text.trim()}"
    }

    private fun purgeExpired(nowMs: Long, windowMs: Long) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value > windowMs) {
                iterator.remove()
            }
        }
    }
}

/**
 * 静默优先级分流器。
 *
 * 采用纯规则策略快速分流通知，无端侧神经网络推理开销，零隐私泄漏：
 * 1. 严格屏蔽验证码/动态口令（IGNORE，安全隐私敏感）；
 * 2. 严格屏蔽广告营销推广类通知（IGNORE）；
 * 3. 屏蔽系统层常驻与嘈杂服务通知（IGNORE）；
 * 4. 识别快递取件、日程、会议、即时通讯重要互动（CAPSULE_BADGE_HINT）；
 * 5. 其余常规有价值通知静默沉淀入摘要（SILENT_DIGEST）。
 */
class NoticeTriager(
    private val customIgnoredPackages: Set<String> = emptySet(),
    private val customImportantContacts: Set<String> = emptySet()
) {
    /**
     * 判定一条通知的优先级分流分类。
     */
    fun triage(notice: IncomingNotice): TriageResult {
        val fullContent = "${notice.title} ${notice.text}".trim()

        // 1. 黑名单包名拦截
        if (isIgnoredPackage(notice.packageName)) {
            return TriageResult.IGNORE
        }

        // 2. 验证码 / 短信验证码 / 动态密码（严格隐私拦截，不得打扰亦不得上屏展示）
        if (VERIFICATION_CODE_REGEX.containsMatchIn(fullContent)) {
            return TriageResult.IGNORE
        }

        // 3. 营销推广广告拦截
        if (MARKETING_PROMOTION_REGEX.containsMatchIn(fullContent)) {
            return TriageResult.IGNORE
        }

        // 4. 重要联系人匹配（若命中直接提示）
        if (customImportantContacts.isNotEmpty()) {
            for (contact in customImportantContacts) {
                if (notice.title.contains(contact) || notice.text.contains(contact)) {
                    return TriageResult.CAPSULE_BADGE_HINT
                }
            }
        }

        // 5. 快递取件 / 外卖派送关键事件
        if (EXPRESS_DELIVERY_REGEX.containsMatchIn(fullContent)) {
            return TriageResult.CAPSULE_BADGE_HINT
        }

        // 6. 日程 / 会议 / 待办倒计时
        if (SCHEDULE_MEETING_REGEX.containsMatchIn(fullContent)) {
            return TriageResult.CAPSULE_BADGE_HINT
        }

        // 7. 即时通讯社交与工作消息中含明确呼叫（如 @、语音通话、紧急请回复等）
        if (isChatPackage(notice.packageName) && CHAT_URGENT_REGEX.containsMatchIn(fullContent)) {
            return TriageResult.CAPSULE_BADGE_HINT
        }

        // 8. 默认：常规非垃圾应用通知沉淀为静默日/周摘要
        return TriageResult.SILENT_DIGEST
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        if (customIgnoredPackages.contains(pkg)) return true
        return SYSTEM_NOISE_PACKAGES.contains(pkg) || pkg.startsWith("com.android.providers.downloads")
    }

    private fun isChatPackage(pkg: String): Boolean {
        return COMMON_CHAT_PACKAGES.contains(pkg)
    }

    companion object {
        /** 验证码敏感词匹配 */
        private val VERIFICATION_CODE_REGEX = Regex(
            "(?:验证码|校验码|动态码|安全码|确认码|auth code|verification code|security code)",
            RegexOption.IGNORE_CASE
        )

        /** 广告营销推广关键词匹配 */
        private val MARKETING_PROMOTION_REGEX = Regex(
            "(?:退订|回TD|双11|双12|满\\d+减|立减\\d+|优惠券|领券|特惠|限时秒杀|首充|爆款|免费领取|免单|低至\\d+折|返现|大促|拼单|红包已到账)",
            RegexOption.IGNORE_CASE
        )

        /** 快递与送达提醒关键词匹配 */
        private val EXPRESS_DELIVERY_REGEX = Regex(
            "(?:取件码|快递已送达|丰巢|菜鸟驿站|顺丰速运|京东快递|中通快递|圆通速递|韵达快递|代收点|包裹已到达|凭提取码|已放入自提柜)"
        )

        /** 日程与会议提醒关键词匹配 */
        private val SCHEDULE_MEETING_REGEX = Regex(
            "(?:日程提醒|会议提醒|腾讯会议|飞书会议|Zoom|钉钉会议|即将开始|准时参加|会议室|待办提醒|日历提醒)"
        )

        /** 社交工作紧急呼叫匹配 */
        private val CHAT_URGENT_REGEX = Regex(
            "(?:@了你|有人@我|邀请你进行通话|未接来电|紧急|请查收|发来了一份文件|发来一条重要消息)"
        )

        /** 常见社交与协同包名 */
        private val COMMON_CHAT_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.alibaba.android.rimet",
            "com.ss.android.lark",
            "com.tencent.wework"
        )

        /** 默认屏蔽的系统噪音包 */
        private val SYSTEM_NOISE_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.gms",
            "com.android.vending",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui"
        )
    }
}

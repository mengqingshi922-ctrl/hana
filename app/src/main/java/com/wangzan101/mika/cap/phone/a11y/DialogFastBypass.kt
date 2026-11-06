package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect

/**
 * 屏幕节点简要特征，支持纯 JVM 单元测试与真机无障碍节点无缝桥接。
 */
interface BypassNode {
    val text: String
    val resourceId: String?
    val clickable: Boolean
    val bounds: BypassBounds
}

data class BypassBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Google ARTEMIS 风格通用弹窗与阻断蒙层前置主动穿透 (Barrier Fast-Bypass):
 * 自动识别开屏跳过广告、系统权限弹窗、青少年模式防沉迷、隐私协议确认及活动大弹窗关闭按钮，
 * 在无需大模型推理的情况下本地毫秒级穿透阻断，让大模型直接面对清爽的核心操作界面。
 */
internal object DialogFastBypass {
    data class Candidate<T>(
        val target: T,
        val x: Int,
        val y: Int,
        val reason: String,
        val text: String,
    )

    private val PERMISSION_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.lbe.security.miui",
        "com.miui.securitycenter",
    )

    private val PERMISSION_CONFIRM_WORDS = listOf(
        "仅在使用中允许", "使用应用时允许", "始终允许", "允许", "仅本次允许", "允许本次使用"
    )

    private val SKIP_WORDS = listOf(
        "跳过", "跳过广告", "跳过倒计时", "skip", "Skip", "SKIP"
    )

    private val YOUTH_MODE_KEYWORDS = listOf(
        "青少年模式", "未成年人模式", "防沉迷", "未成年人保护"
    )

    private val PRIVACY_KEYWORDS = listOf(
        "用户协议", "隐私政策", "个人信息保护", "服务协议"
    )

    private val PROMO_DISMISS_WORDS = listOf(
        "我知道了", "我知道啦", "下次再说", "以后再说", "稍后再说", "残忍拒绝", "暂不升级", "暂不更新", "忽略"
    )

    /**
     * 核心纯逻辑判定函数，不依赖任何 Android 系统运行时服务。
     */
    fun <T> findBypassGeneric(
        items: List<Pair<T, BypassNode>>,
        topPackage: String?,
        screenWidth: Int,
        screenHeight: Int,
    ): Candidate<T>? {
        if (items.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return null

        val allTexts = items.map { it.second.text }.filter { it.isNotBlank() }
        val fullText = allTexts.joinToString(" ")

        // 1. 系统权限请求弹窗检测
        val isPermissionUi = (topPackage != null && PERMISSION_PACKAGES.contains(topPackage)) ||
                fullText.contains("要允许") || fullText.contains("申请权限") || fullText.contains("权限申请")
        if (isPermissionUi) {
            for (word in PERMISSION_CONFIRM_WORDS) {
                val match = items.firstOrNull { (_, node) ->
                    val name = node.text.trim()
                    (name == word || name.startsWith(word)) && node.clickable
                }
                if (match != null) {
                    val b = match.second.bounds
                    return Candidate(match.first, b.centerX, b.centerY, "permission_allow", match.second.text)
                }
            }
        }

        // 2. 开屏跳过广告检测 (Splash / Ad Skip)
        for ((item, node) in items) {
            val name = node.text.trim()
            val resId = node.resourceId?.lowercase().orEmpty()
            val isSkipText = SKIP_WORDS.any { name.equals(it, ignoreCase = true) || (name.startsWith(it) && name.length <= 10) }
            val isSkipId = resId.contains("skip") || resId.contains("splash_skip") || resId.contains("tt_splash_skip")

            if ((isSkipText || isSkipId) && node.clickable) {
                val b = node.bounds
                if (b.width in 20..(screenWidth * 0.6).toInt() && b.height in 20..(screenHeight * 0.2).toInt()) {
                    return Candidate(item, b.centerX, b.centerY, "splash_ad_skip", name.ifEmpty { "skip" })
                }
            }
        }

        // 3. 青少年模式强提醒检测
        val isYouthMode = YOUTH_MODE_KEYWORDS.any { fullText.contains(it) }
        if (isYouthMode) {
            val match = items.firstOrNull { (_, node) ->
                val name = node.text.trim()
                (name == "我知道了" || name == "我知道啦" || name == "好的") && node.clickable
            }
            if (match != null) {
                val b = match.second.bounds
                return Candidate(match.first, b.centerX, b.centerY, "youth_mode_dismiss", match.second.text)
            }
        }

        // 4. 用户隐私协议首次进入弹窗
        val isPrivacyUi = PRIVACY_KEYWORDS.any { fullText.contains(it) } && (fullText.contains("同意") || fullText.contains("请阅读"))
        if (isPrivacyUi) {
            val match = items.firstOrNull { (_, node) ->
                val name = node.text.trim()
                (name == "同意并继续" || name == "同意并使用" || name == "同意" || name == "确定并继续") && node.clickable
            }
            if (match != null) {
                val b = match.second.bounds
                return Candidate(match.first, b.centerX, b.centerY, "privacy_agreement_consent", match.second.text)
            }
        }

        // 5. 阻断性更新/活动大弹窗关闭
        for ((item, node) in items) {
            val name = node.text.trim()
            if (PROMO_DISMISS_WORDS.contains(name) && node.clickable) {
                val b = node.bounds
                if (b.width <= screenWidth * 0.8 && b.height <= screenHeight * 0.15) {
                    return Candidate(item, b.centerX, b.centerY, "promo_dismiss", name)
                }
            }
        }

        return null
    }

    /**
     * 针对真机无障碍 NodeEntry 列表的快速查找入口。
     */
    fun findBypass(
        entries: List<NodeEntry>,
        topPackage: String?,
        screenWidth: Int,
        screenHeight: Int,
    ): Candidate<NodeEntry>? {
        val rect = Rect()
        val items = entries.map { entry ->
            entry.node.getBoundsInScreen(rect)
            val bounds = BypassBounds(rect.left, rect.top, rect.right, rect.bottom)
            val node = object : BypassNode {
                override val text: String = entry.element.name
                override val resourceId: String? = entry.node.viewIdResourceName
                override val clickable: Boolean = entry.node.isClickable || entry.element.clickable
                override val bounds: BypassBounds = bounds
            }
            entry to node
        }
        return findBypassGeneric(items, topPackage, screenWidth, screenHeight)
    }
}

package com.hana.agent.core.security

/**
 * 文本输入注入路径枚举：
 * - [SHORTCUT]：优先使用无障碍 `ACTION_SET_TEXT` / 快捷键通道直接提交文本，完全绕过软键盘；
 * - [PASTE_KEY]：通过 `ACTION_PASTE` 或 `Ctrl+V` 注入（配合瞬态剪贴板），不触发系统软键盘弹窗；
 * - [CLIPBOARD]：通过系统剪贴板写入并触发粘贴、随后立即还原/清理剪贴板；
 * - [IME]：常规系统软键盘（IME）输入路径；在 Android 14+ 后台虚拟屏上严禁默认激活；
 * - [HAND_TO_USER]：当目标为受保护的网页密码框等禁止剪贴板/无障碍写入的控件时，安全转交人工接管。
 */
enum class VirtualDisplayInputPath(val wire: String) {
    SHORTCUT("shortcut"),
    PASTE_KEY("paste_key"),
    CLIPBOARD("clipboard"),
    IME("ime"),
    HAND_TO_USER("hand_to_user"),
}

/**
 * 虚拟屏输入判定请求参数。
 */
data class VirtualDisplayImeRequest(
    val sdkInt: Int,
    val displayId: Int,
    val isVirtualDisplay: Boolean = displayId > DEFAULT_DISPLAY_ID,
    val isPasswordField: Boolean = false,
    val inWebView: Boolean = false,
    val hasAccessibilityNode: Boolean = true,
    val supportsShortcutSetText: Boolean = true,
    val supportsPasteKey: Boolean = true,
    val clipboardAvailable: Boolean = true,
    val imeVisibleOnTargetDisplay: Boolean = false,
) {
    companion object {
        const val DEFAULT_DISPLAY_ID = 0
    }
}

/**
 * 输入模式判定结果，指导执行层在 Android 14+ 后台虚拟屏场景下彻底避免主屏 IME 焦点抢占。
 */
data class VirtualDisplayImePlan(
    val guardActive: Boolean,
    val suppressSoftIme: Boolean,
    val avoidTapToFocusImePopup: Boolean,
    val primaryPath: VirtualDisplayInputPath,
    val fallbackPaths: List<VirtualDisplayInputPath>,
    val clearClipboardAfterPaste: Boolean,
    val imeAllowed: Boolean,
    val reason: String,
) {
    /** 合并后的推荐路径优先级序列（主路径 + 备选路径，已过滤不可用选项）。 */
    val orderedPaths: List<VirtualDisplayInputPath>
        get() = listOf(primaryPath) + fallbackPaths
}

/**
 * Android 14+ 后台虚拟屏 IME 焦点防泄漏判定工具 (Virtual Display IME Guard)
 *
 * 背景痛点：
 * 从 Android 14 (API 34) 起，系统收紧了多屏输入法路由策略；当后台虚拟屏 (`displayId > 0`) 上的应用
 * 拉起软键盘（IME）或点击唤醒输入法时，系统常将 IME 窗口及输入焦点泄漏/抢占至物理主屏 (`displayId == 0`)，
 * 严重打断用户在主屏的正常操作并存在输入焦点串屏风险。
 *
 * 核心策略：
 * - 一旦判定为 **Android 14+ (`sdkInt >= 34`) 且运行在后台虚拟屏 (`isVirtualDisplay && displayId > 0`)**：
 *   1. 禁止走常规软键盘 `ime` 路径 (`suppressSoftIme = true`, `imeAllowed = false`)；
 *   2. 避免为了弹软键盘而执行点击抢焦 (`avoidTapToFocusImePopup = true`)；
 *   3. 优先引导走 `shortcut`（无障碍直接设值/快捷键提交）→ `paste_key`（`ACTION_PASTE`/`Ctrl+V`）→ `clipboard`（瞬态剪贴板写入+粘贴后立即还原）路径；
 *   4. 若为网页密码框或无法走无障碍/安全剪贴板注入的特殊密码控件，宁可转交人工 (`hand_to_user`) 也不泄漏主屏键盘焦点或明文剪贴板。
 */
object VirtualDisplayImeGuard {
    const val ANDROID_14_API_LEVEL = 34
    const val DEFAULT_DISPLAY_ID = 0

    /**
     * 判断当前运行环境是否必须启用后台虚拟屏 IME 焦点防泄漏保护。
     */
    fun shouldGuard(sdkInt: Int, displayId: Int, isVirtualDisplay: Boolean = displayId > DEFAULT_DISPLAY_ID): Boolean =
        sdkInt >= ANDROID_14_API_LEVEL && isVirtualDisplay && displayId > DEFAULT_DISPLAY_ID

    /**
     * 根据当前系统版本、屏幕归属、控件类型与可用通道，判定最安全的输入路径优先级计划。
     */
    fun decideInputPlan(request: VirtualDisplayImeRequest): VirtualDisplayImePlan {
        val guardActive = shouldGuard(request.sdkInt, request.displayId, request.isVirtualDisplay)

        if (!guardActive) {
            // 物理主屏或 Android 13 及以下版本：按常规优先级调度，仍优先使用无感 shortcut，但允许 IME 兜底
            val candidates = buildList {
                if (request.hasAccessibilityNode && !request.inWebView && request.supportsShortcutSetText) {
                    add(VirtualDisplayInputPath.SHORTCUT)
                }
                if (!request.isPasswordField && request.supportsPasteKey && request.clipboardAvailable) {
                    add(VirtualDisplayInputPath.PASTE_KEY)
                    add(VirtualDisplayInputPath.CLIPBOARD)
                }
                add(VirtualDisplayInputPath.IME)
            }
            val primary = candidates.first()
            return VirtualDisplayImePlan(
                guardActive = false,
                suppressSoftIme = false,
                avoidTapToFocusImePopup = false,
                primaryPath = primary,
                fallbackPaths = candidates.drop(1),
                clearClipboardAfterPaste = VirtualDisplayInputPath.PASTE_KEY in candidates || VirtualDisplayInputPath.CLIPBOARD in candidates,
                imeAllowed = true,
                reason = if (request.displayId <= DEFAULT_DISPLAY_ID && !request.isVirtualDisplay) {
                    "physical_display_ime_safe"
                } else {
                    "pre_android_14_ime_allowed"
                },
            )
        }

        // Android 14+ 后台虚拟屏场景：严禁拉起物理屏 IME
        if (request.isPasswordField) {
            // 密码框绝不将明文写入全局剪贴板（防止多设备/输入法剪贴板历史泄露）
            return if (request.hasAccessibilityNode && !request.inWebView && request.supportsShortcutSetText) {
                VirtualDisplayImePlan(
                    guardActive = true,
                    suppressSoftIme = true,
                    avoidTapToFocusImePopup = true,
                    primaryPath = VirtualDisplayInputPath.SHORTCUT,
                    fallbackPaths = listOf(VirtualDisplayInputPath.HAND_TO_USER),
                    clearClipboardAfterPaste = false,
                    imeAllowed = false,
                    reason = "android14_virtual_display_password_shortcut_only",
                )
            } else {
                VirtualDisplayImePlan(
                    guardActive = true,
                    suppressSoftIme = true,
                    avoidTapToFocusImePopup = true,
                    primaryPath = VirtualDisplayInputPath.HAND_TO_USER,
                    fallbackPaths = emptyList(),
                    clearClipboardAfterPaste = false,
                    imeAllowed = false,
                    reason = "android14_virtual_display_password_requires_user_takeover",
                )
            }
        }

        val paths = ArrayList<VirtualDisplayInputPath>(3)
        if (request.inWebView || !request.hasAccessibilityNode) {
            // WebView 或无障碍树缺失的自绘控件：优先 paste_key + 瞬态剪贴板，其次 shortcut
            if (request.supportsPasteKey && request.clipboardAvailable) {
                paths += VirtualDisplayInputPath.PASTE_KEY
                paths += VirtualDisplayInputPath.CLIPBOARD
            } else if (request.clipboardAvailable) {
                paths += VirtualDisplayInputPath.CLIPBOARD
            }
            if (request.supportsShortcutSetText && request.hasAccessibilityNode) {
                paths += VirtualDisplayInputPath.SHORTCUT
            }
        } else {
            // 原生无障碍可访问输入框：优先 shortcut (ACTION_SET_TEXT)，其次 paste_key 与剪贴板回退
            if (request.supportsShortcutSetText) {
                paths += VirtualDisplayInputPath.SHORTCUT
            }
            if (request.supportsPasteKey && request.clipboardAvailable) {
                paths += VirtualDisplayInputPath.PASTE_KEY
                paths += VirtualDisplayInputPath.CLIPBOARD
            } else if (request.clipboardAvailable) {
                paths += VirtualDisplayInputPath.CLIPBOARD
            }
        }

        if (paths.isEmpty()) {
            paths += VirtualDisplayInputPath.HAND_TO_USER
        }

        val usesClipboard = VirtualDisplayInputPath.PASTE_KEY in paths || VirtualDisplayInputPath.CLIPBOARD in paths
        return VirtualDisplayImePlan(
            guardActive = true,
            suppressSoftIme = true,
            avoidTapToFocusImePopup = !request.imeVisibleOnTargetDisplay,
            primaryPath = paths.first(),
            fallbackPaths = paths.drop(1),
            clearClipboardAfterPaste = usesClipboard,
            imeAllowed = false,
            reason = "android14_virtual_display_ime_suppressed_prefer_${paths.first().wire}",
        )
    }

    /**
     * 判断给定输入路径在当前环境是否被允许执行。
     * 在 Android 14+ 后台虚拟屏上，`IME` 路径会被明确拒绝以防止物理屏系统键盘抢占焦点。
     */
    fun isPathPermitted(path: VirtualDisplayInputPath, request: VirtualDisplayImeRequest): Boolean {
        if (path == VirtualDisplayInputPath.IME &&
            shouldGuard(request.sdkInt, request.displayId, request.isVirtualDisplay)
        ) {
            return false
        }
        if ((path == VirtualDisplayInputPath.PASTE_KEY || path == VirtualDisplayInputPath.CLIPBOARD) &&
            request.isPasswordField
        ) {
            return false
        }
        return true
    }
}

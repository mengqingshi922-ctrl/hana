package com.hana.agent.core.tool


/**
 * open_link 能打开什么。分发器执行前查一次,外壳服务启动前再查一次。
 * 设置页只认安卓原版公开的 `android.settings.*` 动作;链接拒绝文件与脚本类协议,
 * 网页链接必须指定 App(否则会落到浏览器里)。
 */
object LinkPolicy {
    const val APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"

    val SETTINGS_ACTIONS: Set<String> = setOf(
        "android.settings.SETTINGS", "android.settings.WIFI_SETTINGS", "android.settings.BLUETOOTH_SETTINGS",
        "android.settings.DISPLAY_SETTINGS", "android.settings.SOUND_SETTINGS", "android.settings.BATTERY_SAVER_SETTINGS",
        APP_DETAILS, "android.settings.APPLICATION_SETTINGS", "android.settings.DATE_SETTINGS",
        "android.settings.LOCALE_SETTINGS", "android.settings.DEVICE_INFO_SETTINGS", "android.settings.LOCATION_SOURCE_SETTINGS",
        "android.settings.INTERNAL_STORAGE_SETTINGS", "android.settings.ACCESSIBILITY_SETTINGS",
    )

    val TRUSTED_SCHEMES: Set<String> = setOf(
        "weixin", "alipayqr", "alipays", "amapuri", "baidumap",
        "snssdk1128", "xhsdiscover", "taobao", "openapp.jdmobile",
        "bilibili", "zhihu", "meituanwaimai", "imeituan",
        "qqmusic", "orpheus", "sinaweibo", "snssdk141",
        "eleme", "pinduoduo", "dianping"
    )

    /** 常用中国头部 App 深度快捷直达 DeepLink 路由映射表（直达目标功能页，绕过开屏广告与弹窗） */
    val COMMON_SHORTCUTS: Map<String, String> = mapOf(
        "微信扫一扫" to "weixin://dl/scan",
        "微信收付款" to "weixin://dl/offlinePay",
        "微信朋友圈" to "weixin://dl/moments",
        "支付宝扫一扫" to "alipays://platformapi/startapp?appId=10000007",
        "支付宝收付款" to "alipays://platformapi/startapp?appId=20000056",
        "支付宝乘车码" to "alipays://platformapi/startapp?appId=200011235",
        "高德地图查路线" to "amapuri://route/plan/",
        "高德地图回家" to "amapuri://route/plan/?dlat=&dlon=&dname=家&dev=0&t=0",
        "高德地图去公司" to "amapuri://route/plan/?dlat=&dlon=&dname=公司&dev=0&t=0",
        "淘宝购物车" to "taobao://cart.m.taobao.com/cart.htm",
        "淘宝我的订单" to "taobao://trade.taobao.com/trade/itemlist/list_bought_items.htm",
        "京东我的订单" to "openapp.jdmobile://virtual?params={\"category\":\"jump\",\"des\":\"orderList\"}",
        "小红书搜索" to "xhsdiscover://search/result",
        "知乎热榜" to "zhihu://hot-list",
        "哔哩哔哩动态" to "bilibili://following/home"
    )

    /** 动态参数化 DeepLink 路由模板契约 */
    data class DeepLinkRouteTemplate(
        val routeId: String,
        val targetPackage: String,
        val intentPattern: Regex,
        val uriTemplate: String,
    ) {
        fun resolve(input: String): String? {
            // 1. 标准包名前缀路由与参数填槽：如 com.xingin.xhs:search?keyword=xxx 或 com.xingin.xhs?keyword=xxx
            if (input.startsWith(targetPackage)) {
                val remainder = input.removePrefix(targetPackage).trim()
                if (remainder.isNotEmpty() && !remainder.startsWith(".")) {
                    val query = if (remainder.contains("=")) {
                        remainder.substringAfter("=").trim()
                    } else if (remainder.startsWith("?") || remainder.startsWith(":") || remainder.startsWith("/")) {
                        val stripped = remainder.drop(1).trim()
                        if (stripped.startsWith("search", ignoreCase = true)) {
                            stripped.removePrefix("search").trim().trimStart('?', ':', '=', '/', ' ')
                        } else {
                            stripped
                        }
                    } else ""
                    if (query.isNotEmpty()) {
                        val encoded = runCatching { java.net.URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
                        return uriTemplate.replace("{keyword}", encoded).replace("{query}", encoded)
                    }
                }
            }
            // 2. 意图正则模式匹配
            val match = intentPattern.find(input) ?: return null
            val value = match.groupValues.getOrNull(1)?.trim() ?: return null
            if (value.isEmpty()) return null
            val encoded = runCatching { java.net.URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
            return uriTemplate.replace("{keyword}", encoded).replace("{query}", encoded)
        }
    }

    val ROUTE_TEMPLATES: List<DeepLinkRouteTemplate> = listOf(
        DeepLinkRouteTemplate("xhs_search", "com.xingin.xhs", Regex("(?:小红书搜索|小红书搜)\\s*(.+)"), "xhsdiscover://search/result?keyword={keyword}"),
        DeepLinkRouteTemplate("amap_route", "com.autonavi.minimap", Regex("(?:高德地图导航到|高德导航到|高德地图去|导航去)\\s*(.+)"), "amapuri://route/plan/?dname={query}&dev=0&t=0"),
        DeepLinkRouteTemplate("bili_search", "tv.danmaku.bili", Regex("(?:B站搜索|哔哩哔哩搜索|bilibili搜索)\\s*(.+)"), "bilibili://search?keyword={keyword}"),
        DeepLinkRouteTemplate("taobao_search", "com.taobao.taobao", Regex("(?:淘宝搜索|淘宝搜)\\s*(.+)"), "taobao://search.m.taobao.com/search.htm?q={keyword}"),
        DeepLinkRouteTemplate("jd_search", "com.jingdong.app.mall", Regex("(?:京东搜索|京东搜)\\s*(.+)"), "openapp.jdmobile://virtual?params={\"category\":\"jump\",\"des\":\"search\",\"keyWord\":\"{keyword}\"}"),
        DeepLinkRouteTemplate("zhihu_search", "com.zhihu.android", Regex("(?:知乎搜索|知乎搜)\\s*(.+)"), "zhihu://search?q={keyword}"),
    )

    fun resolveShortcut(name: String): String? {
        val trimmed = name.trim()
        val direct = COMMON_SHORTCUTS[trimmed]
        if (direct != null) return direct

        for (template in ROUTE_TEMPLATES) {
            val matched = template.resolve(trimmed)
            if (matched != null) return matched
        }

        val partial = COMMON_SHORTCUTS.entries.firstOrNull { (k, _) -> trimmed.contains(k) }?.value
        if (partial != null) return partial

        return null
    }

    fun isTrustedAppUri(uri: String): Boolean {
        val s = SCHEME.find(uri)?.groupValues?.get(1)?.lowercase() ?: return false
        return s in TRUSTED_SCHEMES
    }

    private val BLOCKED_SCHEMES = setOf("intent", "file", "content", "javascript")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    /** 安卓包名的形状;分发器检查 package / app 参数也用它。 */
    val PACKAGE = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private const val MAX_URI = 2000

    /** null = 允许;否则是给模型看的错误说明。 */
    fun check(action: String?, uri: String?, pkg: String?): String? {
        if (action == null && uri == null) return "Give uri, or action for an Android settings page."
        if (pkg != null && !PACKAGE.matches(pkg)) return "package must be an Android package name."
        if (uri != null && (uri.length > MAX_URI || uri.any { it.isWhitespace() || it.isISOControl() })) {
            return "uri must be at most $MAX_URI characters without spaces."
        }
        if (action != null) {
            if (action !in SETTINGS_ACTIONS) return "action must be one of the Android settings actions: ${SETTINGS_ACTIONS.joinToString()}."
            if (pkg != null) return "package is not used with action."
            if (action == APP_DETAILS) {
                val target = uri?.takeIf { it.startsWith("package:") }?.removePrefix("package:")
                if (target == null || !PACKAGE.matches(target)) return "$APP_DETAILS needs uri package:<package name>, e.g. package:com.example.app."
            } else if (uri != null) return "Only $APP_DETAILS takes a uri; open other settings pages with action alone."
            return null
        }
        val scheme = SCHEME.find(uri!!)?.groupValues?.get(1)?.lowercase() ?: return "uri must start with a scheme, e.g. https:// or app://."
        if (scheme in BLOCKED_SCHEMES) return "$scheme: links are not supported by phone_open_link."
        if (scheme == "package") return "package: uris are only for action $APP_DETAILS."
        if ((scheme == "http" || scheme == "https") && pkg == null) {
            return "A web link needs package (the app that should open it); otherwise it would open in a browser."
        }
        return null
    }
}

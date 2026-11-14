package com.hana.agent.core.tool

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

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

    /** 动态注册的包名 -> 默认 Scheme 前缀（如 "com.example.app" -> "exampleapp"） */
    val dynamicPackageToDefaultScheme = ConcurrentHashMap<String, String>()

    /** 动态注册的包名 -> 完整可用 Scheme 列表 */
    val dynamicPackageToSchemes = ConcurrentHashMap<String, List<String>>()

    /** 动态发现模板列表 */
    val dynamicRouteTemplates = CopyOnWriteArrayList<DeepLinkRouteTemplate>()

    /** 动态注册的可信自定义 Scheme 集合 */
    @Volatile
    var dynamicTrustedSchemes: Set<String> = emptySet()
        private set

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

    /**
     * 注册运行时动态发现的自定义 Scheme 与包名路由映射。
     * 自动排除 file/content/javascript/intent 等高危 scheme 及 http/https 等通用 scheme。
     */
    fun registerDynamicRoutes(schemes: Set<String>, packageToSchemes: Map<String, List<String>>) {
        val validFromSchemes = schemes
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it !in BLOCKED_SCHEMES && it !in DISALLOWED_DYNAMIC_SCHEMES }
            .toSet()

        val validFromPkgs = mutableSetOf<String>()
        for ((pkg, list) in packageToSchemes) {
            val valid = list
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && it !in BLOCKED_SCHEMES && it !in DISALLOWED_DYNAMIC_SCHEMES }
            if (valid.isNotEmpty()) {
                dynamicPackageToSchemes[pkg] = valid
                dynamicPackageToDefaultScheme[pkg] = valid.first()
                validFromPkgs.addAll(valid)
            }
        }
        dynamicTrustedSchemes = (dynamicTrustedSchemes + validFromSchemes + validFromPkgs).toSet()
    }

    /** 动态注册自定义路由模板 */
    fun registerDynamicRouteTemplate(template: DeepLinkRouteTemplate) {
        dynamicRouteTemplates.add(template)
    }

    /** 清空动态注册的路由与 Scheme（主要用于测试重置或重新发现） */
    fun clearDynamicRoutes() {
        dynamicTrustedSchemes = emptySet()
        dynamicPackageToDefaultScheme.clear()
        dynamicPackageToSchemes.clear()
        dynamicRouteTemplates.clear()
    }

    fun resolveShortcut(name: String): String? {
        val trimmed = name.trim()
        val direct = COMMON_SHORTCUTS[trimmed]
        if (direct != null) return direct

        for (template in ROUTE_TEMPLATES) {
            val matched = template.resolve(trimmed)
            if (matched != null) return matched
        }

        for (template in dynamicRouteTemplates) {
            val matched = template.resolve(trimmed)
            if (matched != null) return matched
        }

        val partial = COMMON_SHORTCUTS.entries.firstOrNull { (k, _) -> trimmed.contains(k) }?.value
        if (partial != null) return partial

        // 未命中时继续检索动态注册的 packageToSchemes（支持传入 <packageName> 或 <packageName>:<path> 自动拼接可信自定义 Scheme DeepLink）
        if (PACKAGE.matches(trimmed)) {
            val scheme = dynamicPackageToDefaultScheme[trimmed]
            if (scheme != null) return "$scheme://"
        } else if (trimmed.contains(':')) {
            val pkgCandidate = trimmed.substringBefore(':').trim()
            if (PACKAGE.matches(pkgCandidate)) {
                val scheme = dynamicPackageToDefaultScheme[pkgCandidate]
                if (scheme != null) {
                    val pathPart = trimmed.substringAfter(':').trim().trimStart('/')
                    return "$scheme://$pathPart"
                }
            }
        } else if (trimmed.contains('/') || trimmed.contains('?')) {
            val delimiter = if (trimmed.contains('/')) '/' else '?'
            val pkgCandidate = trimmed.substringBefore(delimiter).trim()
            if (PACKAGE.matches(pkgCandidate)) {
                val scheme = dynamicPackageToDefaultScheme[pkgCandidate]
                if (scheme != null) {
                    val remainder = trimmed.substring(pkgCandidate.length).trim()
                    val pathPart = remainder.trimStart('/')
                    return if (remainder.startsWith("?")) "$scheme://$remainder" else "$scheme://$pathPart"
                }
            }
        }

        return null
    }

    /** 常见国内头部应用的静态包名与 Scheme 映射，用于 Shortcuts-First 跨端提示与推导 */
    val STATIC_PACKAGE_TO_SCHEMES: Map<String, List<String>> = mapOf(
        "com.tencent.mm" to listOf("weixin"),
        "com.eg.android.AlipayGphone" to listOf("alipays", "alipayqr"),
        "com.autonavi.minimap" to listOf("amapuri"),
        "com.baidu.BaiduMap" to listOf("baidumap"),
        "com.ss.android.ugc.aweme" to listOf("snssdk1128"),
        "com.xingin.xhs" to listOf("xhsdiscover"),
        "com.taobao.taobao" to listOf("taobao"),
        "com.jingdong.app.mall" to listOf("openapp.jdmobile"),
        "tv.danmaku.bili" to listOf("bilibili"),
        "com.zhihu.android" to listOf("zhihu"),
        "com.sankuai.meituan.takeoutnew" to listOf("meituanwaimai"),
        "com.sankuai.meituan" to listOf("imeituan"),
        "com.tencent.qqmusic" to listOf("qqmusic"),
        "com.netease.cloudmusic" to listOf("orpheus"),
        "com.sina.weibo" to listOf("sinaweibo"),
        "com.ss.android.article.news" to listOf("snssdk141"),
        "me.ele" to listOf("eleme"),
        "com.xunmeng.pinduoduo" to listOf("pinduoduo"),
        "com.dianping.v1" to listOf("dianping"),
    )

    /**
     * 查询指定包名下已注册的静态与动态 DeepLink schemes 及快捷直达模板提示。
     * 用于在规划阶段为大模型直接提供当前 App 的所有可用 Shortcuts 候选。
     */
    fun shortcutsHintForPackage(pkg: String): List<String> {
        val hints = linkedSetOf<String>()
        val schemes = linkedSetOf<String>()
        STATIC_PACKAGE_TO_SCHEMES[pkg]?.let { schemes.addAll(it) }
        dynamicPackageToSchemes[pkg]?.let { schemes.addAll(it) }
        dynamicPackageToDefaultScheme[pkg]?.let { schemes.add(it) }

        // 1. 静态快捷方式
        for ((_, uri) in COMMON_SHORTCUTS) {
            val s = SCHEME.find(uri)?.groupValues?.get(1)?.lowercase()
            if (s != null && s in schemes) {
                hints.add(uri)
            }
        }

        // 2. 静态与动态模板的 uriTemplate
        for (template in ROUTE_TEMPLATES) {
            if (template.targetPackage.equals(pkg, ignoreCase = true)) {
                hints.add(template.uriTemplate)
            }
        }
        for (template in dynamicRouteTemplates) {
            if (template.targetPackage.equals(pkg, ignoreCase = true)) {
                hints.add(template.uriTemplate)
            }
        }

        // 3. 动态 schemes 前缀
        for (s in schemes) {
            hints.add("$s://")
        }

        return hints.toList()
    }

    /**
     * 根据用户意图目标与可选包名，按最高优先级自动推导最佳直达 DeepLink URI。
     * 支撑 Shortcuts-First 优先走直达路由跳过层层点击与开屏弹窗。
     */
    fun bestShortcutForIntent(goal: String, packageName: String? = null): String? {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return null

        // 1. 提取包名或目标包关联的 schemes
        val targetSchemes = if (packageName != null) {
            val s = mutableSetOf<String>()
            STATIC_PACKAGE_TO_SCHEMES[packageName]?.let { s.addAll(it) }
            dynamicPackageToSchemes[packageName]?.let { s.addAll(it) }
            dynamicPackageToDefaultScheme[packageName]?.let { s.add(it) }
            s
        } else null

        // 2. 精准匹配静态 COMMON_SHORTCUTS
        val direct = COMMON_SHORTCUTS[trimmed]
        if (direct != null) {
            if (targetSchemes == null) return direct
            val uriScheme = SCHEME.find(direct)?.groupValues?.get(1)?.lowercase()
            if (uriScheme in targetSchemes) return direct
        }

        // 3. 高优先级功能意图关键词匹配（扫一扫、收付款、乘车码、购物车、我的订单、路线等）
        val intentKeywords = listOf(
            listOf("扫一扫", "扫码", "二维码") to listOf("扫一扫"),
            listOf("付款", "收付款", "支付码", "付款码") to listOf("收付款"),
            listOf("乘车码", "公交码", "地铁码") to listOf("乘车码"),
            listOf("购物车") to listOf("购物车"),
            listOf("订单", "我的订单", "已买到") to listOf("我的订单"),
            listOf("朋友圈") to listOf("朋友圈"),
            listOf("热榜") to listOf("热榜"),
            listOf("路线", "查路线", "导航") to listOf("查路线"),
            listOf("回家") to listOf("回家"),
            listOf("去公司") to listOf("去公司"),
            listOf("动态") to listOf("动态"),
        )

        for ((synonyms, targetKeys) in intentKeywords) {
            if (synonyms.any { trimmed.contains(it) }) {
                for (tKey in targetKeys) {
                    val matchedEntry = COMMON_SHORTCUTS.entries.firstOrNull { (k, uri) ->
                        if (!k.contains(tKey)) return@firstOrNull false
                        if (targetSchemes != null) {
                            val uriScheme = SCHEME.find(uri)?.groupValues?.get(1)?.lowercase()
                            uriScheme in targetSchemes
                        } else {
                            val appMention = listOf("微信", "支付宝", "高德", "淘宝", "京东", "小红书", "知乎", "哔哩哔哩", "B站").firstOrNull { trimmed.contains(it) }
                            if (appMention != null) k.contains(appMention) else true
                        }
                    }
                    if (matchedEntry != null) {
                        return matchedEntry.value
                    }
                }
            }
        }

        // 4. 从 ROUTE_TEMPLATES 与 dynamicRouteTemplates 中做关键词与意图填槽匹配
        val allTemplates = ROUTE_TEMPLATES + dynamicRouteTemplates
        for (tpl in allTemplates) {
            if (packageName != null && !tpl.targetPackage.equals(packageName, ignoreCase = true)) {
                continue
            }
            val res = tpl.resolve(trimmed)
            if (res != null) return res

            if (trimmed.contains("搜索") || trimmed.contains("搜")) {
                val query = trimmed
                    .replace(Regex("^(?:请帮我|帮我|在|去|打开)?(?:微信|支付宝|淘宝|京东|小红书|高德地图|B站|哔哩哔哩|知乎)?(?:搜索|搜一下|搜一搜|搜)"), "")
                    .trim()
                    .removePrefix(":")
                    .removePrefix("：")
                    .trim()
                if (query.isNotEmpty()) {
                    val encoded = runCatching { java.net.URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
                    return tpl.uriTemplate.replace("{keyword}", encoded).replace("{query}", encoded)
                }
            }
        }

        // 5. 尝试通用 resolveShortcut
        val resolved = resolveShortcut(trimmed)
        if (resolved != null) return resolved

        if (packageName != null) {
            // 仅当 trimmed 看起来像合法英文路径/查询参数且无中文空白时，才尝试拼接包名前缀路径
            if (!trimmed.any { it.isWhitespace() || it in '\u4e00'..'\u9fff' }) {
                val byPkg = resolveShortcut("$packageName:$trimmed")
                if (byPkg != null) return byPkg
            }
        }

        // 6. 若指定了 packageName 且有默认动态 scheme，兜底返回 scheme 根协议
        if (packageName != null) {
            val defaultScheme = dynamicPackageToDefaultScheme[packageName]
            if (defaultScheme != null) {
                return "$defaultScheme://"
            }
        }

        return null
    }

    fun isTrustedAppUri(uri: String): Boolean {
        val s = SCHEME.find(uri)?.groupValues?.get(1)?.lowercase() ?: return false
        return s in TRUSTED_SCHEMES || s in dynamicTrustedSchemes
    }

    private val BLOCKED_SCHEMES = setOf("intent", "file", "content", "javascript")
    private val DISALLOWED_DYNAMIC_SCHEMES = setOf("http", "https", "package", "android")
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

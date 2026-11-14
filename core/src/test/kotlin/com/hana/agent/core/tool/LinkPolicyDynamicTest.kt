package com.hana.agent.core.tool

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinkPolicyDynamicTest {

    @Before
    fun setUp() {
        LinkPolicy.clearDynamicRoutes()
    }

    @After
    fun tearDown() {
        LinkPolicy.clearDynamicRoutes()
    }

    @Test
    fun registersAndResolvesDynamicSchemes() {
        LinkPolicy.registerDynamicRoutes(
            schemes = setOf("custommall", "specialapp"),
            packageToSchemes = mapOf(
                "com.test.mall" to listOf("custommall", "testmall"),
                "com.test.special" to listOf("specialapp"),
            ),
        )

        // isTrustedAppUri 验证
        assertTrue(LinkPolicy.isTrustedAppUri("custommall://product/123"))
        assertTrue(LinkPolicy.isTrustedAppUri("testmall://home"))
        assertTrue(LinkPolicy.isTrustedAppUri("specialapp://activity?id=1"))
        // 静态可信 scheme 依然保持有效
        assertTrue(LinkPolicy.isTrustedAppUri("weixin://dl/scan"))
        // 未注册 scheme 不受信任
        assertFalse(LinkPolicy.isTrustedAppUri("unknownscheme://main"))

        // resolveShortcut 纯包名自动解析默认 Scheme 前缀
        assertEquals("custommall://", LinkPolicy.resolveShortcut("com.test.mall"))
        assertEquals("specialapp://", LinkPolicy.resolveShortcut("com.test.special"))

        // resolveShortcut 包名:路径拼接
        assertEquals("custommall://product/123", LinkPolicy.resolveShortcut("com.test.mall:product/123"))
        assertEquals("custommall://product/123", LinkPolicy.resolveShortcut("com.test.mall:/product/123"))
        assertEquals("specialapp://query?tab=hot", LinkPolicy.resolveShortcut("com.test.special:query?tab=hot"))
    }

    @Test
    fun strictlyBlocksHighRiskSchemesFromDynamicRegistration() {
        LinkPolicy.registerDynamicRoutes(
            schemes = setOf("file", "content", "javascript", "intent", "http", "https", "validapp"),
            packageToSchemes = mapOf(
                "com.malicious.app" to listOf("file", "javascript", "content", "intent"),
                "com.benign.app" to listOf("validapp"),
            ),
        )

        // 危险 schemes 严禁被纳入可信
        assertFalse(LinkPolicy.isTrustedAppUri("file:///sdcard/passwords.txt"))
        assertFalse(LinkPolicy.isTrustedAppUri("content://contacts/people"))
        assertFalse(LinkPolicy.isTrustedAppUri("javascript:alert(document.cookie)"))
        assertFalse(LinkPolicy.isTrustedAppUri("intent://com.attacker.app#Intent;end"))

        // 恶意包名无法通过包名快捷解析
        assertNull(LinkPolicy.resolveShortcut("com.malicious.app"))
        assertNull(LinkPolicy.resolveShortcut("com.malicious.app:run"))

        // 合法注册的 scheme 正常生效
        assertTrue(LinkPolicy.isTrustedAppUri("validapp://home"))
        assertEquals("validapp://", LinkPolicy.resolveShortcut("com.benign.app"))
    }

    @Test
    fun clearsDynamicRoutesProperly() {
        LinkPolicy.registerDynamicRoutes(
            schemes = setOf("temprun"),
            packageToSchemes = mapOf("com.temp.run" to listOf("temprun")),
        )
        assertTrue(LinkPolicy.isTrustedAppUri("temprun://start"))
        assertEquals("temprun://", LinkPolicy.resolveShortcut("com.temp.run"))

        LinkPolicy.clearDynamicRoutes()

        assertFalse(LinkPolicy.isTrustedAppUri("temprun://start"))
        assertNull(LinkPolicy.resolveShortcut("com.temp.run"))
        // 静态依然正常
        assertTrue(LinkPolicy.isTrustedAppUri("weixin://dl/scan"))
        assertEquals("weixin://dl/scan", LinkPolicy.resolveShortcut("微信扫一扫"))
    }

    @Test
    fun testShortcutsHintForPackageStaticAndDynamic() {
        // 静态包提示：微信
        val wxHints = LinkPolicy.shortcutsHintForPackage("com.tencent.mm")
        assertTrue(wxHints.contains("weixin://dl/scan"))
        assertTrue(wxHints.contains("weixin://dl/offlinePay"))
        assertTrue(wxHints.contains("weixin://"))

        // 静态包提示：小红书
        val xhsHints = LinkPolicy.shortcutsHintForPackage("com.xingin.xhs")
        assertTrue(xhsHints.contains("xhsdiscover://search/result"))
        assertTrue(xhsHints.contains("xhsdiscover://search/result?keyword={keyword}"))

        // 动态注册包提示
        LinkPolicy.registerDynamicRoutes(
            schemes = setOf("dynamicshop"),
            packageToSchemes = mapOf("com.dynamic.shop" to listOf("dynamicshop")),
        )
        LinkPolicy.registerDynamicRouteTemplate(
            LinkPolicy.DeepLinkRouteTemplate(
                routeId = "dyn_search",
                targetPackage = "com.dynamic.shop",
                intentPattern = Regex("逛店搜索\\s*(.+)"),
                uriTemplate = "dynamicshop://search?q={keyword}",
            )
        )

        val dynHints = LinkPolicy.shortcutsHintForPackage("com.dynamic.shop")
        assertTrue(dynHints.contains("dynamicshop://"))
        assertTrue(dynHints.contains("dynamicshop://search?q={keyword}"))
    }

    @Test
    fun testBestShortcutForIntentKeywordAndPackage() {
        // 1. 无包名时的明确意图与包名关联
        assertEquals("weixin://dl/scan", LinkPolicy.bestShortcutForIntent("微信扫一扫"))
        assertEquals("taobao://cart.m.taobao.com/cart.htm", LinkPolicy.bestShortcutForIntent("打开淘宝购物车"))
        assertEquals("alipays://platformapi/startapp?appId=10000007", LinkPolicy.bestShortcutForIntent("支付宝扫码"))

        // 2. 带包名时的关键词推导
        assertEquals(
            "taobao://cart.m.taobao.com/cart.htm",
            LinkPolicy.bestShortcutForIntent("购物车", "com.taobao.taobao")
        )
        assertEquals(
            "amapuri://route/plan/",
            LinkPolicy.bestShortcutForIntent("查路线", "com.autonavi.minimap")
        )

        // 3. 模板意图填槽
        val xhsSearch = LinkPolicy.bestShortcutForIntent("小红书搜索 拿铁咖啡")
        assertNotNull(xhsSearch)
        assertTrue(xhsSearch!!.contains("keyword="))

        val tbSearch = LinkPolicy.bestShortcutForIntent("搜索 无线蓝牙耳机", "com.taobao.taobao")
        assertNotNull(tbSearch)
        assertTrue(tbSearch!!.contains("taobao://search.m.taobao.com"))

        // 4. 动态注册包的兜底推导
        LinkPolicy.registerDynamicRoutes(
            schemes = setOf("testapp"),
            packageToSchemes = mapOf("com.test.testapp" to listOf("testapp")),
        )
        val dynResult = LinkPolicy.bestShortcutForIntent("未定义意图", "com.test.testapp")
        assertEquals("testapp://", dynResult)
    }

    @Test
    fun testBestShortcutCleansCommaBeforeSearchKeyword() {
        val uri = LinkPolicy.bestShortcutForIntent("打开小红书，搜索减脂期高蛋白快手晚餐食谱")
        assertNotNull(uri)
        val decoded = java.net.URLDecoder.decode(uri!!, "UTF-8")
        assertEquals("xhsdiscover://search/result?keyword=减脂期高蛋白快手晚餐食谱", decoded)
    }
}

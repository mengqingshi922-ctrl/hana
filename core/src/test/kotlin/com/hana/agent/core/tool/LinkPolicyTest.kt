package com.hana.agent.core.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {

    @Test
    fun testCommonStaticShortcuts() {
        assertEquals("weixin://dl/scan", LinkPolicy.resolveShortcut("微信扫一扫"))
        assertEquals("alipays://platformapi/startapp?appId=10000007", LinkPolicy.resolveShortcut("支付宝扫一扫"))
        assertEquals("taobao://cart.m.taobao.com/cart.htm", LinkPolicy.resolveShortcut("淘宝购物车"))
        assertEquals("amapuri://route/plan/", LinkPolicy.resolveShortcut("高德地图查路线"))
    }

    @Test
    fun testDynamicIntentRegexRouting() {
        // 小红书搜索
        val xhs = LinkPolicy.resolveShortcut("小红书搜索 咖啡教程")
        assertNotNull(xhs)
        assertTrue(xhs!!.startsWith("xhsdiscover://search/result?keyword="))
        assertTrue(xhs.contains("%E5%92%96%E5%95%A1"))

        // 高德导航
        val amap = LinkPolicy.resolveShortcut("高德地图导航到 北京南站")
        assertNotNull(amap)
        assertTrue(amap!!.startsWith("amapuri://route/plan/?dname="))
        assertTrue(amap.contains("dev=0&t=0"))

        // B站搜索
        val bili = LinkPolicy.resolveShortcut("哔哩哔哩搜索 动画")
        assertNotNull(bili)
        assertTrue(bili!!.startsWith("bilibili://search?keyword="))

        // 淘宝搜索
        val tb = LinkPolicy.resolveShortcut("淘宝搜索 手机壳")
        assertNotNull(tb)
        assertTrue(tb!!.startsWith("taobao://search.m.taobao.com/search.htm?q="))

        // 京东搜索
        val jd = LinkPolicy.resolveShortcut("京东搜索 显卡")
        assertNotNull(jd)
        assertTrue(jd!!.contains("openapp.jdmobile"))
        assertTrue(jd.contains("search"))
    }

    @Test
    fun testStandardPackageNameDynamicInterpolation() {
        // 标准 Android 包名 + 查询参数填槽
        val xhsByPkg = LinkPolicy.resolveShortcut("com.xingin.xhs:search?keyword=猫咪")
        assertNotNull(xhsByPkg)
        assertTrue(xhsByPkg!!.startsWith("xhsdiscover://search/result?keyword="))

        val amapByPkg = LinkPolicy.resolveShortcut("com.autonavi.minimap?dname=故宫")
        assertNotNull(amapByPkg)
        assertTrue(amapByPkg!!.startsWith("amapuri://route/plan/?dname="))

        val biliByPkg = LinkPolicy.resolveShortcut("tv.danmaku.bili/search?keyword=鬼畜")
        assertNotNull(biliByPkg)
        assertTrue(biliByPkg!!.startsWith("bilibili://search?keyword="))

        // 无匹配包名
        assertNull(LinkPolicy.resolveShortcut("com.unknown.app:action?q=test"))

        // 关键防御：标准包名与具体 Activity 绝不能被劫持为搜索深链
        assertNull(LinkPolicy.resolveShortcut("com.xingin.xhs"))
        assertNull(LinkPolicy.resolveShortcut("com.xingin.xhs.SplashActivity"))
        assertNull(LinkPolicy.resolveShortcut("com.autonavi.minimap.activity.NewMapActivity"))
        assertNull(LinkPolicy.resolveShortcut("tv.danmaku.bili.ui.splash.SplashActivity"))
    }

    @Test
    fun testTrustedAppUri() {
        assertTrue(LinkPolicy.isTrustedAppUri("weixin://dl/scan"))
        assertTrue(LinkPolicy.isTrustedAppUri("alipays://platformapi/startapp"))
        assertTrue(LinkPolicy.isTrustedAppUri("xhsdiscover://search/result"))
        assertTrue(LinkPolicy.isTrustedAppUri("amapuri://route/plan/"))
    }
}

package com.hana.agent.core.tool

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

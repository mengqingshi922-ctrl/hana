package com.hana.agent.cap.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DialogFastBypassTest {

    private fun fakeItem(
        id: String,
        text: String,
        clickable: Boolean = true,
        bounds: BypassBounds = BypassBounds(100, 100, 300, 200),
        resId: String? = null,
    ): Pair<String, BypassNode> {
        val node = object : BypassNode {
            override val text = text
            override val resourceId = resId
            override val clickable = clickable
            override val bounds = bounds
        }
        return id to node
    }

    @Test
    fun matchesSystemPermissionAllow() {
        val items = listOf(
            fakeItem("title", "是否允许 小红书 访问您的设备位置信息？", clickable = false),
            fakeItem("btn_deny", "拒绝", clickable = true, bounds = BypassBounds(100, 500, 400, 600)),
            fakeItem("btn_allow", "仅在使用中允许", clickable = true, bounds = BypassBounds(500, 500, 900, 600)),
        )

        val c = DialogFastBypass.findBypassGeneric(
            items,
            topPackage = "com.android.permissioncontroller",
            screenWidth = 1080,
            screenHeight = 2400,
        )

        assertNotNull(c)
        assertEquals("btn_allow", c!!.target)
        assertEquals("permission_allow", c.reason)
        assertEquals(700, c.x)
        assertEquals(550, c.y)
    }

    @Test
    fun matchesSplashAdSkipButton() {
        val items = listOf(
            fakeItem("bg", "精彩内容即将呈现", clickable = false),
            fakeItem("skip_btn", "跳过 5s", clickable = true, bounds = BypassBounds(850, 150, 1000, 220), resId = "com.app:id/skip_view"),
        )

        val c = DialogFastBypass.findBypassGeneric(
            items,
            topPackage = "com.some.app",
            screenWidth = 1080,
            screenHeight = 2400,
        )

        assertNotNull(c)
        assertEquals("skip_btn", c!!.target)
        assertEquals("splash_ad_skip", c.reason)
        assertEquals(925, c.x)
        assertEquals(185, c.y)
    }

    @Test
    fun matchesYouthModeDialog() {
        val items = listOf(
            fakeItem("title", "青少年模式提醒", clickable = false),
            fakeItem("content", "为了保护未成年人健康成长，已开启防沉迷保护。", clickable = false),
            fakeItem("btn_ok", "我知道了", clickable = true, bounds = BypassBounds(300, 1200, 780, 1320)),
        )

        val c = DialogFastBypass.findBypassGeneric(
            items,
            topPackage = "com.xingin.xhs",
            screenWidth = 1080,
            screenHeight = 2400,
        )

        assertNotNull(c)
        assertEquals("btn_ok", c!!.target)
        assertEquals("youth_mode_dismiss", c.reason)
    }

    @Test
    fun matchesPrivacyAgreement() {
        val items = listOf(
            fakeItem("title", "服务协议与隐私政策", clickable = false),
            fakeItem("content", "请阅读并同意我们的用户协议与个人信息保护政策...", clickable = false),
            fakeItem("btn_agree", "同意并继续", clickable = true, bounds = BypassBounds(200, 1400, 880, 1520)),
        )

        val c = DialogFastBypass.findBypassGeneric(
            items,
            topPackage = "com.luna.music",
            screenWidth = 1080,
            screenHeight = 2400,
        )

        assertNotNull(c)
        assertEquals("btn_agree", c!!.target)
        assertEquals("privacy_agreement_consent", c.reason)
    }

    @Test
    fun ignoresNormalBusinessUi() {
        val items = listOf(
            fakeItem("chat_title", "微信 (3)", clickable = false),
            fakeItem("message_1", "今天晚上吃什么？", clickable = true),
            fakeItem("send_btn", "发送", clickable = true, bounds = BypassBounds(800, 2100, 1000, 2200)),
        )

        val c = DialogFastBypass.findBypassGeneric(
            items,
            topPackage = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 2400,
        )

        assertNull("正常业务页面绝不应该误触穿透", c)
    }
}

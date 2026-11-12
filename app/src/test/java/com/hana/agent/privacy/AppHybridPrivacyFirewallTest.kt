package com.hana.agent.privacy

import com.hana.agent.core.bus.RegisterBus
import com.hana.agent.core.security.MaskStyle
import com.hana.agent.core.security.SensitiveCategory
import com.hana.agent.core.uistate.UiElement
import com.hana.agent.core.uistate.VisibleTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppHybridPrivacyFirewallTest {

    @Before
    fun setUp() {
        RegisterBus.clear()
    }

    @Test
    fun textSanitizationAndRegisterResolutionWorkThroughAppFacade() {
        val raw = "验证码: 591824，收件人张三，手机号13912345678"
        val res = HybridPrivacyFirewall.sanitizeText(raw, packageName = "com.tencent.mm")

        assertEquals(3, res.replacedCount)
        assertFalse(res.sanitizedText.contains("591824"))
        assertFalse(res.sanitizedText.contains("13912345678"))
        assertEquals(raw, RegisterBus.resolve(res.sanitizedText))
    }

    @Test
    fun redactPixelBufferMasksPasswordOtpAndCardRegionsBeforeUpload() {
        val w = 120
        val h = 120
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }

        // 在验证码区域 [100, 100, 300, 200] (像素 x=12..36, y=12..24) 绘制黑底白字交叉条纹
        for (y in 12..24) {
            for (x in 12..36) {
                pixels[y * w + x] = if ((x + y) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        // 在密码框区域 [100, 500, 600, 600] (像素 x=12..72, y=60..72) 放置明文颜色
        for (y in 60..72) {
            for (x in 12..72) {
                pixels[y * w + x] = 0xFF1E40AF.toInt()
            }
        }

        val ocrLines = listOf(
            VisibleTextLine("验证码 918273", listOf(100, 100, 300, 200), 0.99f),
            VisibleTextLine("普通提示文案", listOf(100, 800, 400, 860), 0.98f),
        )
        val uiElements = listOf(
            UiElement(
                ref = "@e1",
                role = "EditText",
                name = "secret123",
                centerX = 350,
                centerY = 550,
                bounds = listOf(100, 500, 600, 600),
                resourceId = "pwd",
                clickable = true,
                editable = true,
                scrollable = false,
                secret = true,
            )
        )

        val (masked, regions) = HybridPrivacyFirewall.redactPixelBuffer(
            pixels = pixels,
            width = w,
            height = h,
            ocrLines = ocrLines,
            uiElements = uiElements,
            packageName = "com.eg.android.AlipayGphone",
            blockSize = 10,
        )

        assertEquals(2, regions.size)
        assertTrue(regions.any { it.category == SensitiveCategory.VERIFY_CODE && it.style == MaskStyle.PIXELATE_BLUR })
        assertTrue(regions.any { it.category == SensitiveCategory.PASSWORD && it.style == MaskStyle.SOLID_MASK })

        // 验证码区像素被马赛克均值模糊，不再是纯黑/纯白
        val otpCenter = masked[18 * w + 24]
        assertNotEquals(0xFF000000.toInt(), otpCenter)
        assertNotEquals(0xFFFFFFFF.toInt(), otpCenter)

        // 密码框区像素被深色遮罩覆盖
        assertNotEquals(0xFF1E40AF.toInt(), masked[66 * w + 40])

        // 非敏感区域像素保持不变
        assertEquals(0xFFFFFFFF.toInt(), masked[100 * w + 30])
    }
}

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

    @Test
    fun sensitivePackageDetectionRecognizesFinancialAndCommunicationApps() {
        // 主流金融、支付、短信与即时通讯包名应被识别为敏感
        assertTrue(HybridPrivacyFirewall.isSensitivePackage("com.eg.android.AlipayGphone"))
        assertTrue(HybridPrivacyFirewall.isSensitivePackage("com.tencent.mm"))
        assertTrue(HybridPrivacyFirewall.isSensitivePackage("com.tencent.mobileqq"))
        assertTrue(HybridPrivacyFirewall.isSensitivePackage("com.icbc.mobilebank"))
        assertTrue(HybridPrivacyFirewall.isSensitivePackage("com.unionpay"))
        assertTrue(HybridPrivacyFirewall.isSensitivePackage("com.android.mms"))
        assertTrue(HybridPrivacyFirewall.isSensitivePackage("com.miui.notes"))

        // 系统设置与普通应用不属于隐私敏感包
        assertFalse(HybridPrivacyFirewall.isSensitivePackage("com.android.settings"))
        assertFalse(HybridPrivacyFirewall.isSensitivePackage("com.android.chrome"))
        assertFalse(HybridPrivacyFirewall.isSensitivePackage(null))
        assertFalse(HybridPrivacyFirewall.isSensitivePackage(""))
    }

    @Test
    fun pureSelfDrawnScreenWithoutUiElementsPlansOcrRedactionsPrecisely() {
        // 模拟 Flutter/自绘 Canvas/纯 WebView 界面：无障碍树为空 (uiElements = emptyList())
        val ocrLines = listOf(
            VisibleTextLine("收银台快捷支付", listOf(100, 80, 400, 140), 0.95f),
            VisibleTextLine("短信验证码 748291 有效期5分钟", listOf(100, 200, 500, 260), 0.99f),
            VisibleTextLine("付款卡号 6222021000123456789", listOf(100, 320, 650, 380), 0.99f),
            VisibleTextLine("支付密码: 889900", listOf(100, 440, 400, 500), 0.99f),
            VisibleTextLine("确认支付", listOf(200, 750, 600, 820), 0.98f),
        )

        val regions = HybridPrivacyFirewall.planVisualRedactions(
            ocrLines = ocrLines,
            uiElements = emptyList(),
            packageName = "com.eg.android.AlipayGphone",
        )

        // 仅验证码、银行卡号、密码被打码，收银台标题与确认支付按钮保留
        assertEquals(3, regions.size)
        val categories = regions.map { it.category }.toSet()
        assertTrue(SensitiveCategory.VERIFY_CODE in categories)
        assertTrue(SensitiveCategory.BANK_CARD in categories)
        assertTrue(SensitiveCategory.PASSWORD in categories)

        val otpRegion = regions.first { it.category == SensitiveCategory.VERIFY_CODE }
        assertEquals(MaskStyle.PIXELATE_BLUR, otpRegion.style)

        val cardRegion = regions.first { it.category == SensitiveCategory.BANK_CARD }
        assertEquals(MaskStyle.PIXELATE_BLUR, cardRegion.style)

        val pwdRegion = regions.first { it.category == SensitiveCategory.PASSWORD }
        assertEquals(MaskStyle.SOLID_MASK, pwdRegion.style)
    }

    @Test
    fun pureSelfDrawnScreenPixelRedactionEndToEndBlursSensitiveDataAndPreservesCanvas() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }

        // 在验证码坐标 [100, 200, 400, 300] (对应像素 x=10..40, y=20..30) 绘制高对比条纹
        for (y in 20..30) {
            for (x in 10..40) {
                pixels[y * w + x] = if ((x + y) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        // 在密码框坐标 [100, 600, 400, 700] (对应像素 x=10..40, y=60..70) 放置明文特征色
        for (y in 60..70) {
            for (x in 10..40) {
                pixels[y * w + x] = 0xFF0055AA.toInt()
            }
        }
        // 在非敏感的确认按钮区域 (像素 x=80..90, y=80..90) 放置原图关键像素
        val buttonColor = 0xFFEEDDCC.toInt()
        for (y in 80..90) {
            for (x in 80..90) {
                pixels[y * w + x] = buttonColor
            }
        }

        val ocrLines = listOf(
            VisibleTextLine("验证码 829104", listOf(100, 200, 400, 300), 0.99f),
            VisibleTextLine("登录密码 654321", listOf(100, 600, 400, 700), 0.99f),
            VisibleTextLine("确认登录", listOf(800, 800, 900, 900), 0.95f),
        )

        val (masked, regions) = HybridPrivacyFirewall.redactPixelBuffer(
            pixels = pixels,
            width = w,
            height = h,
            ocrLines = ocrLines,
            uiElements = emptyList(), // 纯自绘场景无 UI 元素
            packageName = "com.icbc.mobilebank",
            blockSize = 8,
        )

        assertEquals(2, regions.size)

        // 验证码区被马赛克模糊，黑白交替像素被均值化
        val otpCenter = masked[25 * w + 25]
        assertNotEquals(0xFF000000.toInt(), otpCenter)
        assertNotEquals(0xFFFFFFFF.toInt(), otpCenter)

        // 密码区域被实体深色遮罩覆盖
        assertNotEquals(0xFF0055AA.toInt(), masked[65 * w + 25])

        // 按钮及非敏感区域像素保持完全不变
        assertEquals(buttonColor, masked[85 * w + 85])
        assertEquals(0xFFFFFFFF.toInt(), masked[5 * w + 5])
    }

    @Test
    fun nonSensitivePackageInSelfDrawnModeStillRedactsOcrSensitiveData() {
        // 未知自绘/第三方 WebView 应用（非预置敏感包名），uiElements 为空时通过 OCR 兜底识别
        val ocrLines = listOf(
            VisibleTextLine("您的验证码为 678912", listOf(100, 200, 400, 280), 0.99f),
            VisibleTextLine("卡号 6214830112345678", listOf(100, 350, 600, 420), 0.99f),
        )

        val regions = HybridPrivacyFirewall.planVisualRedactions(
            ocrLines = ocrLines,
            uiElements = emptyList(),
            packageName = "com.example.custompay",
        )

        assertEquals(2, regions.size)
        assertTrue(regions.any { it.category == SensitiveCategory.VERIFY_CODE })
        assertTrue(regions.any { it.category == SensitiveCategory.BANK_CARD })
    }
}

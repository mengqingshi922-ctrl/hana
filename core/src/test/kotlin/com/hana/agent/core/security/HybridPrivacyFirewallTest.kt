package com.hana.agent.core.security

import com.hana.agent.core.bus.RegisterBus
import com.hana.agent.core.uistate.UiElement
import com.hana.agent.core.uistate.VisibleTextLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HybridPrivacyFirewallTest {

    @Before
    fun setUp() {
        RegisterBus.clear()
        HybridPrivacyFirewall.reset()
        HybridPrivacyFirewall.setContextRecognizer(null)
    }

    @After
    fun tearDown() {
        HybridPrivacyFirewall.setContextRecognizer(null)
    }

    @Test
    fun sanitizesRegexAndContextualEntitiesAndRestoresViaRegisterBus() {
        val raw = "请转账给李雷，他的银行卡 6222 0212 3456 7890 123，动态数字验证码 839201，登录密码: Hana@2026，API密钥 sk-test1234567890abcdef"
        val res = HybridPrivacyFirewall.sanitizeText(raw, packageName = "com.tencent.mm")

        assertTrue(res.replacedCount >= 4)
        assertFalse(res.sanitizedText.contains("李雷"))
        assertFalse(res.sanitizedText.contains("6222 0212 3456 7890 123"))
        assertFalse(res.sanitizedText.contains("839201"))
        assertFalse(res.sanitizedText.contains("Hana@2026"))
        assertFalse(res.sanitizedText.contains("sk-test1234567890abcdef"))
        assertTrue(SensitiveCategory.PASSWORD in res.detectedCategories)
        assertTrue(SensitiveCategory.VERIFY_CODE in res.detectedCategories)
        assertTrue(SensitiveCategory.BANK_CARD in res.detectedCategories)
        assertTrue(SensitiveCategory.SECRET_TOKEN in res.detectedCategories)

        // 端侧 RegisterBus 无感还原明文
        assertEquals(raw, RegisterBus.resolve(res.sanitizedText))
    }

    @Test
    fun pluggableContextEntityRecognizerExtendsDetection() {
        HybridPrivacyFirewall.setContextRecognizer { text, _ ->
            val idx = text.indexOf("机密代号Alpha")
            if (idx >= 0) {
                listOf(ContextEntityRecognizer.Span(idx, idx + "机密代号Alpha".length, SensitiveCategory.SECRET_TOKEN, 0.96f))
            } else {
                emptyList()
            }
        }
        val raw = "内部项目 机密代号Alpha 明天上线"
        val res = HybridPrivacyFirewall.sanitizeText(raw, packageName = "com.eg.android.AlipayGphone")
        assertEquals(1, res.replacedCount)
        assertFalse(res.sanitizedText.contains("机密代号Alpha"))
        assertEquals(raw, RegisterBus.resolve(res.sanitizedText))
    }

    @Test
    fun sanitizeScreenJsonProtectsSecretElements() {
        val json = """{"elements":[{"ref":"@e1","name":"MySecretPass99","secret":true},{"ref":"@e2","name":"确认","secret":false}]}"""
        val sanitized = HybridPrivacyFirewall.sanitizeScreenJson(json, packageName = "com.example.normal")
        assertFalse(sanitized.contains("MySecretPass99"))
        assertTrue(sanitized.contains("<密码:${RegisterBus.PREFIX}"))
        assertTrue(sanitized.contains("确认"))
    }

    @Test
    fun plansVisualRedactionsFromOcrBoxesIncludingCrossLineContext() {
        val ocrLines = listOf(
            VisibleTextLine("请输入短信验证码", listOf(100, 120, 420, 160), 0.98f),
            VisibleTextLine("749201", listOf(100, 175, 280, 225), 0.99f),
            VisibleTextLine("银行卡号 6222 0212 3456 7890 123", listOf(100, 300, 720, 350), 0.97f),
            VisibleTextLine("欢迎使用 Hana", listOf(100, 50, 400, 90), 0.99f),
        )
        val secretElements = listOf(
            UiElement(
                ref = "@e5",
                role = "EditText",
                name = "••••••",
                centerX = 300,
                centerY = 450,
                bounds = listOf(100, 420, 700, 480),
                resourceId = "password",
                clickable = true,
                editable = true,
                scrollable = false,
                secret = true,
            )
        )

        val regions = HybridPrivacyFirewall.planVisualRedactions(
            ocrLines = ocrLines,
            uiElements = secretElements,
            packageName = "com.icbc.mobilebank",
        )

        val categories = regions.map { it.category }.toSet()
        assertTrue(SensitiveCategory.VERIFY_CODE in categories)
        assertTrue(SensitiveCategory.BANK_CARD in categories)
        assertTrue(SensitiveCategory.PASSWORD in categories)
        // 普通标题 "欢迎使用 Hana" 不应被打码
        assertEquals(3, regions.size)
        assertEquals(MaskStyle.SOLID_MASK, regions.first { it.category == SensitiveCategory.PASSWORD }.style)
        assertEquals(MaskStyle.PIXELATE_BLUR, regions.first { it.category == SensitiveCategory.VERIFY_CODE }.style)
    }

    @Test
    fun redactPixelsBlursSensitiveBoxAndPreservesOutsidePixels() {
        val w = 100
        val h = 100
        val bg = 0xFFFFFFFF.toInt()
        val pixels = IntArray(w * h) { bg }

        // 在 [200, 200, 400, 400] (即像素 20..40) 区域写入黑白高对比条纹模拟文字笔划
        for (y in 20..40) {
            for (x in 20..40) {
                pixels[y * w + x] = if (x % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        // 区域外放一个参照色像素
        pixels[5 * w + 5] = 0xFF123456.toInt()

        val blurRegion = RedactionRegion(
            bounds = listOf(200, 200, 400, 400),
            category = SensitiveCategory.VERIFY_CODE,
            style = MaskStyle.PIXELATE_BLUR,
        )
        val masked = HybridPrivacyFirewall.redactPixels(pixels, w, h, listOf(blurRegion), blockSize = 8)

        // 外部像素必须保持原样
        assertEquals(0xFF123456.toInt(), masked[5 * w + 5])
        assertEquals(bg, masked[90 * w + 90])

        // 内部像素被块均值马赛克模糊，黑白交替被均值为灰阶，且同一 8x8 块内像素一致
        val p0 = masked[20 * w + 20]
        val p1 = masked[20 * w + 21]
        assertNotEquals(0xFF000000.toInt(), p0)
        assertNotEquals(0xFFFFFFFF.toInt(), p0)
        assertEquals("同一马赛克块内相邻像素应呈均一均值", p0, p1)

        // 再测试 SOLID_MASK 实体遮罩
        val solidRegion = blurRegion.copy(style = MaskStyle.SOLID_MASK)
        val solidMasked = HybridPrivacyFirewall.redactPixels(pixels, w, h, listOf(solidRegion), maskColor = 0xFF111827.toInt())
        assertEquals(0xFF111827.toInt(), solidMasked[30 * w + 30])
        assertEquals(0xFF123456.toInt(), solidMasked[5 * w + 5])
    }
}

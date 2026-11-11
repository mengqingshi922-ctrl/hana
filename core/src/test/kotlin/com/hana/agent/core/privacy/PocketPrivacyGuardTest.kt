package com.hana.agent.core.privacy

import com.hana.agent.core.bus.RegisterBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PocketPrivacyGuardTest {

    @Before
    fun setUp() {
        RegisterBus.clear()
        PocketPrivacyGuard.reset()
    }

    @Test
    fun identifiesSensitivePackagesAccurately() {
        assertTrue(PocketPrivacyGuard.isSensitivePackage("com.tencent.mm"))
        assertTrue(PocketPrivacyGuard.isSensitivePackage("com.eg.android.AlipayGphone"))
        assertTrue(PocketPrivacyGuard.isSensitivePackage("com.android.mms"))
        assertTrue(PocketPrivacyGuard.isSensitivePackage("com.miui.notes"))
        assertTrue(PocketPrivacyGuard.isSensitivePackage("com.icbc.mobilebank"))
        assertTrue(PocketPrivacyGuard.isSensitivePackage("com.bankofchina.app"))

        assertFalse(PocketPrivacyGuard.isSensitivePackage("com.xingin.xhs"))
        assertFalse(PocketPrivacyGuard.isSensitivePackage("com.ss.android.ugc.aweme"))
        assertFalse(PocketPrivacyGuard.isSensitivePackage(null))
        assertFalse(PocketPrivacyGuard.isSensitivePackage(""))
    }

    @Test
    fun sanitizesEntitiesAndIntegratesWithRegisterBus() {
        val raw = "请帮我给张三转账500元，他的手机号是13812345678，身份证是110101199003072345，银行卡号是6222021234567890123，地址是北京市海淀区中关村南大街1号院3号楼102室，验证码是849201"
        val result = PocketPrivacyGuard.sanitize(raw, packageName = "com.tencent.mm")

        assertTrue("脱敏替换数应大于 0", result.replacedCount > 0)
        assertFalse("原手机号不应以明文存在", result.sanitizedText.contains("13812345678"))
        assertFalse("原身份证不应以明文存在", result.sanitizedText.contains("110101199003072345"))
        assertFalse("原银行卡不应以明文存在", result.sanitizedText.contains("6222021234567890123"))
        assertFalse("原验证码不应以明文存在", result.sanitizedText.contains("849201"))

        assertTrue("应包含手机号寄存器占位符", result.sanitizedText.contains("<手机号:${RegisterBus.PREFIX}priv_"))
        assertTrue("应包含身份证寄存器占位符", result.sanitizedText.contains("<身份证:${RegisterBus.PREFIX}priv_"))

        // 验证 RegisterBus 能够正确存储和解析
        val resolved = RegisterBus.resolve(result.sanitizedText)
        assertTrue(resolved.contains("13812345678"))
        assertTrue(resolved.contains("110101199003072345"))
        assertTrue(resolved.contains("6222021234567890123"))
        assertTrue(resolved.contains("849201"))
    }

    @Test
    fun ignoresNonSensitivePackagesUnlessForced() {
        val raw = "我的手机号是13900001111"
        val result = PocketPrivacyGuard.sanitize(raw, packageName = "com.xingin.xhs")
        assertEquals(raw, result.sanitizedText)
        assertEquals(0, result.replacedCount)

        val forced = PocketPrivacyGuard.sanitize(raw, packageName = "com.xingin.xhs", force = true)
        assertTrue(forced.sanitizedText.contains(RegisterBus.PREFIX))
        assertEquals(1, forced.replacedCount)
    }

    @Test
    fun sanitizesScreenJsonCorrectly() {
        val json = """
            {
                "screen": {
                    "elements": [
                        {"ref": "@e1", "name": "联系人: 李四 13788889999", "role": "TextView"},
                        {"ref": "@e2", "name": "确定", "role": "Button"}
                    ]
                }
            }
        """.trimIndent()

        val sanitized = PocketPrivacyGuard.sanitizeScreenJson(json, packageName = "com.tencent.mm")
        assertFalse(sanitized.contains("13788889999"))
        assertTrue(sanitized.contains(RegisterBus.PREFIX))
        assertTrue(sanitized.contains("@e1"))
        assertTrue(sanitized.contains("确定"))
    }

    @Test
    fun preventsPlaceholderCorruptionWithSubsequentDigits() {
        val raw = "手机号是13800138000，实付1元"
        val result = PocketPrivacyGuard.sanitize(raw, packageName = "com.tencent.mm")

        // 绝不能出现占位符被后继数字正则破坏 (如 priv_<金额:$reg:priv_2>)
        assertFalse("占位符内部不应发生二次替换破坏", result.sanitizedText.contains("priv_<金额"))
        assertTrue("应包含规范的手机号占位符", result.sanitizedText.contains("<手机号:${RegisterBus.PREFIX}priv_1>"))
        assertTrue("应包含规范的金额占位符", result.sanitizedText.contains("<金额:${RegisterBus.PREFIX}priv_2>"))

        // 验证完整还原
        val resolved = RegisterBus.resolve(result.sanitizedText)
        assertEquals("手机号是13800138000，实付1元", resolved)
    }

    @Test
    fun deduplicatesIdenticalEntityAndReusesRegister() {
        val raw = "李雷: 13800138000\n韩梅梅: 好的，我记下了 13800138000"
        val result = PocketPrivacyGuard.sanitize(raw, packageName = "com.tencent.mm")

        assertEquals(2, result.replacedCount)
        assertEquals(1, result.maskedEntities.size) // 复用同一个寄存器键
        val count = Regex(Regex.escape("<手机号:${RegisterBus.PREFIX}priv_1>")).findAll(result.sanitizedText).count()
        assertEquals(2, count)

        val resolved = RegisterBus.resolve(result.sanitizedText)
        assertEquals(raw, resolved)
    }

    @Test
    fun resolvesTaggedPlaceholdersDirectlyInRegisterBus() {
        RegisterBus.put("priv_test", "13800138000")
        assertEquals("13800138000", RegisterBus.resolve("<手机号:${RegisterBus.PREFIX}priv_test>"))
        assertEquals("向13800138000转账", RegisterBus.resolve("向<手机号:${RegisterBus.PREFIX}priv_test>转账"))
        assertEquals("13800138000", RegisterBus.resolve("${RegisterBus.PREFIX}priv_test"))
    }
}

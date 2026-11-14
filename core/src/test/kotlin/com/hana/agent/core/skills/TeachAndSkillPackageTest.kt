package com.hana.agent.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 示范录制 TeachRecorder 与标准技能包 HanaSkillPackage 单元测试。
 */
class TeachAndSkillPackageTest {

    @Test
    fun testTeachRecorderRedactionAndDraftGeneration() {
        val recorder = TeachRecorder()

        // 模拟用户示范操作流
        recorder.recordStep(
            packageName = "com.eg.android.AlipayGphone",
            actionType = "click",
            targetLabel = "转账"
        )
        recorder.recordStep(
            packageName = "com.eg.android.AlipayGphone",
            actionType = "input",
            targetLabel = "对方账号",
            inputSample = "13812345678" // 敏感手机号
        )
        recorder.recordStep(
            packageName = "com.eg.android.AlipayGphone",
            actionType = "input",
            targetLabel = "转账金额",
            inputSample = "500元" // 敏感金额
        )
        recorder.recordStep(
            packageName = "com.eg.android.AlipayGphone",
            actionType = "click",
            targetLabel = "确认转账"
        )

        val draft = recorder.finishAndBuildDraft(
            suggestedName = "alipay-transfer-sample",
            display = "支付宝转账示范"
        )

        assertEquals("alipay-transfer-sample", draft.suggestedName)
        assertEquals(4, draft.stepCount)
        assertEquals(listOf("com.eg.android.AlipayGphone"), draft.appsUsed)

        // 验证敏感信息已自动脱敏替换为 <参数>，不残留明文
        assertFalse("草稿中严禁残留手机号码", draft.markdownBody.contains("13812345678"))
        assertFalse("草稿中严禁残留金额数值", draft.markdownBody.contains("500元"))
        assertTrue("脱敏后应包含参数占位符", draft.markdownBody.contains(SkillRedactor.PLACEHOLDER))

        // 验证元数据头部包含标准标记
        assertTrue(draft.markdownBody.contains("learned_from: teach_recording"))
        assertTrue(draft.markdownBody.contains("source: user"))
    }

    @Test
    fun testHanaSkillPackageIntegrityAndTrialImport() {
        val rawMarkdown = """
            ---
            name: order-tracking
            display: 物流追踪技能
            apps:
              - com.cainiao.wireless
            ---
            
            ## 步骤
            1. 打开菜鸟
            2. 点击取件码 882910 并支付 15元
        """.trimIndent()

        val pkg = HanaSkillPackage.create(
            id = "pkg.community.tracking",
            name = "order-tracking",
            display = "物流追踪技能",
            author = "tester",
            apps = listOf("com.cainiao.wireless"),
            markdownBody = rawMarkdown
        )

        // 1. 验证完整性签名
        assertTrue("原始生成的技能包完整性校验应通过", pkg.verifyIntegrity())

        // 2. 篡改内容后完整性校验应失败
        val tamperedPkg = pkg.copy(markdownBody = rawMarkdown + "\n恶意附加代码")
        assertFalse("正文篡改后完整性校验必须失败", tamperedPkg.verifyIntegrity())

        // 3. 导入转换为本地个人技能
        val trialSkill = pkg.toTrialSkill()
        assertNotNull(trialSkill)

        // 核心安全规则：外部导入技能必须强制置为 trial 试用状态
        assertEquals(SkillStatus.trial, trialSkill.status)
        assertEquals("package:pkg.community.tracking", trialSkill.learnedFrom)
        assertEquals(HanaSkillPackage.SOURCE_PACKAGE_IMPORT, trialSkill.source)

        // 核心安全规则：导入时必须经过 SkillRedactor 清洗
        assertFalse("导入清洗后不得残留数字凭证", trialSkill.markdown.contains("882910"))
        assertFalse("导入清洗后不得残留金额", trialSkill.markdown.contains("15元"))
        assertTrue("导入清洗后应替换为占位符", trialSkill.markdown.contains(SkillRedactor.PLACEHOLDER))
    }

    @Test
    fun testHanaSkillPackageJsonSerialization() {
        val original = HanaSkillPackage.create(
            id = "pkg.weather",
            name = "check-weather",
            display = "查看天气",
            apps = listOf("com.huawei.android.totemweather"),
            markdownBody = "# 天气技能正文"
        )

        val jsonStr = original.toJson()
        val parsed = HanaSkillPackage.fromJson(jsonStr)

        assertEquals(original.id, parsed.id)
        assertEquals(original.name, parsed.name)
        assertEquals(original.sha256, parsed.sha256)
        assertTrue(parsed.verifyIntegrity())
    }
}

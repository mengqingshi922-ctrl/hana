package com.hana.agent.capsule

import android.content.ContextWrapper
import com.hana.agent.core.agent.TaskState
import com.hana.agent.core.gate.ConfirmationRequest
import com.hana.agent.core.gate.RiskTier
import com.hana.agent.core.proactive.IncomingNotice
import com.hana.agent.core.risk.IrrevAction
import com.hana.agent.i18n.ChineseTexts
import com.hana.agent.proactive.ClipboardIntentKind
import com.hana.agent.proactive.ClipboardPortalDetector
import com.hana.agent.proactive.ProactivePrefs
import com.hana.agent.proactive.ProactiveRuntime
import com.hana.agent.settings.FeatureFlags
import com.hana.agent.settings.FeatureFlagsCapsuleTest
import com.hana.agent.ui.Exchange
import com.hana.agent.ui.ExchangeStatus
import com.hana.agent.ui.components.mascot.StreamEmotionBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 验证三级风险门禁模型、三要素账单解析、接管态隐私护盾联动、
 * ProactiveRuntime 持久化冷启动恢复以及剪贴板“任意门”意图侦测。
 */
class RiskTierAndProactiveCapsuleTest {
    @get:Rule
    val texts = ChineseTexts()

    private class DummyContext : ContextWrapper(null)

    private fun card(
        key: String,
        state: TaskState,
        kind: CapsuleKind = CapsuleKind.NeedConfirm,
        exchange: Exchange? = null,
    ) = CardRun(
        CapsuleRun(key, "c-$key", state, goal = "目标$key", exchange = exchange, onScreen = true),
        kind,
        "",
    )

    @Test
    fun confirmCardPreservesBackwardCompatibilityAndPropagatesRiskTierAndUntrustedFlag() {
        // 1. 原有三参数构造保留默认值
        val legacy = CardContent.Confirm("确认下单", "商品: 牙膏", 101)
        assertEquals(RiskTier.L3_HARD_SLIDE, legacy.riskTier)
        assertFalse(legacy.fromUntrustedSource)

        // 2. L2 软确认 + 外部防注入标志经 CapsuleCards.content 透传（含 Exchange 与无 Exchange 两种路径）
        val req = ConfirmationRequest(
            action = IrrevAction.send,
            target = "项目大群",
            content = "今晚按时发版",
            amount = null,
            app = "com.tencent.mm",
            riskTier = RiskTier.L2_SOFT_COUNTDOWN,
            fromUntrustedSource = true,
        )
        val waiting = TaskState.WaitingConfirmation(req)
        val rawConfirm = CapsuleCards.content(card("k1", waiting)) as CardContent.Confirm
        assertEquals(RiskTier.L2_SOFT_COUNTDOWN, rawConfirm.riskTier)
        assertTrue(rawConfirm.fromUntrustedSource)

        val ex = Exchange(
            goal = "发群消息",
            steps = emptyList(),
            status = ExchangeStatus.NeedsConfirmation("要你同意:发送", "在 微信\n对象:项目大群\n内容:今晚按时发版", 88),
            live = true,
        )
        val exConfirm = CapsuleCards.content(card("k2", waiting, exchange = ex)) as CardContent.Confirm
        assertEquals(88, exConfirm.key)
        assertEquals(RiskTier.L2_SOFT_COUNTDOWN, exConfirm.riskTier)
        assertTrue(exConfirm.fromUntrustedSource)
    }

    @Test
    fun parseConfirmBillElementsExtractsThreeElementsFromMultipleFormats() {
        // 格式 A：ConfirmationCopy 转义后的比例冒号 `∶` 与换行分隔
        val billA = CapsuleCards.parseConfirmBillElements(
            title = "要你同意:转账",
            body = "在 支付宝\n对象∶张三\n内容∶AA聚餐转账\n金额∶¥128.50",
            riskTier = RiskTier.L3_HARD_SLIDE,
        )
        assertEquals("支付宝", billA.appName)
        assertEquals("张三", billA.target)
        assertEquals("AA聚餐转账", billA.content)
        assertEquals("¥128.50", billA.impact)

        // 格式 B：中点 ` · ` 分隔的 L2 轻确认消息
        val billB = CapsuleCards.parseConfirmBillElements(
            title = "要你同意:发送",
            body = "在 微信 · 对象:李四 · 内容:明天上午九点会议室见",
            riskTier = RiskTier.L2_SOFT_COUNTDOWN,
        )
        assertEquals("李四", billB.target)
        assertEquals("明天上午九点会议室见", billB.content)
        assertTrue(billB.impact.contains("4 秒内可撤回"))

        // 格式 C：自由文本兜底提取金额
        val billC = CapsuleCards.parseConfirmBillElements(
            title = "支付确认",
            body = "即将向星巴克门店付款 38.00 元",
            riskTier = RiskTier.L3_HARD_SLIDE,
        )
        assertEquals("38.00 元", billC.impact)
        assertTrue(billC.target.isNotBlank())
        assertTrue(billC.content.isNotBlank())
    }

    @Test
    fun privacyShieldActivatesOnlyInTakeoverStatesAndSyncsWithStreamEmotionBus() {
        assertTrue(CapsuleCards.shouldActivatePrivacyShield(CapsuleKind.NeedTakeover))
        assertTrue(CapsuleCards.shouldActivatePrivacyShield(CapsuleKind.HandedOver))
        assertFalse(CapsuleCards.shouldActivatePrivacyShield(CapsuleKind.Running))
        assertFalse(CapsuleCards.shouldActivatePrivacyShield(CapsuleKind.NeedConfirm))
        assertFalse(CapsuleCards.shouldActivatePrivacyShield(CapsuleKind.Idle))
        assertFalse(CapsuleCards.shouldActivatePrivacyShield(null))

        StreamEmotionBus.setPrivacyShield(true)
        assertTrue(StreamEmotionBus.privacyShieldActive.value)
        StreamEmotionBus.setPrivacyShield(false)
        assertFalse(StreamEmotionBus.privacyShieldActive.value)
    }

    @Test
    fun proactiveRuntimePersistsDigestAndRestoresOnColdStart() {
        val prefsStorage = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flagsStorage = FeatureFlagsCapsuleTest.MemoryPrefs()
        val proactivePrefs = ProactivePrefs(prefsStorage)
        val featureFlags = FeatureFlags(flagsStorage)
        featureFlags.setProactiveEnabled(true)
        proactivePrefs.setProactiveEnabled(true)
        proactivePrefs.addAllowedPackage("com.eg.android.AlipayGphone")

        val runtime1 = ProactiveRuntime(DummyContext(), proactivePrefs, featureFlags)
        val notice = IncomingNotice(
            id = "notice-pickup-001",
            packageName = "com.eg.android.AlipayGphone",
            appLabel = "支付宝",
            title = "菜鸟驿站取件码",
            text = "您的快递取件码为 12-3-4567，请凭码前往南门驿站领取，联系电话 13812345678。",
            postTimeMs = System.currentTimeMillis(),
        )
        runtime1.onNoticeReceived(notice)

        // 1. 验证胶囊轻提示角标生成且长数字已脱敏
        val hints = runtime1.badgeHints.value
        assertEquals(1, hints.size)
        assertEquals("notice-pickup-001", hints.first().id)
        assertFalse("长手机号应脱敏掩码", hints.first().previewText.contains("13812345678"))

        // 2. 验证本地 JSON 已写入 prefs
        val savedJson = proactivePrefs.getDigestJson()
        assertTrue(savedJson.isNotBlank())

        // 3. 模拟冷启动：使用同一底层 SharedPreferences 重建新实例，验证今日摘要自动恢复
        val runtime2 = ProactiveRuntime(DummyContext(), ProactivePrefs(prefsStorage), FeatureFlags(flagsStorage))
        val restoredToday = runtime2.getTodayDigest()
        assertNotNull("冷启动后今日摘要必须自动恢复", restoredToday)
        assertEquals(1, restoredToday!!.items.size)
        assertEquals("notice-pickup-001", restoredToday.items.first().id)

        // 4. 一键忽略角标轻提示
        runtime1.dismissHint("notice-pickup-001")
        assertTrue(runtime1.badgeHints.value.isEmpty())
    }

    @Test
    fun clipboardPortalDetectorRecognizesIntentsAndBlocksSensitiveSecrets() {
        val detector = ClipboardPortalDetector(dedupeWindowMs = 60_000L)

        // 1. 电商链接与淘口令 -> 后台三平台比价
        val jd = detector.inspect("https://item.jd.com/100012345678.html", nowMs = 1_000L)
        assertNotNull(jd)
        assertEquals(ClipboardIntentKind.ECOMMERCE_PRODUCT, jd!!.intent)
        assertEquals(ClipboardPortalDetector.ACTION_COMPARE_PRICES, jd.recommendedActionLabel)
        assertTrue(jd.nonIntrusive)

        // 去重窗口内重复内容不再弹窗/提示
        assertNull(detector.inspect("https://item.jd.com/100012345678.html", nowMs = 2_000L))

        val taoToken = ClipboardPortalDetector.detect("【淘宝】fu置这行话 ￥AbCd1234Ef￥ 打开手机淘宝立享到手价")
        assertNotNull(taoToken)
        assertEquals(ClipboardIntentKind.ECOMMERCE_PRODUCT, taoToken!!.intent)
        assertEquals(ClipboardPortalDetector.ACTION_COMPARE_PRICES, taoToken.recommendedActionLabel)

        // 2. 地址与餐厅 -> 查路线与团购
        val addr = ClipboardPortalDetector.detect("上海市静安区南京西路1601号越洋广场")
        assertNotNull(addr)
        assertEquals(ClipboardIntentKind.ADDRESS_OR_RESTAURANT, addr!!.intent)
        assertEquals(ClipboardPortalDetector.ACTION_ROUTE_AND_DEALS, addr.recommendedActionLabel)

        val restaurant = ClipboardPortalDetector.detect("老北京铜锅涮肉(三里屯店) 营业时间 11:00-23:00 人均￥135")
        assertNotNull(restaurant)
        assertEquals(ClipboardIntentKind.ADDRESS_OR_RESTAURANT, restaurant!!.intent)

        // 3. 长文与外文 -> 提炼三点摘要
        val longChinese = "人工智能正在重塑移动操作系统的交互范式。过去用户需要在多个应用之间频繁跳转完成比价、导航与日程编排，而如今具备视觉理解与端侧隐私护盾的个人智能体能够在后台静默协同完成繁琐步骤，只在关键风险节点请求人类确认，从而兼顾效率与安全体验。"
        val longSuggestion = ClipboardPortalDetector.detect(longChinese)
        assertNotNull(longSuggestion)
        assertEquals(ClipboardIntentKind.LONG_OR_FOREIGN_TEXT, longSuggestion!!.intent)
        assertEquals(ClipboardPortalDetector.ACTION_SUMMARIZE_THREE, longSuggestion.recommendedActionLabel)

        val foreignText = ClipboardPortalDetector.detect("Mobile GUI agents enable autonomous task execution across everyday smartphone apps.")
        assertNotNull(foreignText)
        assertEquals(ClipboardIntentKind.LONG_OR_FOREIGN_TEXT, foreignText!!.intent)
        assertEquals(ClipboardPortalDetector.ACTION_SUMMARIZE_THREE, foreignText.recommendedActionLabel)

        // 4. 敏感信息绝对拦截：API Key / JWT / 密码 / OTP / 私钥 / 身份证
        val unsafeSamples = listOf(
            "sk-proj-1234567890abcdef1234567890",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.signature12345",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...",
            "我的wifi密码: Hana@2026!",
            "P@ssw0rd!9xK",
            "482910",
            "11010119900101123X",
        )
        for (secret in unsafeSamples) {
            assertNull("敏感文本绝不允许触发剪贴板任意门建议: $secret", ClipboardPortalDetector.detect(secret))
        }
    }

    @Test
    fun proactiveRuntimeRespectsToggleForClipboardSuggestions() {
        val prefsStorage = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flagsStorage = FeatureFlagsCapsuleTest.MemoryPrefs()
        val proactivePrefs = ProactivePrefs(prefsStorage)
        val featureFlags = FeatureFlags(flagsStorage)
        val runtime = ProactiveRuntime(DummyContext(), proactivePrefs, featureFlags)

        // 开关默认关闭时绝不产生剪贴板建议
        assertNull(runtime.onClipboardText("https://item.jd.com/100012345678.html"))
        assertNull(runtime.clipboardSuggestion.value)

        // 开启开关后安全生成非侵入建议，并支持一键忽略
        featureFlags.setProactiveEnabled(true)
        val hit = runtime.onClipboardText("https://item.jd.com/100012345678.html")
        assertNotNull(hit)
        assertEquals(hit, runtime.clipboardSuggestion.value)

        runtime.dismissClipboardSuggestion()
        assertNull(runtime.clipboardSuggestion.value)
    }
}

package com.hana.agent.core.agent

import com.hana.agent.core.observation.Place
import com.hana.agent.core.tool.placeOf
import com.hana.agent.core.model.Capabilities
import com.hana.agent.core.model.ChatModel
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.ModelReply
import com.hana.agent.core.model.ToolSpec
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hana.agent.core.testing.BATCHABLE
import com.hana.agent.core.testing.verb
import com.hana.agent.core.testing.SANDBOX_TOOLS

private class NamedModel(override val name: String, toolCalls: Boolean = true, vision: Boolean = false) : ChatModel {
    override val capabilities = Capabilities(toolCalls = toolCalls, vision = vision, contextTokens = 64_000, reasoning = false)
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("not called")
}

class ModelRouterTest {
    private val main = NamedModel("main")
    private val light = NamedModel("light")
    private fun clean(step: Int) = StepContext(step, lastOk = true, lastHadGuard = false, lastChanged = true, lastWasRecovery = false, sameAppAsLast = true)

    @Test fun firstStepUsesMain() {
        assertSame(main, ModelRouter(main, light).pick(clean(1)))
    }

    @Test fun lightAfterCleanStep() {
        val r = ModelRouter(main, light)
        assertSame(light, r.pick(clean(2)))
        assertSame(light, r.pick(clean(3)))
        // 读屏一类不报画面变化,不算没变
        assertSame(light, r.pick(clean(4).copy(lastChanged = null)))
    }

    @Test fun eachConditionKeepsMain() {
        val r = ModelRouter(main, light)
        assertSame(main, r.pick(clean(5).copy(lastWasRecovery = true)))
        assertSame(main, r.pick(clean(5).copy(sameAppAsLast = false)))
        // 不看图的轻量模型收不了截图
        assertSame(main, r.pick(clean(5).copy(hasImages = true)))
        val seeing = NamedModel("seeing", vision = true)
        assertSame(seeing, ModelRouter(main, seeing).pick(clean(5).copy(hasImages = true)))
    }

    @Test fun troubleForcesMainForTwoSteps() {
        for (trouble in listOf(clean(3).copy(lastOk = false), clean(3).copy(lastHadGuard = true), clean(3).copy(lastChanged = false))) {
            val r = ModelRouter(main, light)
            assertSame(main, r.pick(trouble))
            assertSame(main, r.pick(clean(4)))
            assertSame(light, r.pick(clean(5)))
        }
        val r = ModelRouter(main, light)
        r.noteTrouble()
        assertSame(main, r.pick(clean(6)))
        assertSame(main, r.pick(clean(7)))
        assertSame(light, r.pick(clean(8)))
    }

    @Test fun disabledRouterAlwaysMain() {
        val r = ModelRouter(main, light)
        r.disable()
        assertSame(main, r.pick(clean(5)))
        org.junit.Assert.assertFalse(r.active)
    }

    @Test fun markRecoveryMakesNextStepRecovery() {
        val t = StepTrack()
        t.after(verb("phone_observe"), ok = true, guard = false, changed = null, recovery = false, app = "com.x")
        t.after(verb("phone_observe"), ok = true, guard = false, changed = null, recovery = false, app = "com.x")
        assertSame(light, ModelRouter(main, light).pick(t.context(3, hasImages = false)))
        t.markRecovery()
        assertSame(main, ModelRouter(main, light).pick(t.context(3, hasImages = false)))
    }

    @Test fun backAndFindVisuallyForceMainRecovery() {
        val t = StepTrack()
        t.after(verb("phone_observe"), ok = true, guard = false, changed = null, recovery = false, app = "com.luna.music")
        t.after(verb("phone_observe"), ok = true, guard = false, changed = null, recovery = false, app = "com.luna.music")
        assertSame(light, ModelRouter(main, light).pick(t.context(3, hasImages = false)))

        // 模拟误入商品页后主模型执行 phone_back: 之后一步必须严格强制为主模型接管纠错，绝不让轻量模型无脑抢权
        t.after(com.hana.agent.core.tool.Verb.Back, ok = true, guard = false, changed = true, recovery = false, app = "com.luna.music")
        assertSame(main, ModelRouter(main, light).pick(t.context(4, hasImages = false)))

        // 模拟自绘界面执行视觉定位 phone_find_visually: 之后一步必须严格由主模型接管
        t.after(com.hana.agent.core.tool.Verb.FindVisually, ok = true, guard = false, changed = null, recovery = false, app = "com.luna.music")
        assertSame(main, ModelRouter(main, light).pick(t.context(5, hasImages = false)))
    }

    @Test fun noToolCapableLightMeansMain() {
        val r = ModelRouter(main, NamedModel("plain", toolCalls = false))
        assertSame(main, r.pick(clean(2)))
        assertSame(main, ModelRouter(main, null).pick(clean(2)))
    }

    @Test fun consecutiveLightStepsUpToEight() {
        val r = ModelRouter(main, light)
        // 验证同应用内可以连续自主爆发 8 步端侧小脑动作
        for (i in 2..9) {
            assertSame("Step $i should be light", light, r.pick(clean(i)))
        }
        // 第 9 步（爆发满 8 步后）第 10 步强制回退给主模型做全局复核
        assertSame("Step 10 should yield to main for reflection", main, r.pick(clean(10)))
    }

    @Test fun extractCoordinateToolCall() {
        val tapTool = listOf(com.hana.agent.core.model.ToolSpec("phone_tap", "tap", "{}"))
        val call1 = ModelCaller.extractToolCallFromText("click(832, 399)", tapTool)
        org.junit.Assert.assertNotNull(call1)
        org.junit.Assert.assertEquals("phone_tap", call1?.name)
        org.junit.Assert.assertTrue(call1?.argsJson?.contains("832") == true)
        org.junit.Assert.assertTrue(call1?.argsJson?.contains("399") == true)

        val call2 = ModelCaller.extractToolCallFromText("[500, 1200]", tapTool)
        org.junit.Assert.assertNotNull(call2)
        org.junit.Assert.assertEquals("phone_tap", call2?.name)
        org.junit.Assert.assertTrue(call2?.argsJson?.contains("500") == true)
        org.junit.Assert.assertTrue(call2?.argsJson?.contains("1200") == true)
    }

    @Test fun sandboxToolsAreMainOnlyAndNotBatchable() {
        assertTrue(SANDBOX_TOOLS.all { ModelRouter.mainOnly(it, verb(it)) })
        assertTrue(SANDBOX_TOOLS.none { it in BATCHABLE })
    }

    @Test fun sceneSlotsRouteToDedicatedModels() {
        val chatModel = NamedModel("chat", vision = false)
        val visionModel = NamedModel("vision", vision = true)
        val summaryModel = NamedModel("summary", vision = false)
        val reflexModel = NamedModel("reflex", vision = true)

        val router = ModelRouter(
            slots = ModelSlots(
                chat = chatModel,
                visionAction = visionModel,
                memorySummary = summaryModel,
                edgeReflex = reflexModel,
            ),
        )

        assertSame(chatModel, router.pickForScene(ModelScene.Chat))
        assertSame(summaryModel, router.pickForScene(ModelScene.MemorySummary))
        assertSame(reflexModel, router.pickForScene(ModelScene.EdgeReflex))
        assertSame(visionModel, router.pickForScene(ModelScene.VisionAction, clean(1)))
        assertSame(reflexModel, router.pickForScene(ModelScene.VisionAction, clean(2)))
    }

    @Test fun thermalAndBatteryCallbacksReduceConcurrencyAndDowngradeModels() {
        val chatModel = NamedModel("chat-heavy", vision = true)
        val visionModel = NamedModel("vision-heavy", vision = true)
        val summaryModel = NamedModel("summary-light", vision = false)
        val reflexModel = NamedModel("reflex-light", vision = true)

        val router = ModelRouter(
            slots = ModelSlots(
                chat = chatModel,
                visionAction = visionModel,
                memorySummary = summaryModel,
                edgeReflex = reflexModel,
            ),
            baseConcurrency = 4,
        )

        val observedPolicies = mutableListOf<RoutingPolicy>()
        router.addPolicyListener { _, policy -> observedPolicies += policy }

        assertEquals(4, router.recommendedConcurrency)
        assertTrue(router.currentPolicy.allowHeavyLocalVlm)
        assertSame(chatModel, router.pickForScene(ModelScene.Chat))

        // 1. 中度发热：并发减半
        val modPolicy = router.onThermalStatusChanged(ThermalLevel.MODERATE)
        assertEquals(2, modPolicy.maxConcurrency)
        assertEquals(2, router.recommendedConcurrency)

        // 2. 严重发热：并发缩至 1，禁用重型端侧 VLM，Chat 与 VisionAction 降级轻量模型
        val severePolicy = router.onThermalStatusChanged(ThermalLevel.SEVERE)
        assertEquals(1, severePolicy.maxConcurrency)
        org.junit.Assert.assertFalse(severePolicy.allowHeavyLocalVlm)
        assertTrue(severePolicy.preferLightweightModel)
        assertSame(reflexModel, router.pickForScene(ModelScene.Chat))
        assertSame(reflexModel, router.pickForScene(ModelScene.VisionAction, clean(1)))

        // 3. 恢复正常温度但电量低于 15% 且未充电：仍保持降级保护
        router.onThermalStatusChanged(ThermalLevel.NONE)
        val lowBatPolicy = router.onBatteryLevelChanged(batteryPct = 12, isCharging = false)
        assertEquals(1, lowBatPolicy.maxConcurrency)
        assertTrue(lowBatPolicy.preferLightweightModel)
        assertSame(reflexModel, router.pickForScene(ModelScene.Chat))

        // 4. 插上电源充电后解除低电量限流
        val chargingPolicy = router.onBatteryLevelChanged(batteryPct = 12, isCharging = true)
        assertEquals(4, chargingPolicy.maxConcurrency)
        org.junit.Assert.assertFalse(chargingPolicy.preferLightweightModel)
        assertSame(chatModel, router.pickForScene(ModelScene.Chat))
        assertTrue(observedPolicies.size >= 4)
    }

    private fun assertEquals(expected: Int, actual: Int) = org.junit.Assert.assertEquals(expected, actual)
}

/** 「在哪」分手机 App 与网站:换到网页、打开网址都算换了地方。 */
class PlaceTrackingTest {
    @Test fun placeOfSeparatesAppsAndSites() {
        fun o(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
        org.junit.Assert.assertEquals(Place.app("com.x"), placeOf(o("""{"currentApp":"com.x"}""")))
        org.junit.Assert.assertEquals(Place.site("a.com"), placeOf(o("""{"currentSite":"a.com"}""")))
        org.junit.Assert.assertEquals(Place.site("a.com"), placeOf(o("""{"ok":true,"screen":{"currentSite":"a.com"}}""")))
        org.junit.Assert.assertNull(placeOf(o("""{"ok":true}""")))
    }

    @Test fun navigatingIsASwitch() {
        val t = StepTrack()
        t.after(verb("phone_tap"), true, false, true, false, "网站 a.com")
        t.after(verb("phone_tap"), true, false, true, false, "网站 a.com")
        org.junit.Assert.assertTrue(t.context(3, false).sameAppAsLast)
        t.after(verb("web_navigate"), true, false, true, false, "网站 a.com")
        org.junit.Assert.assertFalse(t.context(4, false).sameAppAsLast)
    }
}

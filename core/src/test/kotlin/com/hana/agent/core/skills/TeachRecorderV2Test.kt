package com.hana.agent.core.skills

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teach Mode 2.0 示范录制编译器单元测试：
 * 覆盖连续输入合并（<1200ms）、控件锚点/归一化坐标记录、滚动方向映射、
 * 变量化参数槽位生成（{{input_N}}）、强脱敏与 RouteGraph 导出。
 */
class TeachRecorderV2Test {

    @Test
    fun mergesConsecutiveTypingOnSameControlWithin1200Ms() {
        val recorder = TeachRecorder()

        // 模拟在同一个输入框（同一 resourceId）内连续逐字打字，间隔均 < 1200ms
        recorder.recordStep(
            packageName = "com.taobao.taobao",
            actionType = "type_text",
            targetLabel = "h",
            inputSample = "h",
            timestampMs = 1_000L,
            resourceId = "com.taobao.taobao:id/search_edit",
            normalizedBounds = listOf(80, 60, 820, 130),
        )
        recorder.recordStep(
            packageName = "com.taobao.taobao",
            actionType = "type_text",
            targetLabel = "ha",
            inputSample = "ha",
            timestampMs = 1_250L,
            resourceId = "com.taobao.taobao:id/search_edit",
            normalizedBounds = listOf(80, 60, 820, 130),
        )
        recorder.recordStep(
            packageName = "com.taobao.taobao",
            actionType = "input",
            targetLabel = "搜索框",
            inputSample = "hana 智能体",
            timestampMs = 2_000L,
            resourceId = "com.taobao.taobao:id/search_edit",
            normalizedBounds = listOf(80, 60, 820, 130),
        )

        // 间隔 >= 1200ms 的另一次同控件输入不应合并
        recorder.recordStep(
            packageName = "com.taobao.taobao",
            actionType = "type_text",
            targetLabel = "搜索框",
            inputSample = "补充关键词",
            timestampMs = 3_500L,
            resourceId = "com.taobao.taobao:id/search_edit",
            normalizedBounds = listOf(80, 60, 820, 130),
        )

        val steps = recorder.currentSteps()
        assertEquals("连续 <1200ms 输入应合并为 1 步，加上超时后的第 2 步共 2 步", 2, steps.size)
        assertEquals(1, steps[0].seq)
        assertEquals("hana 智能体", steps[0].inputSample)
        assertEquals("com.taobao.taobao:id/search_edit", steps[0].resourceId)
        assertEquals(listOf(80, 60, 820, 130), steps[0].normalizedBounds)
        assertEquals(2, steps[1].seq)
        assertEquals("补充关键词", steps[1].inputSample)
    }

    @Test
    fun doesNotMergeTypingAcrossDifferentControlsWithin1200Ms() {
        val recorder = TeachRecorder()

        recorder.recordStep(
            packageName = "com.bank.app",
            actionType = "input",
            targetLabel = "收款人",
            inputSample = "13800138000",
            timestampMs = 1_000L,
            resourceId = "com.bank.app:id/et_account",
        )
        recorder.recordStep(
            packageName = "com.bank.app",
            actionType = "input",
            targetLabel = "金额",
            inputSample = "288元",
            timestampMs = 1_400L,
            resourceId = "com.bank.app:id/et_amount",
        )

        assertEquals(2, recorder.currentSteps().size)
    }

    @Test
    fun generatesParameterizedSlotsStructuredStepsAndRouteGraphWithRedaction() {
        val recorder = TeachRecorder()

        recorder.recordStep(
            packageName = "com.shop.app",
            actionType = "tap",
            targetLabel = "搜索入口",
            timestampMs = 1_000L,
            resourceId = "com.shop.app:id/btn_search",
            normalizedBounds = listOf(100, 80, 900, 150),
        )
        recorder.recordStep(
            packageName = "com.shop.app",
            actionType = "type_text",
            targetLabel = "商品关键字",
            inputSample = "降噪耳机 13912345678",
            timestampMs = 1_500L,
            resourceId = "com.shop.app:id/et_query",
            normalizedBounds = listOf(120, 90, 780, 145),
        )
        recorder.recordStep(
            packageName = "com.shop.app",
            actionType = "scroll_down",
            targetLabel = "商品列表",
            timestampMs = 2_800L,
            resourceId = "com.shop.app:id/rv_list",
            normalizedBounds = listOf(0, 200, 999, 950),
        )
        recorder.recordStep(
            packageName = "com.shop.app",
            actionType = "scroll_up",
            targetLabel = "商品列表",
            timestampMs = 4_000L,
            resourceId = "com.shop.app:id/rv_list",
        )

        val draft = recorder.finishAndBuildDraft(
            suggestedName = "shop-search-scroll",
            display = "商城搜索与浏览",
        )

        assertEquals(4, draft.stepCount)
        assertEquals(listOf("com.shop.app"), draft.appsUsed)
        assertEquals(1, draft.paramSlots.size)
        assertEquals("input_1", draft.paramSlots[0].name)
        assertEquals("com.shop.app:id/et_query", draft.paramSlots[0].resourceId)

        // 验证 Markdown 包含槽位提示、脱敏占位符且绝不残留明文手机号与原始输入
        assertTrue(draft.markdownBody.contains("{{input_1}}"))
        assertTrue(draft.markdownBody.contains(SkillRedactor.PLACEHOLDER))
        assertFalse(draft.markdownBody.contains("13912345678"))
        assertFalse(draft.markdownBody.contains("降噪耳机"))

        // 验证结构化步骤映射
        assertEquals(4, draft.structuredSteps.size)
        assertEquals("phone_tap", draft.structuredSteps[0].tool)
        assertEquals("phone_type_text", draft.structuredSteps[1].tool)
        assertEquals("input_1", draft.structuredSteps[1].paramSlot)
        assertEquals("phone_swipe", draft.structuredSteps[2].tool)
        assertEquals("phone_swipe", draft.structuredSteps[3].tool)

        // 验证可导出为标准 RouteGraph
        val graph = draft.toRouteGraph()
        assertEquals("teach_shop-search-scroll", graph.graphId)
        assertEquals("n_0", graph.initialNodeId)
        assertEquals(5, graph.nodes.size)
        assertEquals(4, graph.edges.size)
        assertTrue(graph.nodes.getValue("n_4").isTerminal)

        val typeEdge = graph.edges[1]
        assertEquals("phone_type_text", typeEdge.action.tool)
        assertEquals("{{input_1}}", typeEdge.action.args["text"]?.jsonPrimitive?.content)
        assertNotNull(graph.findNextStep("n_0"))
    }

    @Test
    fun preservesTwoDigitSlotTokensDuringNumberScrubbing() {
        val recorder = TeachRecorder()
        repeat(10) { idx ->
            recorder.recordStep(
                packageName = "com.example.form",
                actionType = "input",
                targetLabel = "字段",
                inputSample = "样本值${idx + 100}",
                timestampMs = 1_000L + idx * 2_000L,
                resourceId = "com.example.form:id/field_$idx",
            )
        }

        val draft = recorder.finishAndBuildDraft("multi-slot-form", "多槽位表单")
        assertEquals(10, draft.paramSlots.size)
        assertEquals("input_10", draft.paramSlots.last().name)
        assertTrue("第 10 个参数槽位 {{input_10}} 不应被数字脱敏破坏", draft.markdownBody.contains("{{input_10}}"))
        assertFalse(draft.markdownBody.contains("样本值109"))
    }
}

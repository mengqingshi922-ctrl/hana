package com.hana.agent.core.action

import com.hana.agent.core.cap.phone.ActionChainTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentCPM-GUI 超紧凑动作别名 DSL 解析器与反向编码单元测试。
 */
class CompactActionDslTest {

    @Test
    fun testSingleTapCommands() {
        val step1 = CompactActionDsl.parseBatch("t(e3)").first()
        assertEquals("phone_tap", step1.tool)
        assertEquals("e3", step1.args["ref"]?.jsonPrimitive?.content)

        val step2 = CompactActionDsl.parseBatch("tap(@e12)").first()
        assertEquals("phone_tap", step2.tool)
        assertEquals("@e12", step2.args["ref"]?.jsonPrimitive?.content)

        val step3 = CompactActionDsl.parseBatch("t(150, 450)").first()
        assertEquals("phone_tap", step3.tool)
        assertEquals(150, step3.args["x"]?.jsonPrimitive?.intOrNull)
        assertEquals(450, step3.args["y"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun testTypeAndSwipeCommands() {
        val typeSteps = CompactActionDsl.parseBatch("tp(e3, \"hello, world\"); type(\"direct text\")")
        assertEquals(2, typeSteps.size)

        assertEquals("phone_type", typeSteps[0].tool)
        assertEquals("e3", typeSteps[0].args["ref"]?.jsonPrimitive?.content)
        assertEquals("hello, world", typeSteps[0].args["text"]?.jsonPrimitive?.content)

        assertEquals("phone_type", typeSteps[1].tool)
        assertEquals("direct text", typeSteps[1].args["text"]?.jsonPrimitive?.content)

        val swipeStep = CompactActionDsl.parseBatch("sw(500,1400,500,400)").first()
        assertEquals("phone_swipe", swipeStep.tool)
        assertEquals(500, swipeStep.args["x1"]?.jsonPrimitive?.intOrNull)
        assertEquals(1400, swipeStep.args["y1"]?.jsonPrimitive?.intOrNull)
        assertEquals(500, swipeStep.args["x2"]?.jsonPrimitive?.intOrNull)
        assertEquals(400, swipeStep.args["y2"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun testBackHomeWaitCommands() {
        val dsl = """
            bk()
            hm()
            wt(800)
        """.trimIndent()
        val steps = CompactActionDsl.parseBatch(dsl)
        assertEquals(3, steps.size)

        assertEquals("phone_back", steps[0].tool)
        assertEquals("phone_home", steps[1].tool)
        assertEquals("wait", steps[2].tool)
        assertEquals(800L, steps[2].args["ms"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun testRoundTripEncodeAndDecode() {
        val originalDsl = "t(e3); tp(e3, \"welcome\"); sw(500,1400,500,400); bk(); hm(); wt(800)"
        val steps = CompactActionDsl.parseBatch(originalDsl)
        assertEquals(6, steps.size)

        val encoded = CompactActionDsl.encodeSteps(steps)
        assertTrue(encoded.contains("t(e3)"))
        assertTrue(encoded.contains("tp(e3, \"welcome\")"))
        assertTrue(encoded.contains("sw(500,1400,500,400)"))
        assertTrue(encoded.contains("bk()"))
        assertTrue(encoded.contains("hm()"))
        assertTrue(encoded.contains("wt(800)"))

        val decodedAgain = CompactActionDsl.parseBatch(encoded)
        assertEquals(steps.size, decodedAgain.size)
        assertEquals(steps[0].tool, decodedAgain[0].tool)
        assertEquals(steps[1].args["text"]?.jsonPrimitive?.content, decodedAgain[1].args["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun testActionChainToolWithCompactDsl() = runBlocking {
        val argsWithCompact = buildJsonObject {
            put("compact", "t(e5); tp(e5, \"hana\"); wt(600)")
            put("fallbackStrategy", "backtrack")
        }
        val result = ActionChainTool.execute(argsWithCompact)
        assertTrue(result.content["ok"]?.jsonPrimitive?.content.toBoolean())
        assertEquals("3", result.content["stepsCount"]?.jsonPrimitive?.content)
        assertEquals("backtrack", result.content["strategy"]?.jsonPrimitive?.content)

        // 验证 100% 向后兼容旧 steps JSON 数组
        val argsWithSteps = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "phone_tap")
                    put("args", buildJsonObject { put("ref", "e1") })
                })
            })
        }
        val resultSteps = ActionChainTool.execute(argsWithSteps)
        assertTrue(resultSteps.content["ok"]?.jsonPrimitive?.content.toBoolean())
        assertEquals("1", resultSteps.content["stepsCount"]?.jsonPrimitive?.content)

        // 验证缺少 steps 和 compact 时的失败返回
        val emptyArgs = buildJsonObject {}
        val emptyResult = ActionChainTool.execute(emptyArgs)
        assertFalse(emptyResult.content["ok"]?.jsonPrimitive?.content.toBoolean())
        assertEquals("missing_steps", emptyResult.content["error"]?.jsonPrimitive?.content)
    }
}

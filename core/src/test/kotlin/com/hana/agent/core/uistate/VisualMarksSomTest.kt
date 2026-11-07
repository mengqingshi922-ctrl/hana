package com.hana.agent.core.uistate

import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.model.Part
import com.hana.agent.core.tool.FakeScreen
import com.hana.agent.core.tool.execute
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualMarksSomTest {

    @Test
    fun testObserveGeneratesVisualMarksOnPoorTree() = runTest {
        // 无障碍树只有一个根节点，判定为 poor tree
        val emptyTree = ScreenSnapshot(
            "p1", "com.flutter.app", emptyList(), 1080, 2400, windows = listOf(
                UiWindowTree(1, "application", 1, null, "com.flutter.app", true, true, emptyList())
            )
        )
        val ocrLines = listOf(
            VisibleTextLine("立即登录", listOf(100, 200, 300, 260)),
            VisibleTextLine("手机快捷登录", listOf(100, 300, 400, 360))
        )
        val port = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = emptyTree
            override suspend fun readVisibleText() = ocrLines
        }

        val d = toolDispatcher(port)
        val result = d.executeRead(Part.ToolCall("o1", "phone_observe", "{}"))
        val content = result.content

        val marks = content["visualMarks"]?.jsonArray
        assertNotNull("visualMarks must be generated on poor tree", marks)
        assertEquals(2, marks!!.size)

        val firstMark = marks[0].jsonObject
        assertEquals("@v_txt0", firstMark["ref"]!!.jsonPrimitive.content)
        assertEquals("立即登录", firstMark["text"]!!.jsonPrimitive.content)
        val center = firstMark["center"]!!.jsonObject
        assertEquals(200, center["x"]!!.jsonPrimitive.content.toInt())
        assertEquals(230, center["y"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun testNativeCoordinateGroundingFastLane() = runTest {
        val tapRecorded = mutableListOf<Pair<Int, Int>>()
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                tapRecorded += x to y
                return ActionResult.Ok(changed = true)
            }
        }

        val d = toolDispatcher(screen)
        // 传入归一化坐标 x=500, y=500 (对应 1080x2400 物理屏中心)
        val result = d.execute(Part.ToolCall("t1", "phone_tap", """{"x":500,"y":500}"""))

        assertEquals(1, tapRecorded.size)
        assertEquals(true, result.content["ok"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(540, tapRecorded[0].first)
        assertEquals(1201, tapRecorded[0].second)
    }
}

package com.hana.agent.core.uistate

import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.model.Part
import com.hana.agent.core.tool.FakeScreen
import com.hana.agent.core.tool.execute
import com.hana.agent.core.vision.ImageOps
import com.hana.agent.core.vision.JvmTestImageOps
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class SpyImageOps(private val delegate: ImageOps = JvmTestImageOps) : ImageOps by delegate {
    var drawVisualMarksCalls = 0
    val recordedMarks = mutableListOf<List<Pair<Int, IntArray>>>()

    override fun drawVisualMarks(img: ByteArray, marks: List<Pair<Int, IntArray>>): ByteArray {
        drawVisualMarksCalls++
        recordedMarks += marks
        return "SOM_MARKED".toByteArray()
    }
}

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
    fun testObserveDrawsVisualMarksOnPoorTreeWithImage() = runTest {
        val fakeRawBytes = "RAW_ORIGINAL_PNG".toByteArray()
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
            override suspend fun screenshotPng() = fakeRawBytes
        }

        val spy = SpyImageOps()
        val d = toolDispatcher(port, visionEnabled = true, imageOps = spy)
        val result = d.executeRead(Part.ToolCall("o1", "phone_observe", "{}"))

        // 验证调用了 drawVisualMarks
        assertEquals(1, spy.drawVisualMarksCalls)
        val marks = spy.recordedMarks.single()
        assertEquals(2, marks.size)
        assertEquals(0, marks[0].first)
        assertArrayEquals(intArrayOf(100, 200, 300, 260), marks[0].second)
        assertEquals(1, marks[1].first)
        assertArrayEquals(intArrayOf(100, 300, 400, 360), marks[1].second)

        // 验证返回给模型的 image 为打标后图像，而 frame 为未打标的原始图
        assertNotNull(result.image)
        assertArrayEquals("SOM_MARKED".toByteArray(), result.image)
        assertNotNull(result.frame)
        assertArrayEquals(fakeRawBytes, result.frame)

        // 验证 content 里包含了 visualMarks
        assertNotNull(result.content["visualMarks"])
    }

    @Test
    fun testObserveDoesNotDrawVisualMarksOnAdequateTree() = runTest {
        val fakeRawBytes = "RAW_ORIGINAL_PNG".toByteArray()
        val normalTree = ScreenSnapshot(
            "p2", "com.normal.app", listOf(
                UiElement("@e1", "button", "登录", 200, 230, listOf(100, 200, 300, 260), null, true, false, false),
                UiElement("@e2", "button", "注册", 250, 330, listOf(100, 300, 400, 360), null, true, false, false)
            ), 1080, 2400, windows = listOf(
                UiWindowTree(1, "application", 1, null, "com.normal.app", true, true, emptyList())
            )
        )
        val ocrLines = listOf(
            VisibleTextLine("立即登录", listOf(100, 200, 300, 260))
        )
        val port = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = normalTree
            override suspend fun readVisibleText() = ocrLines
            override suspend fun screenshotPng() = fakeRawBytes
        }

        val spy = SpyImageOps()
        val d = toolDispatcher(port, visionEnabled = true, imageOps = spy)
        val result = d.executeRead(Part.ToolCall("o2", "phone_observe", "{}"))

        // 非 poor tree 时不调用 drawVisualMarks
        assertEquals(0, spy.drawVisualMarksCalls)
        assertArrayEquals(fakeRawBytes, result.image)
        assertArrayEquals(fakeRawBytes, result.frame)
        assertNull(result.content["visualMarks"])
    }

    @Test
    fun testWithScreenAppliesVisualMarksOnPoorTree() = runTest {
        val fakeRawBytes = "RAW_ACTION_PNG".toByteArray()
        val emptyTree = ScreenSnapshot(
            "p3", "com.flutter.app", emptyList(), 1080, 2400, windows = listOf(
                UiWindowTree(1, "application", 1, null, "com.flutter.app", true, true, emptyList())
            )
        )
        val ocrLines = listOf(
            VisibleTextLine("确定提交", listOf(200, 500, 600, 580))
        )
        val port = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = emptyTree
            override suspend fun readVisibleText() = ocrLines
            override suspend fun screenshotPng() = fakeRawBytes
            override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok(changed = true)
        }

        val spy = SpyImageOps()
        val d = toolDispatcher(port, visionEnabled = true, inlineScreen = true, recordFrames = true, imageOps = spy)
        val result = d.execute(Part.ToolCall("t1", "phone_tap", """{"x":500,"y":500}"""))

        // 动作后 withScreen 应附带打标后的图片和 visualMarks diff
        assertEquals(1, spy.drawVisualMarksCalls)
        val screenObj = result.content["screen"]?.jsonObject
        assertNotNull(screenObj)
        assertNotNull(screenObj!!["visualMarks"])

        // 验证返回给模型的 image 也是打标后的图片，而 frame 保留原始图片
        assertEquals(1, result.images.size)
        assertArrayEquals("SOM_MARKED".toByteArray(), result.images.single())
        assertNotNull(result.frame)
        assertArrayEquals(fakeRawBytes, result.frame)
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

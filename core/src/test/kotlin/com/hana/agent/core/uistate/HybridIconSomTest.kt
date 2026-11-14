package com.hana.agent.core.uistate

import com.hana.agent.core.access.AppAccessPolicy
import com.hana.agent.core.access.AppAccessState
import com.hana.agent.core.cap.phone.PHONE_FAMILY
import com.hana.agent.core.model.Capabilities
import com.hana.agent.core.model.ChatModel
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.ModelReply
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.StopReason
import com.hana.agent.core.model.ToolSpec
import com.hana.agent.core.vision.ImageOps
import com.hana.agent.core.vision.JvmTestImageOps
import com.hana.agent.core.vision.LocateRegistry
import com.hana.agent.core.vision.LocateResult
import com.hana.agent.core.vision.Locator
import com.hana.agent.core.vision.VisionService
import com.hana.agent.core.visual.Shots
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 混合 SoM 物理标牌渲染（AppAgentX + OmniParser V2）与
 * 小目标自适应 ROI 放大精定位（Ferret-UI 2）单元测试。
 */
class HybridIconSomTest {

    private class RecordingImageOps(private val delegate: ImageOps = JvmTestImageOps) : ImageOps {
        val visualMarkCalls = mutableListOf<List<Pair<Int, IntArray>>>()
        val cropBoxes = mutableListOf<IntArray>()
        val scaleFactors = mutableListOf<Double>()
        val drawFrameBoxes = mutableListOf<IntArray>()

        override fun size(img: ByteArray): Pair<Int, Int> = delegate.size(img)
        override fun aHash(img: ByteArray): Long = delegate.aHash(img)
        override fun toJpeg(img: ByteArray, maxSide: Int, quality: Int): ByteArray = delegate.toJpeg(img, maxSide, quality)
        override fun pixels(img: ByteArray): IntArray = delegate.pixels(img)
        override fun isMostlyBlack(img: ByteArray): Boolean = delegate.isMostlyBlack(img)

        override fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int, strokePx: Int): ByteArray {
            drawFrameBoxes += box.copyOf()
            return delegate.drawFrame(img, box, expandPx, strokePx)
        }

        override fun crop(img: ByteArray, box: IntArray): ByteArray {
            cropBoxes += box.copyOf()
            return delegate.crop(img, box)
        }

        override fun scale(img: ByteArray, factor: Double): ByteArray {
            scaleFactors += factor
            return delegate.scale(img, factor)
        }

        override fun drawVisualMarks(img: ByteArray, marks: List<Pair<Int, IntArray>>): ByteArray {
            visualMarkCalls += marks.map { it.first to it.second.copyOf() }
            // 返回一个可区分于原图的有效 PNG 字节流，便于验证 image 确实经过绘制而 frame 保持原图
            return delegate.drawFrame(img, marks.first().second, expandPx = 1, strokePx = 2)
        }
    }

    private class FakeScreen(
        private val elements: List<UiElement>,
        private val ocrLines: List<VisibleTextLine> = emptyList(),
        private val png: ByteArray = blankPng(1080, 2400),
    ) : ScreenPort {
        override val appAccess: AppAccessPolicy = AppAccessPolicy { AppAccessState(revision = 1L) }
        override suspend fun snapshot(): ScreenSnapshot = ScreenSnapshot("snap-1", "com.example.shop", elements, 1080, 2400)
        override suspend fun peek(): ScreenSnapshot = snapshot()
        override suspend fun screenshotPng(): ByteArray = png
        override suspend fun readVisibleText(): List<VisibleTextLine> = ocrLines
        override suspend fun isProtected(): Boolean = false
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok()
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
        override suspend fun back() = ActionResult.Ok()
        override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = emptyList<Pair<String, String>>()
    }

    private class ScriptedVisionModel(private val replies: List<String>) : ChatModel {
        override val capabilities = Capabilities(
            toolCalls = false,
            vision = true,
            contextTokens = 32_000,
            reasoning = false,
            groundingVerified = true,
        )
        val requests = mutableListOf<List<Message>>()
        private var idx = 0

        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            requests += messages
            val text = replies.getOrElse(idx++) { replies.last() }
            return ModelReply(listOf(Part.Text(text)), StopReason.EndTurn, null)
        }
    }

    @Test
    fun `adequate tree without ico elements does not call drawVisualMarks`() = runTest {
        val rawPng = blankPng(1080, 2400)
        val elements = List(6) { i ->
            UiElement(
                ref = "@e${i + 1}",
                role = "button",
                name = "按钮$i",
                centerX = 200,
                centerY = 200 + i * 160,
                bounds = listOf(80, 150 + i * 160, 320, 250 + i * 160),
                resourceId = "btn_$i",
                clickable = true,
                editable = false,
                scrollable = false,
            )
        }
        val ops = RecordingImageOps()
        val session = ScreenSession(
            surface = Surface(PHONE_FAMILY, FakeScreen(elements, png = rawPng), { it }, "packageName"),
            stableRefs = false,
            visionEnabled = true,
            recordFrames = true,
            lastRead = { null },
            shots = Shots(),
            imageOps = ops,
        )

        val exec = session.observe(withImage = true)
        assertTrue("不应触发物理 SoM 绘制", ops.visualMarkCalls.isEmpty())
        assertNull("无 OCR 兜底且无 ico_ 元素时不应输出 visualMarks", exec.content["visualMarks"])
        assertEquals(rawPng.toList(), exec.images.single().toList())
    }

    @Test
    fun `adequate tree with ico_ elements renders physical SoM marks on sent image`() = runTest {
        val rawPng = blankPng(1080, 2400)
        val elements = List(5) { i ->
            UiElement(
                ref = "@e${i + 1}",
                role = "button",
                name = "商品$i",
                centerX = 300,
                centerY = 300 + i * 200,
                bounds = listOf(100, 250 + i * 200, 500, 350 + i * 200),
                resourceId = "item_$i",
                clickable = true,
                editable = false,
                scrollable = false,
            )
        } + listOf(
            UiElement(
                ref = "ico_0",
                role = "icon_candidate",
                name = "搜索图标",
                centerX = 940,
                centerY = 120,
                bounds = listOf(900, 80, 980, 160),
                resourceId = null,
                clickable = true,
                editable = false,
                scrollable = false,
            ),
            UiElement(
                ref = "ico_1",
                role = "icon_candidate",
                name = "",
                centerX = 940,
                centerY = 260,
                bounds = listOf(910, 230, 970, 290),
                resourceId = null,
                clickable = true,
                editable = false,
                scrollable = false,
            ),
        )
        val ops = RecordingImageOps()
        val session = ScreenSession(
            surface = Surface(PHONE_FAMILY, FakeScreen(elements, png = rawPng), { it }, "packageName"),
            stableRefs = false,
            visionEnabled = true,
            recordFrames = true,
            lastRead = { null },
            shots = Shots(),
            imageOps = ops,
        )

        val exec = session.observe(withImage = true)
        assertEquals(1, ops.visualMarkCalls.size)
        val drawn = ops.visualMarkCalls.single()
        assertEquals(2, drawn.size)
        assertEquals(0, drawn[0].first)
        assertEquals(1, drawn[1].first)

        val marks = exec.content["visualMarks"] as? JsonArray
        assertNotNull("存在 ico_ 图标元素时必须生成 visualMarks 映射", marks)
        assertEquals(2, marks!!.size)
        assertEquals("@ico_0", marks[0].jsonObject["ref"]?.jsonPrimitive?.contentOrNull)
        assertEquals("@ico_1", marks[1].jsonObject["ref"]?.jsonPrimitive?.contentOrNull)
        assertEquals("icon_candidate", marks[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0, marks[0].jsonObject["markId"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, marks[1].jsonObject["markId"]?.jsonPrimitive?.intOrNull)

        // 发给模型的图片必须已画标牌，而回放底图 frame 必须保持原图纯净
        assertFalse(exec.images.single().contentEquals(rawPng))
        assertTrue(exec.frame!!.contentEquals(rawPng))
    }

    @Test
    fun `poor tree with ocr and stabilized ico_ elements encodes both v_txt and ico_ marks`() = runTest {
        val rawPng = blankPng(1080, 2400)
        // 仅 1 个 ico_ 元素（触发 tree.poor），且开启 stableRefs = true 时 RefStabilizer 会将其重命名为 @e1
        val elements = listOf(
            UiElement(
                ref = "ico_0",
                role = "icon_candidate",
                name = "",
                centerX = 950,
                centerY = 120,
                bounds = listOf(910, 80, 990, 160),
                resourceId = null,
                clickable = true,
                editable = false,
                scrollable = false,
            ),
        )
        val ocrLines = listOf(
            VisibleTextLine("立即购买", listOf(300, 2000, 780, 2120)),
        )
        val ops = RecordingImageOps()
        val session = ScreenSession(
            surface = Surface(PHONE_FAMILY, FakeScreen(elements, ocrLines = ocrLines, png = rawPng), { it }, "packageName"),
            stableRefs = true,
            visionEnabled = true,
            recordFrames = false,
            lastRead = { null },
            shots = Shots(),
            imageOps = ops,
        )

        val exec = session.observe(withImage = true)
        assertEquals(1, ops.visualMarkCalls.size)
        assertEquals(2, ops.visualMarkCalls.single().size)

        val marks = exec.content["visualMarks"] as JsonArray
        val refs = marks.map { it.jsonObject["ref"]?.jsonPrimitive?.contentOrNull }
        assertTrue("贫乏树下应保留 OCR 文本标牌 @v_txt0", "@v_txt0" in refs)
        assertTrue("开启稳定编号后仍应识别原始 ico_ 端口图标并生成 @ico_0 标牌", "@ico_0" in refs)
        val icoEntry = marks.first { it.jsonObject["ref"]?.jsonPrimitive?.contentOrNull == "@ico_0" }.jsonObject
        assertNotNull("stableRefs 映射后的元素 ref 应记录在 elementRef", icoEntry["elementRef"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `ferret ui 2 adaptive roi thresholds and coordinate back-projection validation`() {
        val ops = RecordingImageOps()
        val model = ScriptedVisionModel(listOf("""{"box":[0,0,10,10]}"""))
        val locator = Locator(
            screen = FakeScreen(emptyList()),
            vision = VisionService(model, ops),
            registry = LocateRegistry(),
            family = PHONE_FAMILY,
            enableTwoStageRoi = true,
        )

        // 1. 面积占比 < 2.5% 的极小目标（40x30 -> 0.12%）必须触发 ROI
        assertTrue(locator.shouldUseRoi(480, 480, 520, 510))
        // 2. 边缘小目标（右上方关闭按钮，面积约 3.2%）必须触发 ROI
        assertTrue(locator.shouldUseRoi(820, 20, 980, 220))
        // 3. 居中大按钮（400x200 -> 8%）不应浪费第二轮 ROI
        assertFalse(locator.shouldUseRoi(300, 400, 700, 600))

        // 4. 验证 ROI 窗口计算与坐标回射范围校验
        val roi = locator.computeRoiWindow(920, 40, 960, 75)
        assertNotNull(roi)
        roi!!
        assertTrue("极小目标缩放倍率应 >= 2.0", roi.zoomFactor >= 2.0)
        assertTrue(roi.l in 0..920 && roi.r in 960..999)
        assertTrue(roi.t in 0..40 && roi.b in 75..999)

        // 合法局部坐标应准确回射到全局归一化坐标
        val projected = locator.projectRoiBoxToGlobal(intArrayOf(400, 400, 600, 600), roi)
        assertNotNull(projected)
        assertTrue(projected!![0] in roi.l..roi.r && projected[2] in roi.l..roi.r)
        assertTrue(projected[1] in roi.t..roi.b && projected[3] in roi.t..roi.b)

        // 越界局部坐标必须被校验拦截返回 null
        assertNull(locator.projectRoiBoxToGlobal(intArrayOf(-50, 200, 300, 400), roi))
        assertNull(locator.projectRoiBoxToGlobal(intArrayOf(200, 200, 1080, 400), roi))
        // 退化空框必须被拦截
        assertNull(locator.projectRoiBoxToGlobal(intArrayOf(500, 500, 500, 520), roi))
    }

    @Test
    fun `locateWithRoiRefinement zooms small corner button and snaps center accurately`() = runTest {
        val rawPng = blankPng(1000, 1000)
        val ops = RecordingImageOps()
        // 第1轮粗定位略有偏移 [905, 30, 955, 70]；
        // 第2轮在局部放大 ROI 上精定位（局部居中微偏右下方）-> 全局精确落点 [924, 42, 956, 72]；
        // 第3轮红框确认返回 ok
        val roi = Locator(
            FakeScreen(emptyList(), png = rawPng),
            VisionService(ScriptedVisionModel(listOf("{}")), ops),
            LocateRegistry(),
            PHONE_FAMILY,
            enableTwoStageRoi = true,
        ).computeRoiWindow(905, 30, 955, 70)!!
        // 反向推算期望全局框 [924, 42, 956, 72] 在 ROI 内的局部 0..999 坐标
        val roiW = (roi.r - roi.l).coerceAtLeast(1)
        val roiH = (roi.b - roi.t).coerceAtLeast(1)
        val localL = (((924 - roi.l).toDouble() / roiW) * 999.0).toInt()
        val localT = (((42 - roi.t).toDouble() / roiH) * 999.0).toInt()
        val localR = (((956 - roi.l).toDouble() / roiW) * 999.0).toInt()
        val localB = (((72 - roi.t).toDouble() / roiH) * 999.0).toInt()

        val model = ScriptedVisionModel(
            listOf(
                """{"bbox_2d":[905,30,955,70],"seen":"右上角关闭叉号"}""",
                """{"bbox_2d":[$localL,$localT,$localR,$localB],"seen":"放大后的关闭叉号 ×"}""",
                "YES",
            ),
        )
        val locator = Locator(
            screen = FakeScreen(emptyList(), png = rawPng),
            vision = VisionService(model, ops),
            registry = LocateRegistry(),
            family = PHONE_FAMILY,
            enableTwoStageRoi = true,
        )

        val result = locator.locateWithRoiRefinement("右上角关闭按钮", text = null)
        assertTrue("二阶段 ROI 放大定位应成功返回坐标", result is LocateResult.Found)
        val found = result as LocateResult.Found
        assertEquals("应调用 3 次模型（粗定位 + ROI 放大精定位 + 局部红框确认）", 3, model.requests.size)
        assertTrue("应对极小目标执行高倍率 ROI 缩放", ops.scaleFactors.any { it >= 2.0 })
        // 精定位中心应更接近真实 [940, 57] 而非粗定位 [930, 50]
        assertTrue("精定位 x=${found.x} 应落在精调目标区 935..945", found.x in 935..945)
        assertTrue("精定位 y=${found.y} 应落在精调目标区 52..62", found.y in 52..62)
    }

    companion object {
        private fun blankPng(w: Int, h: Int): ByteArray =
            JvmTestImageOps.encode(w, h) { _, _ -> 240 }
    }
}

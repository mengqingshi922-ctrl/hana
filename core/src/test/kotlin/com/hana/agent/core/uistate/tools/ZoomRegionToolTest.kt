package com.hana.agent.core.uistate.tools

import com.hana.agent.core.cap.phone.PHONE_FAMILY
import com.hana.agent.core.tool.ArgError
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.EvidenceLog
import com.hana.agent.core.tool.ToolConfig
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolIndex
import com.hana.agent.core.uistate.ActionResult
import com.hana.agent.core.uistate.Interfaces
import com.hana.agent.core.uistate.ScreenPort
import com.hana.agent.core.uistate.ScreenSnapshot
import com.hana.agent.core.uistate.Surface
import com.hana.agent.core.uistate.UiElement
import com.hana.agent.core.uistate.session
import com.hana.agent.core.vision.JvmTestImageOps
import com.hana.agent.core.visual.Recall
import com.hana.agent.core.visual.Shots
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ZoomRegionTool] 局部高精度裁剪、历史截图召回、只读锁屏复用与异常拦截测试。 */
class ZoomRegionToolTest {

    private class TestScreen(
        private val rawPng: ByteArray?,
        var protectedPage: Boolean = false,
    ) : ScreenPort {
        var screenshotCalls = 0
        var snapshotCalls = 0
        private val el = UiElement(
            ref = "@e1",
            role = "button",
            name = "买单",
            centerX = 100,
            centerY = 100,
            bounds = listOf(0, 0, 200, 200),
            resourceId = null,
            clickable = true,
            editable = false,
            scrollable = false,
        )

        override suspend fun snapshot(): ScreenSnapshot {
            snapshotCalls++
            return ScreenSnapshot("s$snapshotCalls", "com.test", listOf(el), 200, 400, protected = protectedPage)
        }

        override suspend fun screenshotPng(): ByteArray? {
            screenshotCalls++
            return rawPng
        }

        override suspend fun isProtected(): Boolean = protectedPage
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

    private fun newContext(
        screen: ScreenPort,
        visionEnabled: Boolean = true,
        withImageOps: Boolean = true,
        shots: Shots = Shots(),
    ): ToolContext {
        val cfg = ToolConfig(
            visionEnabled = visionEnabled,
            skills = null,
            recordFrames = false,
            vision = null,
            inlineScreen = false,
            adaptiveVision = false,
            memory = null,
            profile = null,
            utterances = null,
            conversationId = null,
            imageOps = if (withImageOps) JvmTestImageOps else null,
        )
        val ctx = ToolContext(cfg, EvidenceLog(), ToolIndex(emptyList()), null, shots)
        val surface = Surface(PHONE_FAMILY, screen, { it }, "packageName")
        Interfaces.attach(ctx, listOf(surface), stableRefs = true)
        return ctx
    }

    private fun makeTestPng(width: Int = 200, height: Int = 400): ByteArray =
        JvmTestImageOps.encode(width, height) { x, y ->
            val r = (x * 255) / width
            val g = (y * 255) / height
            (0xFF shl 24) or (r shl 16) or (g shl 8) or 0x80
        }

    @Test
    fun definitionLoadsPromptTemplateAndChecksUsability() {
        val tool = ZoomRegionTool(PHONE_FAMILY)
        val def = tool.def
        assertEquals("phone_zoom_region", def.name)
        assertTrue(def.description.contains("phone_tap"))
        assertFalse(def.description.contains("{{tap}}"))
        assertEquals(listOf("x1", "y1", "x2", "y2"), def.required)

        val screen = TestScreen(makeTestPng())
        assertTrue(tool.available(newContext(screen, visionEnabled = true, withImageOps = true)))
        assertFalse(tool.available(newContext(screen, visionEnabled = false, withImageOps = true)))
        assertFalse(tool.available(newContext(screen, visionEnabled = true, withImageOps = false)))
    }

    @Test
    fun cropsExactNormalizedRegionFromHighResCurrentScreen() = runTest {
        val rawPng = makeTestPng(200, 400)
        val screen = TestScreen(rawPng)
        val ctx = newContext(screen)
        val tool = ZoomRegionTool(PHONE_FAMILY)
        val args = Args(tool.def, Json.parseToJsonElement("""{"x1":250,"y1":500,"x2":750,"y2":999}""").jsonObject)

        val exec = tool.execute(args, ctx)
        assertTrue(exec.content["ok"]!!.jsonPrimitive.boolean)
        assertEquals(200, exec.content["sourceWidth"]!!.jsonPrimitive.int)
        assertEquals(400, exec.content["sourceHeight"]!!.jsonPrimitive.int)
        assertEquals(1, exec.images.size)

        val croppedSize = JvmTestImageOps.size(exec.images.single())
        assertEquals(100, croppedSize.first)
        assertEquals(200, croppedSize.second)
        assertEquals(croppedSize.first, exec.content["croppedWidth"]!!.jsonPrimitive.int)
        assertEquals(croppedSize.second, exec.content["croppedHeight"]!!.jsonPrimitive.int)
    }

    @Test
    fun reusesSingleFrozenScreenshotInsideWithFrozenContext() = runTest {
        val rawPng = makeTestPng(200, 400)
        val screen = TestScreen(rawPng)
        val ctx = newContext(screen)
        val tool = ZoomRegionTool(PHONE_FAMILY)

        ctx.session.withFrozenContext(captureScreenshot = true) {
            val exec1 = tool.execute(
                Args(tool.def, Json.parseToJsonElement("""{"x1":0,"y1":0,"x2":500,"y2":500}""").jsonObject),
                ctx,
            )
            val exec2 = tool.execute(
                Args(tool.def, Json.parseToJsonElement("""{"x1":500,"y1":500,"x2":999,"y2":999}""").jsonObject),
                ctx,
            )

            assertTrue(exec1.content["ok"]!!.jsonPrimitive.boolean)
            assertTrue(exec1.content["frozen"]!!.jsonPrimitive.boolean)
            assertTrue(exec2.content["ok"]!!.jsonPrimitive.boolean)
            assertTrue(exec2.content["frozen"]!!.jsonPrimitive.boolean)
            assertEquals(1, screen.screenshotCalls)
        }
    }

    @Test
    fun cropsFromRecalledScreenshotIdAndHandlesMissingId() = runTest {
        val shots = Shots()
        val oldId = shots.next()
        shots.remember(Recall(oldId, step = 1, app = "com.shop", png = makeTestPng(100, 100), elementsText = ""))

        val screen = TestScreen(rawPng = null)
        val ctx = newContext(screen, shots = shots)
        val tool = ZoomRegionTool(PHONE_FAMILY)

        val okExec = tool.execute(
            Args(tool.def, Json.parseToJsonElement("""{"x1":100,"y1":100,"x2":600,"y2":600,"screenshotId":$oldId}""").jsonObject),
            ctx,
        )
        assertTrue(okExec.content["ok"]!!.jsonPrimitive.boolean)
        assertEquals(oldId, okExec.content["sourceScreenshotId"]!!.jsonPrimitive.int)
        assertEquals(1, okExec.images.size)
        assertEquals(0, screen.screenshotCalls)

        val missingExec = tool.execute(
            Args(tool.def, Json.parseToJsonElement("""{"x1":100,"y1":100,"x2":600,"y2":600,"screenshotId":9999}""").jsonObject),
            ctx,
        )
        assertFalse(missingExec.content["ok"]!!.jsonPrimitive.boolean)
        assertEquals("screenshot_not_found", missingExec.content["error"]!!.jsonPrimitive.content)
        assertTrue(missingExec.images.isEmpty())
    }

    @Test
    fun rejectsInvertedBoxAndBlocksProtectedScreens() = runTest {
        val screen = TestScreen(makeTestPng(), protectedPage = true)
        val ctx = newContext(screen)
        val tool = ZoomRegionTool(PHONE_FAMILY)

        val badArg = runCatching {
            tool.execute(
                Args(tool.def, Json.parseToJsonElement("""{"x1":600,"y1":200,"x2":400,"y2":800}""").jsonObject),
                ctx,
            )
        }.exceptionOrNull()
        assertTrue(badArg is ArgError)

        val protectedExec = tool.execute(
            Args(tool.def, Json.parseToJsonElement("""{"x1":100,"y1":100,"x2":500,"y2":500}""").jsonObject),
            ctx,
        )
        assertFalse(protectedExec.content["ok"]!!.jsonPrimitive.boolean)
        assertEquals("protected_content", protectedExec.content["error"]!!.jsonPrimitive.content)
        assertTrue(protectedExec.content["protected"]!!.jsonPrimitive.boolean)
        assertTrue(protectedExec.images.isEmpty())
        assertEquals(0, screen.screenshotCalls)
    }
}

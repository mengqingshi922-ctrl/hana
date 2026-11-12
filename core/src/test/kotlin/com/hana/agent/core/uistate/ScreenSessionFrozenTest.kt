package com.hana.agent.core.uistate

import com.hana.agent.core.access.AppAccessPolicy
import com.hana.agent.core.access.AppAccessState
import com.hana.agent.core.cap.phone.PHONE_FAMILY
import com.hana.agent.core.visual.Shots
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ScreenSession] 只读锁屏模式（`withFrozenContext` / `freezePageContext` / `screenshotPngCached`）单元测试。 */
class ScreenSessionFrozenTest {

    private class CountingScreen(
        var emptyElements: Boolean = false,
    ) : ScreenPort {
        var snapshotCalls = 0
        var peekCalls = 0
        var screenshotCalls = 0
        var ocrCalls = 0
        var revision = 0L
        var protectedFlag = false
        var pngBytes: ByteArray? = byteArrayOf(10, 20, 30, 40)

        private val el = UiElement(
            ref = "@e1",
            role = "text",
            name = "价格 ¥199",
            centerX = 200,
            centerY = 400,
            bounds = listOf(100, 360, 300, 440),
            resourceId = "id/price",
            clickable = false,
            editable = false,
            scrollable = false,
        )

        override val appAccess: AppAccessPolicy = AppAccessPolicy { AppAccessState(revision = revision) }

        override suspend fun snapshot(): ScreenSnapshot {
            snapshotCalls++
            val elements = if (emptyElements) emptyList() else listOf(el)
            return ScreenSnapshot("snap-$snapshotCalls", "com.shop.app", elements, 1080, 2400, protected = protectedFlag)
        }

        override suspend fun peek(): ScreenSnapshot {
            peekCalls++
            val elements = if (emptyElements) emptyList() else listOf(el)
            return ScreenSnapshot("peek-$peekCalls", "com.shop.app", elements, 1080, 2400, protected = protectedFlag)
        }

        override suspend fun screenshotPng(): ByteArray? {
            screenshotCalls++
            return pngBytes
        }

        override suspend fun readVisibleText(): List<VisibleTextLine> {
            ocrCalls++
            return listOf(VisibleTextLine("价格 ¥199", listOf(100, 360, 300, 440)))
        }

        override suspend fun isProtected(): Boolean = protectedFlag
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
        override suspend fun listApps() = listOf("com.shop.app" to "Shop")
    }

    private fun newSession(screen: ScreenPort): ScreenSession = ScreenSession(
        surface = Surface(PHONE_FAMILY, screen, { it }, "packageName"),
        stableRefs = true,
        visionEnabled = true,
        recordFrames = false,
        lastRead = { null },
        shots = Shots(),
    )

    @Test
    fun withFrozenContextReusesSnapshotPeekAndScreenshotWithoutDuplicateDumps() = runTest {
        val screen = CountingScreen(emptyElements = true)
        val session = newSession(screen)
        assertFalse(session.isFrozen)

        var frozenId: String? = null
        session.withFrozenContext(captureScreenshot = true) {
            assertTrue(session.isFrozen)
            // 进入时尚无基准快照：捕获 1 次 snapshot 与 1 次 screenshot
            assertEquals(1, screen.snapshotCalls)
            assertEquals(1, screen.screenshotCalls)
            val baseId = session.lastSnapshot?.snapshotId
            assertNotNull(baseId)
            frozenId = baseId

            // 锁屏期间多次读屏与截屏均直接命中冻结缓存
            val snap1 = session.snapshot()
            val snap2 = session.snapshot()
            val peek1 = session.peek()
            val png1 = session.screenshotPngCached()
            val png2 = session.screenshotPngCached()

            assertEquals(baseId, snap1.snapshotId)
            assertEquals(baseId, snap2.snapshotId)
            assertEquals(baseId, peek1?.snapshotId)
            assertSame(png1, png2)
            assertEquals(1, screen.snapshotCalls)
            assertEquals(0, screen.peekCalls)
            assertEquals(1, screen.screenshotCalls)

            // 验证 observe 在冻结期复用树分析/OCR 结果与同一帧截图
            val obs1 = session.observe(withImage = true, recordFrame = false)
            val obs2 = session.observe(withImage = true, recordFrame = false)
            assertEquals(baseId, obs1.content["snapshotId"]!!.jsonPrimitive.content)
            assertEquals(baseId, obs2.content["snapshotId"]!!.jsonPrimitive.content)
            assertEquals(1, screen.snapshotCalls)
            assertEquals(1, screen.screenshotCalls)
            assertEquals(1, screen.ocrCalls)
        }

        // 最外层退出后解冻：后续 snapshot 和 screenshot 恢复实时抓取
        assertFalse(session.isFrozen)
        val freshSnap = session.snapshot()
        val freshPng = session.screenshotPngCached()
        assertNotNull(freshPng)
        assertEquals(2, screen.snapshotCalls)
        assertNotEquals(frozenId, freshSnap.snapshotId)
        assertEquals(2, screen.screenshotCalls)
    }

    @Test
    fun nestedWithFrozenContextKeepsLockUntilOutermostExits() = runTest {
        val screen = CountingScreen()
        val session = newSession(screen)

        session.withFrozenContext {
            assertTrue(session.isFrozen)
            val png1 = session.screenshotPngCached()
            assertEquals(1, screen.snapshotCalls)
            assertEquals(1, screen.screenshotCalls)

            session.withFrozenContext {
                assertTrue(session.isFrozen)
                val png2 = session.screenshotPngCached()
                assertSame(png1, png2)
                assertEquals(1, screen.screenshotCalls)
            }

            // 内层退出后依然保持冻结
            assertTrue(session.isFrozen)
            val png3 = session.screenshotPngCached()
            assertSame(png1, png3)
            assertEquals(1, screen.screenshotCalls)
        }

        assertFalse(session.isFrozen)
    }

    @Test
    fun accessRevisionChangeClearsFrozenCacheAndProtectedScreenSkipsScreenshot() = runTest {
        val screen = CountingScreen()
        val session = newSession(screen)

        session.freezePageContext(
            snapshot = screen.snapshot(),
            screenshotPng = byteArrayOf(7, 8, 9),
        )
        assertTrue(session.isFrozen)
        assertNotNull(session.screenshotPngCached())

        // 应用权限修订版本号变化后 checkAppAccess 必须清空冻结快照与截图缓存
        screen.revision = 42L
        session.checkAppAccess()
        assertNull(session.lastSnapshot)

        // 若页面受系统隐私保护，captureScreenshot=true 也不得缓存截图
        session.unfreezePageContext()
        screen.protectedFlag = true
        session.withFrozenContext(captureScreenshot = true) {
            assertNull(session.screenshotPngCached())
        }
    }
}

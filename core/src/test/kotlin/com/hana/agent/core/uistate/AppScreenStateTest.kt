package com.hana.agent.core.uistate

import com.hana.agent.core.tool.FakeScreen
import com.hana.agent.core.visual.Shots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppScreenStateTest {

    private fun createSession(screen: ScreenPort = FakeScreen()): ScreenSession {
        val surface = Surface(
            family = ScreenFamily("phone", "p"),
            screen = screen,
            declaredTarget = { it },
            targetNote = "package"
        )
        return ScreenSession(
            surface = surface,
            stableRefs = true,
            visionEnabled = false,
            recordFrames = false,
            lastRead = { null },
            shots = Shots()
        )
    }

    private fun createSnapshot(
        pkg: String,
        elements: List<UiElement> = listOf(
            UiElement("@e1", "button", "确定", 100, 100, listOf(0, 0, 200, 200), "id/btn_ok", true, false, false),
            UiElement("@e2", "edit", "搜索框", 100, 300, listOf(0, 200, 200, 400), "id/et_search", false, true, false)
        )
    ): ScreenSnapshot {
        return ScreenSnapshot("snap_${pkg}_1", pkg, elements, 1080, 2400)
    }

    @Test
    fun testMultiAppSessionStateStacking() {
        val session = createSession()
        val snapMap = createSnapshot("com.autonavi.minimap")
        val snapWeChat = createSnapshot("com.tencent.mm")

        session.saveAppState("com.autonavi.minimap", snapMap)
        session.saveAppState("com.tencent.mm", snapWeChat)

        val stack = session.appSessionStack
        assertEquals(2, stack.size)
        assertTrue(stack.containsKey("com.autonavi.minimap"))
        assertTrue(stack.containsKey("com.tencent.mm"))

        val stateMap = session.getAppState("com.autonavi.minimap")
        assertNotNull(stateMap)
        assertEquals("com.autonavi.minimap", stateMap?.packageName)
        assertEquals(snapMap, stateMap?.lastSnapshot)

        val stateWeChat = session.getAppState("com.tencent.mm")
        assertNotNull(stateWeChat)
        assertEquals("com.tencent.mm", stateWeChat?.packageName)
        assertEquals(snapWeChat, stateWeChat?.lastSnapshot)
    }

    @Test
    fun testSnapshotAndRefCachePreservationOnAppSwitch() {
        val stabilizer = RefStabilizer("s")
        val snap1 = createSnapshot(
            "com.autonavi.minimap",
            listOf(
                UiElement("@e1", "button", "导航", 100, 100, listOf(0, 0, 200, 200), "id/nav", true, false, false),
                UiElement("@e2", "button", "路线", 300, 100, listOf(200, 0, 400, 200), "id/route", true, false, false)
            )
        )
        val model1 = stabilizer.next(snap1)
        assertEquals("@e1", model1.elements.first { it.name == "导航" }.ref)
        assertEquals("@e2", model1.elements.first { it.name == "路线" }.ref)

        // 导出此时高德的状态
        val exportedState = stabilizer.exportState()
        assertNotNull(exportedState)
        assertEquals(model1, exportedState.latest)

        // 切换到另一个应用微信并生成新的元素映射
        val snap2 = createSnapshot(
            "com.tencent.mm",
            listOf(
                UiElement("@e1", "button", "通讯录", 50, 50, listOf(0, 0, 100, 100), "id/contacts", true, false, false),
                UiElement("@e2", "button", "发现", 150, 50, listOf(100, 0, 200, 100), "id/find", true, false, false)
            )
        )
        val model2 = stabilizer.next(snap2)
        assertEquals("@e3", model2.elements.first { it.name == "通讯录" }.ref)
        assertEquals("@e4", model2.elements.first { it.name == "发现" }.ref)

        // 恢复高德状态
        stabilizer.restoreState(exportedState)
        assertEquals(exportedState.count, stabilizer.exportState().count)
        assertEquals(exportedState.latest, stabilizer.exportState().latest)
        assertEquals(exportedState.nextRef, stabilizer.exportState().nextRef)
    }

    @Test
    fun testIncrementalWakeupViaPageSignatureGenerationCheck() {
        val session = createSession()
        val snap = createSnapshot("com.test.app")
        val sig1 = session.computeSignature(snap)
        val sig2 = session.computeSignature(snap)
        assertEquals(sig1, sig2)

        val snapDifferent = createSnapshot(
            "com.test.app",
            listOf(UiElement("@e1", "text", "全新的详情页标题", 50, 50, listOf(0, 0, 100, 100), null, false, false, false))
        )
        val sigDifferent = session.computeSignature(snapDifferent)
        assertTrue("Different screens must yield different signatures", sig1 != sigDifferent)

        session.saveAppState("com.test.app", snap)
        assertTrue(session.restoreAppStateIfAvailable("com.test.app"))
        assertFalse(session.restoreAppStateIfAvailable("com.unknown.app"))
    }

    @Test
    fun testAppScreenStateLruEvictionPolicy() {
        val session = createSession()
        for (i in 1..6) {
            val pkg = "com.app$i"
            session.saveAppState(pkg, createSnapshot(pkg))
        }

        val stack = session.appSessionStack
        assertEquals(5, stack.size)
        // app1 是最旧的，应该被平滑淘汰
        assertNull(session.getAppState("com.app1"))
        // app2 到 app6 应完整保留
        for (i in 2..6) {
            assertNotNull(session.getAppState("com.app$i"))
        }
    }
}

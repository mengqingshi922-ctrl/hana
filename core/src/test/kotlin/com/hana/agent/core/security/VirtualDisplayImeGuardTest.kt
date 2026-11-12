package com.hana.agent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayImeGuardTest {

    @Test
    fun android14PlusVirtualDisplaySuppressesImeAndPrefersShortcutAndPasteKey() {
        val req = VirtualDisplayImeRequest(
            sdkInt = 34,
            displayId = 2,
            isVirtualDisplay = true,
            isPasswordField = false,
            inWebView = false,
            hasAccessibilityNode = true,
        )
        val plan = VirtualDisplayImeGuard.decideInputPlan(req)

        assertTrue(plan.guardActive)
        assertTrue(plan.suppressSoftIme)
        assertTrue(plan.avoidTapToFocusImePopup)
        assertFalse(plan.imeAllowed)
        assertEquals(VirtualDisplayInputPath.SHORTCUT, plan.primaryPath)
        assertTrue(VirtualDisplayInputPath.PASTE_KEY in plan.fallbackPaths)
        assertTrue(VirtualDisplayInputPath.CLIPBOARD in plan.fallbackPaths)
        assertFalse(VirtualDisplayInputPath.IME in plan.orderedPaths)
        assertTrue(plan.clearClipboardAfterPaste)
    }

    @Test
    fun android14PlusVirtualDisplayWebViewPrefersPasteKeyAndClipboard() {
        val req = VirtualDisplayImeRequest(
            sdkInt = 35,
            displayId = 3,
            isVirtualDisplay = true,
            isPasswordField = false,
            inWebView = true,
            hasAccessibilityNode = true,
        )
        val plan = VirtualDisplayImeGuard.decideInputPlan(req)

        assertTrue(plan.guardActive)
        assertTrue(plan.suppressSoftIme)
        assertFalse(plan.imeAllowed)
        assertEquals(VirtualDisplayInputPath.PASTE_KEY, plan.primaryPath)
        assertTrue(VirtualDisplayInputPath.CLIPBOARD in plan.fallbackPaths)
        assertFalse(VirtualDisplayInputPath.IME in plan.orderedPaths)
        assertTrue(plan.clearClipboardAfterPaste)
    }

    @Test
    fun passwordFieldOnAndroid14VirtualDisplayNeverUsesClipboardOrIme() {
        val nativePwd = VirtualDisplayImeRequest(
            sdkInt = 36,
            displayId = 4,
            isVirtualDisplay = true,
            isPasswordField = true,
            inWebView = false,
            hasAccessibilityNode = true,
        )
        val nativePlan = VirtualDisplayImeGuard.decideInputPlan(nativePwd)
        assertEquals(VirtualDisplayInputPath.SHORTCUT, nativePlan.primaryPath)
        assertEquals(listOf(VirtualDisplayInputPath.HAND_TO_USER), nativePlan.fallbackPaths)
        assertFalse(nativePlan.clearClipboardAfterPaste)
        assertFalse(nativePlan.imeAllowed)

        val webPwd = nativePwd.copy(inWebView = true)
        val webPlan = VirtualDisplayImeGuard.decideInputPlan(webPwd)
        assertEquals(VirtualDisplayInputPath.HAND_TO_USER, webPlan.primaryPath)
        assertFalse(webPlan.imeAllowed)
        assertFalse(VirtualDisplayImeGuard.isPathPermitted(VirtualDisplayInputPath.CLIPBOARD, webPwd))
        assertFalse(VirtualDisplayImeGuard.isPathPermitted(VirtualDisplayInputPath.PASTE_KEY, webPwd))
        assertFalse(VirtualDisplayImeGuard.isPathPermitted(VirtualDisplayInputPath.IME, webPwd))
    }

    @Test
    fun physicalDisplayAndPreAndroid14AllowImeFallback() {
        val physical = VirtualDisplayImeRequest(sdkInt = 35, displayId = 0, isVirtualDisplay = false)
        val physicalPlan = VirtualDisplayImeGuard.decideInputPlan(physical)
        assertFalse(physicalPlan.guardActive)
        assertFalse(physicalPlan.suppressSoftIme)
        assertTrue(physicalPlan.imeAllowed)
        assertTrue(VirtualDisplayImeGuard.isPathPermitted(VirtualDisplayInputPath.IME, physical))

        val android13Virtual = VirtualDisplayImeRequest(sdkInt = 33, displayId = 2, isVirtualDisplay = true)
        val a13Plan = VirtualDisplayImeGuard.decideInputPlan(android13Virtual)
        assertFalse(a13Plan.guardActive)
        assertTrue(a13Plan.imeAllowed)
    }
}

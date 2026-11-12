package com.hana.agent.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeCompactTest {

    private fun sampleSnapshot(withWindows: Boolean = true): ScreenSnapshot {
        val buyBtn = UiTreeNode(
            role = "Button",
            bounds = listOf(120, 840, 340, 910),
            ref = "@e12",
            text = "立即购买",
            resourceId = "com.shop:id/btn_buy",
            clickable = true,
            enabled = true,
        )
        val disabledBtn = UiTreeNode(
            role = "Button",
            bounds = listOf(400, 840, 620, 910),
            ref = "@e13",
            text = "暂不可用",
            clickable = false,
            enabled = false,
        )
        val checkNode = UiTreeNode(
            role = "CheckBox",
            bounds = listOf(40, 700, 120, 760),
            ref = "@e14",
            text = "同意协议",
            checkable = true,
            checked = true,
        )
        val inputNode = UiTreeNode(
            role = "EditText",
            bounds = listOf(40, 200, 960, 280),
            ref = "@e15",
            hintText = "请输入备注",
            editable = true,
            focused = true,
        )
        val offscreenNoise = UiTreeNode(
            role = "View",
            bounds = listOf(1200, 2600, 1300, 2700),
        )
        val wrapper = UiTreeNode(
            role = "FrameLayout",
            bounds = listOf(0, 0, 1080, 2400),
            children = listOf(
                UiTreeNode(
                    role = "LinearLayout",
                    bounds = listOf(0, 180, 1080, 950),
                    children = listOf(inputNode, checkNode, buyBtn, disabledBtn, offscreenNoise),
                ),
            ),
        )
        val window = UiWindowTree(
            id = 1,
            type = "application",
            layer = 0,
            title = "商品详情",
            packageName = "com.shop",
            active = true,
            focused = true,
            nodes = listOf(wrapper),
        )
        val elements = listOf(
            UiElement("@e12", "Button", "立即购买", 230, 875, listOf(120, 840, 340, 910), "com.shop:id/btn_buy", true, false, false),
            UiElement("@e13", "Button", "暂不可用", 510, 875, listOf(400, 840, 620, 910), null, false, false, false, states = setOf(UiStates.DISABLED)),
            UiElement("@e14", "CheckBox", "同意协议", 80, 730, listOf(40, 700, 120, 760), null, false, false, false, states = setOf(UiStates.CHECKED)),
            UiElement("@e15", "EditText", "", 500, 240, listOf(40, 200, 960, 280), null, false, true, false, hint = true, focused = true, placeholder = "请输入备注"),
        )
        return ScreenSnapshot(
            snapshotId = "s1",
            packageName = "com.shop",
            elements = elements,
            width = 1080,
            height = 2400,
            windows = if (withWindows) listOf(window) else emptyList(),
        )
    }

    @Test
    fun formatsCompactNodeWithNonDefaultStatesOnly() {
        val snap = sampleSnapshot()
        val compact = UiTreeCompact.format(snap, prune = false)

        assertTrue(compact.contains("[e12] btn \"立即购买\" #btn_buy [120,840,340,910] {clk}"))
        assertTrue(compact.contains("[e13] btn \"暂不可用\" [400,840,620,910] {disabled}"))
        assertTrue(compact.contains("[e14] chk \"同意协议\" [40,700,120,760] {checked}"))
        assertTrue(compact.contains("[e15] input hint=\"请输入备注\" [40,200,960,280] {edit,focus}"))
        // 默认值不应出现
        assertFalse(compact.contains("\"enabled\":true"))
        assertFalse(compact.contains("scrollable"))
    }

    @Test
    fun significantlyReducesSizeComparedToUiTreeJson() {
        val snap = sampleSnapshot()
        val jsonStr = UiTreeJson.windows(snap, prune = false).toString()
        val compactStr = UiTreeCompact.format(snap, prune = true)

        assertTrue("Expected compact ($compactStr) to be much shorter than JSON ($jsonStr)", compactStr.length < jsonStr.length * 0.5)
        assertTrue(UiTreeCompact.compressionRatio(snap, prune = true) >= 0.5)
    }

    @Test
    fun switchesCleanlyBetweenCompactAndJsonFormats() {
        val snap = sampleSnapshot()
        val jsonViaSwitch = UiTreeCompact.serialize(snap, UiTreeFormat.JSON, prune = true)
        val jsonDirect = UiTreeJson.windows(snap, prune = true).toString()
        assertEquals(jsonDirect, jsonViaSwitch)

        val compactViaSwitch = UiTreeCompact.serialize(snap, UiTreeFormat.COMPACT, prune = true)
        assertEquals(UiTreeCompact.format(snap, prune = true), compactViaSwitch)
    }

    @Test
    fun compactChangesTracksAddedUpdatedAndRemovedNodes() {
        val before = sampleSnapshot()
        val modifiedChild = before.windows.first().nodes.first().children.first().children.map { n ->
            if (n.ref == "@e14") n.copy(checked = false) else n
        } + UiTreeNode("Button", listOf(700, 840, 920, 910), ref = "@e16", text = "加入购物车", clickable = true)

        val updatedRoot = before.windows.first().nodes.first().let { root ->
            root.copy(children = listOf(root.children.first().copy(children = modifiedChild)))
        }
        val after = before.copy(windows = listOf(before.windows.first().copy(nodes = listOf(updatedRoot))))

        val diff = UiTreeCompact.changes(before, after, prune = false)
        assertTrue(diff.contains("~ w1/0/0/1"))
        assertTrue(diff.contains("{unchecked}"))
        assertTrue(diff.contains("+ w1/0/0/5"))
        assertTrue(diff.contains("加入购物车"))

        val shrinkDiff = UiTreeCompact.changes(after, before, prune = false)
        assertTrue(shrinkDiff.contains("- w1/0/0/5"))
    }

    @Test
    fun fallsBackToElementsWhenWindowsEmpty() {
        val snap = sampleSnapshot(withWindows = false)
        val compact = UiTreeCompact.format(snap)
        assertTrue(compact.contains("[e12] btn \"立即购买\" #btn_buy [120,840,340,910] {clk}"))
        assertTrue(compact.contains("[e15] input hint=\"请输入备注\" [40,200,960,280] {edit,focus}"))
    }
}

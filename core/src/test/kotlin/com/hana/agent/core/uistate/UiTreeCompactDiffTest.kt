package com.hana.agent.core.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeCompactDiffTest {

    private fun sampleSnapshot(
        buttonText: String = "立即购买",
        buttonChecked: Boolean = false,
        extraNodes: List<UiTreeNode> = emptyList(),
        pkg: String = "com.shop",
    ): ScreenSnapshot {
        val buyBtn = UiTreeNode(
            role = "Button",
            bounds = listOf(120, 840, 340, 910),
            ref = "@e12",
            text = buttonText,
            resourceId = "com.shop:id/btn_buy",
            clickable = true,
            checkable = true,
            checked = buttonChecked,
            enabled = true,
        )
        val checkNode = UiTreeNode(
            role = "CheckBox",
            bounds = listOf(40, 700, 120, 760),
            ref = "@e14",
            text = "同意协议",
            checkable = true,
            checked = true,
        )
        val children = listOf(buyBtn, checkNode) + extraNodes
        val wrapper = UiTreeNode(
            role = "LinearLayout",
            bounds = listOf(0, 0, 1080, 2400),
            children = children,
        )
        val window = UiWindowTree(
            id = 1,
            type = "application",
            layer = 0,
            title = "商品详情",
            packageName = pkg,
            active = true,
            focused = true,
            nodes = listOf(wrapper),
        )
        val elements = listOf(
            UiElement("@e12", "Button", buttonText, 230, 875, listOf(120, 840, 340, 910), "com.shop:id/btn_buy", true, false, false),
            UiElement("@e14", "CheckBox", "同意协议", 80, 730, listOf(40, 700, 120, 760), null, false, false, false, states = setOf(UiStates.CHECKED)),
        ) + extraNodes.mapIndexed { idx, n ->
            UiElement(n.ref ?: "@extra$idx", n.role, n.text ?: "", 100, 100, listOf(0, 0, 100, 100), null, true, false, false)
        }
        return ScreenSnapshot(
            snapshotId = "s1",
            packageName = pkg,
            elements = elements,
            width = 1080,
            height = 2400,
            windows = listOf(window),
        )
    }

    private fun toModel(screen: ScreenSnapshot, st: RefStabilizer): ModelSnapshot {
        return st.next(screen)
    }

    @Test
    fun compactFullOutputWhenNoBase() {
        val st = RefStabilizer()
        val current = toModel(sampleSnapshot(), st)
        val update = InterfaceUpdate.between(null, current, baseVisible = true, treeFormat = UiTreeFormat.COMPACT)

        assertTrue(update["full"]!!.jsonPrimitive.boolean)
        assertNull(update["treeChanges"])
        val treeStr = update["tree"]!!.jsonPrimitive.content
        assertTrue(treeStr.contains("#w1 application"))
        assertTrue(treeStr.contains("立即购买"))
        assertTrue(treeStr.contains("同意协议"))
    }

    @Test
    fun compactDiffWhenSingleNodeChanged() {
        val st = RefStabilizer()
        val base = toModel(sampleSnapshot(buttonChecked = false), st)
        val current = toModel(sampleSnapshot(buttonChecked = true), st)

        val update = InterfaceUpdate.between(base, current, baseVisible = true, treeFormat = UiTreeFormat.COMPACT)
        assertFalse(update["full"]!!.jsonPrimitive.boolean)
        assertNull(update["tree"])
        assertNotNull(update["treeChanges"])

        val diff = update["treeChanges"]!!.jsonPrimitive.content
        assertTrue("Expected diff to contain node update marker '~', was: $diff", diff.contains("~ w1/0/0"))
        assertTrue("Expected diff to contain checked state, was: $diff", diff.contains("checked"))
    }

    @Test
    fun compactDiffWhenNodeAddedAndRemoved() {
        val st = RefStabilizer()
        val extra = UiTreeNode("Button", listOf(500, 840, 700, 910), ref = "@e15", text = "领券", clickable = true)
        val base = toModel(sampleSnapshot(extraNodes = emptyList()), st)
        val current = toModel(sampleSnapshot(extraNodes = listOf(extra)), st)

        val updateAdded = InterfaceUpdate.between(base, current, baseVisible = true, treeFormat = UiTreeFormat.COMPACT)
        assertFalse(updateAdded["full"]!!.jsonPrimitive.boolean)
        val addedDiff = updateAdded["treeChanges"]!!.jsonPrimitive.content
        assertTrue("Expected diff to contain added node marker '+', was: $addedDiff", addedDiff.contains("+ w1/0/2"))
        assertTrue("Expected diff to contain '领券', was: $addedDiff", addedDiff.contains("领券"))

        val updateRemoved = InterfaceUpdate.between(current, base, baseVisible = true, treeFormat = UiTreeFormat.COMPACT)
        assertFalse(updateRemoved["full"]!!.jsonPrimitive.boolean)
        val removedDiff = updateRemoved["treeChanges"]!!.jsonPrimitive.content
        assertTrue("Expected diff to contain removed node marker '-', was: $removedDiff", removedDiff.contains("- w1/0/2"))
    }

    @Test
    fun compactDiffFallsBackToFullWhenChangesExceedRatio() {
        val st = RefStabilizer()
        val base = toModel(sampleSnapshot(), st)
        // 创建大量节点变更使 diff 大小超过全量树的 50%
        val manyNodes = (1..20).map { i ->
            UiTreeNode("TextView", listOf(0, i * 50, 100, i * 50 + 40), ref = "@e10$i", text = "全新内容项目番号$i")
        }
        val current = toModel(sampleSnapshot(extraNodes = manyNodes), st)

        val update = InterfaceUpdate.between(base, current, baseVisible = true, treeFormat = UiTreeFormat.COMPACT)
        assertTrue("Changes should exceed ratio and fallback to full", update["full"]!!.jsonPrimitive.boolean)
        assertNotNull(update["tree"])
        assertNull(update["treeChanges"])
    }

    @Test
    fun backwardCompatibilityJsonFormat() {
        val st = RefStabilizer()
        val current = toModel(sampleSnapshot(), st)
        val update = InterfaceUpdate.between(null, current, baseVisible = true, treeFormat = UiTreeFormat.JSON)

        assertTrue(update["full"]!!.jsonPrimitive.boolean)
        assertTrue("JSON format tree must be JsonArray", update["tree"] is JsonArray)
    }
}

package com.hana.agent.core.uistate

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreePrunerTest {

    @Test
    fun testQuantizeBounds() {
        // 全屏测试
        val full = UiTreePruner.quantizeBounds(listOf(0, 0, 1080, 2400), 1080, 2400)
        assertEquals(listOf(0, 0, 1000, 1000), full)

        // 中心区域测试
        val center = UiTreePruner.quantizeBounds(listOf(270, 600, 810, 1800), 1080, 2400)
        assertEquals(listOf(250, 250, 750, 750), center)

        // 越界夹逼测试
        val overflow = UiTreePruner.quantizeBounds(listOf(-50, -100, 1200, 2600), 1080, 2400)
        assertEquals(listOf(0, 0, 1000, 1000), overflow)

        // 异常长度安全处理
        val malformed = listOf(10, 20)
        assertEquals(malformed, UiTreePruner.quantizeBounds(malformed, 1080, 2400))
    }

    @Test
    fun testPruneViewportOutsideNodes() {
        val w = 1080
        val h = 2400

        // 屏幕外的无用空白节点 (完全在屏幕下方)
        val outsideNode = UiTreeNode(
            role = "View",
            bounds = listOf(0, 2500, 1080, 2700),
            text = null
        )
        assertNull(UiTreePruner.pruneViewport(outsideNode, w, h))

        // 屏幕外但有重要文本的节点，应予保留
        val outsideWithText = UiTreeNode(
            role = "TextView",
            bounds = listOf(0, 2500, 1080, 2700),
            text = "屏幕外待滚入的重要提示"
        )
        val keptOutside = UiTreePruner.pruneViewport(outsideWithText, w, h)
        assertNotNull(keptOutside)
        assertEquals("屏幕外待滚入的重要提示", keptOutside?.text)

        // 视口内正常节点保留
        val insideNode = UiTreeNode(
            role = "Button",
            bounds = listOf(100, 200, 500, 400),
            text = "确认"
        )
        assertNotNull(UiTreePruner.pruneViewport(insideNode, w, h))
    }

    @Test
    fun testPruneTinyZeroSizeNodes() {
        val w = 1080
        val h = 2400

        // 宽高均小于 5px 的无文本微小节点剔除
        val tinyNode = UiTreeNode(
            role = "View",
            bounds = listOf(100, 100, 102, 103), // 2x3 px
            text = null,
            clickable = false
        )
        assertNull(UiTreePruner.pruneViewport(tinyNode, w, h))

        // 宽高小于 5px 但可点击交互的节点保留
        val tinyClickable = UiTreeNode(
            role = "View",
            bounds = listOf(100, 100, 103, 103),
            clickable = true
        )
        assertNotNull(UiTreePruner.pruneViewport(tinyClickable, w, h))
    }

    @Test
    fun testCollapseSingleChildContainers() {
        // 构造三层嵌套空容器: FrameLayout -> LinearLayout -> RelativeLayout -> TargetButton
        val targetButton = UiTreeNode(
            role = "Button",
            bounds = listOf(100, 100, 300, 200),
            ref = "@e1",
            text = "立即购买",
            clickable = true
        )
        val relativeLayout = UiTreeNode(
            role = "RelativeLayout",
            bounds = listOf(100, 100, 300, 200),
            children = listOf(targetButton)
        )
        val linearLayout = UiTreeNode(
            role = "LinearLayout",
            bounds = listOf(100, 100, 300, 200),
            children = listOf(relativeLayout)
        )
        val frameLayout = UiTreeNode(
            role = "FrameLayout",
            bounds = listOf(100, 100, 300, 200),
            children = listOf(linearLayout)
        )

        val collapsed = UiTreePruner.collapseSingleChildContainers(frameLayout)

        // 折叠后应直接提升为最底层的 TargetButton
        assertEquals("Button", collapsed.role)
        assertEquals("@e1", collapsed.ref)
        assertEquals("立即购买", collapsed.text)
        assertTrue(collapsed.clickable)
        assertTrue(collapsed.children.isEmpty())
    }

    @Test
    fun testDoNotCollapseWhenContainerHasSemanticsOrMultipleChildren() {
        val child1 = UiTreeNode(role = "TextView", bounds = listOf(10, 10, 50, 50), text = "Item 1")
        val child2 = UiTreeNode(role = "TextView", bounds = listOf(60, 10, 100, 50), text = "Item 2")

        // 拥有多个子节点的容器不应折叠
        val multiContainer = UiTreeNode(
            role = "LinearLayout",
            bounds = listOf(0, 0, 200, 100),
            children = listOf(child1, child2)
        )
        val afterMulti = UiTreePruner.collapseSingleChildContainers(multiContainer)
        assertEquals(2, afterMulti.children.size)

        // 拥有自身可点击事件的单子节点容器不应被折叠消除
        val clickableContainer = UiTreeNode(
            role = "CardView",
            bounds = listOf(0, 0, 200, 100),
            clickable = true,
            children = listOf(child1)
        )
        val afterClickable = UiTreePruner.collapseSingleChildContainers(clickableContainer)
        assertEquals("CardView", afterClickable.role)
        assertTrue(afterClickable.clickable)
        assertEquals(1, afterClickable.children.size)
    }

    @Test
    fun testUiTreeJsonPrunedIntegration() {
        val target = UiTreeNode(
            role = "Button",
            bounds = listOf(540, 1200, 1080, 2400),
            ref = "@e_target",
            text = "下一步",
            clickable = true
        )
        val emptyContainer = UiTreeNode(
            role = "FrameLayout",
            bounds = listOf(540, 1200, 1080, 2400),
            children = listOf(target)
        )
        val screen = ScreenSnapshot(
            snapshotId = "s_prune",
            packageName = "com.test.prune",
            elements = emptyList(),
            width = 1080,
            height = 2400,
            windows = listOf(
                UiWindowTree(
                    id = 1,
                    type = "APPLICATION",
                    layer = 0,
                    title = "Main",
                    packageName = "com.test.prune",
                    active = true,
                    focused = true,
                    nodes = listOf(emptyContainer)
                )
            )
        )

        val unprunedJson = UiTreeJson.windows(screen, prune = false)
        val unprunedNode = unprunedJson[0].jsonObject["nodes"]!!.jsonArray[0].jsonObject
        assertEquals("FrameLayout", unprunedNode["role"]?.toString()?.replace("\"", ""))

        val prunedJson = UiTreeJson.prunedWindows(screen)
        val prunedNode = prunedJson[0].jsonObject["nodes"]!!.jsonArray[0].jsonObject
        // 空 FrameLayout 被折叠，直接提升为 Button
        assertEquals("Button", prunedNode["role"]?.toString()?.replace("\"", ""))
        assertEquals("@e_target", prunedNode["ref"]?.toString()?.replace("\"", ""))
        // 且 bounds 被归一化至 [0 ~ 1000]
        val bounds = prunedNode["bounds"]!!.jsonArray.map { it.toString().toInt() }
        assertEquals(listOf(500, 500, 1000, 1000), bounds)
    }
}

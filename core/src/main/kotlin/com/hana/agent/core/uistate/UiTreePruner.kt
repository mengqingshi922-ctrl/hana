package com.hana.agent.core.uistate

import kotlin.math.roundToInt

/** 扩展便捷属性，兼容 contentDescription 命名 */
val UiTreeNode.contentDescription: String? get() = description

/**
 * 无障碍树自适应视口拓扑剪枝、单子节点空容器折叠与相对坐标量化
 */
object UiTreePruner {

    /**
     * 将物理像素坐标归一化量化编码至 [0 ~ 1000]
     */
    fun quantizeBounds(bounds: List<Int>, w: Int, h: Int): List<Int> {
        if (bounds.size != 4 || w <= 0 || h <= 0) return bounds
        val l = bounds[0]
        val t = bounds[1]
        val r = bounds[2]
        val b = bounds[3]
        val xNorm1 = (l.toDouble() / w * 1000.0).roundToInt().coerceIn(0, 1000)
        val yNorm1 = (t.toDouble() / h * 1000.0).roundToInt().coerceIn(0, 1000)
        val xNorm2 = (r.toDouble() / w * 1000.0).roundToInt().coerceIn(0, 1000)
        val yNorm2 = (b.toDouble() / h * 1000.0).roundToInt().coerceIn(0, 1000)
        return listOf(xNorm1, yNorm1, xNorm2, yNorm2)
    }

    /**
     * 视口拓扑几何剪枝：
     * 1. 递归处理所有子节点；
     * 2. 剔除宽高均 < 5px 的无意义微小节点（若无文本且无存活子节点）；
     * 3. 剔除完全在 [0, 0, viewportWidth, viewportHeight] 视口之外且无文字/无存活子节点的节点。
     */
    fun pruneViewport(tree: UiTreeNode, viewportWidth: Int, viewportHeight: Int): UiTreeNode? {
        val prunedChildren = tree.children.mapNotNull { pruneViewport(it, viewportWidth, viewportHeight) }
        val hasChildren = prunedChildren.isNotEmpty()
        val hasText = !tree.text.isNullOrBlank() || !tree.description.isNullOrBlank() || !tree.hintText.isNullOrBlank()
        val isInteractive = tree.clickable || tree.scrollable || tree.editable || tree.checkable || tree.focused

        val bounds = tree.bounds
        val (l, t, r, b) = if (bounds.size == 4) bounds else listOf(0, 0, 0, 0)
        val width = r - l
        val height = b - t

        // 剔除微小零尺寸节点 (宽高均 < 5px)
        if (width < 5 && height < 5 && !hasText && !hasChildren && !isInteractive) {
            return null
        }

        // 剔除完全在视口之外的节点
        val isOutside = r <= 0 || l >= viewportWidth || b <= 0 || t >= viewportHeight
        if (isOutside && !hasText && !hasChildren && !isInteractive) {
            return null
        }

        return tree.copy(children = prunedChildren)
    }

    /**
     * 单子节点空容器折叠：
     * 若 node.children.size == 1 且自身 text.isNullOrEmpty() && description.isNullOrEmpty() &&
     * !clickable && !scrollable && !editable，将子节点提升替代父节点。
     */
    fun collapseSingleChildContainers(node: UiTreeNode): UiTreeNode {
        val collapsedChildren = node.children.map { collapseSingleChildContainers(it) }
        var current = if (collapsedChildren != node.children) node.copy(children = collapsedChildren) else node

        while (current.children.size == 1) {
            val hasContent = !current.text.isNullOrEmpty() ||
                !current.description.isNullOrEmpty() ||
                !current.hintText.isNullOrEmpty()
            val hasAction = current.clickable || current.scrollable || current.editable || current.checkable

            if (!hasContent && !hasAction) {
                val child = current.children.first()
                current = if (child.ref == null && current.ref != null) {
                    child.copy(ref = current.ref)
                } else {
                    child
                }
            } else {
                break
            }
        }
        return current
    }

    /**
     * 对节点进行全套剪枝、折叠与量化处理
     */
    fun pruneTree(node: UiTreeNode, viewportWidth: Int, viewportHeight: Int, quantize: Boolean = true): UiTreeNode? {
        val pruned = pruneViewport(node, viewportWidth, viewportHeight) ?: return null
        val collapsed = collapseSingleChildContainers(pruned)
        return if (quantize) quantizeNode(collapsed, viewportWidth, viewportHeight) else collapsed
    }

    fun quantizeNode(node: UiTreeNode, w: Int, h: Int): UiTreeNode {
        return node.copy(
            bounds = quantizeBounds(node.bounds, w, h),
            children = node.children.map { quantizeNode(it, w, h) }
        )
    }
}

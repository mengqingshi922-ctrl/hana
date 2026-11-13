package com.hana.agent.vision

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 预留的端侧神经网络图标检测委托接口，支持挂入 MNN / TFLite YOLOv9-tiny 等权重
 */
fun interface MnnIconDetectorDelegate {
    fun detect(bitmap: Bitmap, excludeNormBounds: List<IntArray>): List<IntArray>?
}

/**
 * 端侧视觉无字图标检测器 (EdgeIconDetector)：
 * - 参考 OmniParser 局部可交互孤立区块提取思路，专用于无障碍树匮乏（tree.poor）且 OCR 无文字标注的纯图标区域；
 * - 针对常见无文字交互元素（返回箭头、关闭叉号、设置齿轮、搜索放大镜、购物车、播放控制等）；
 * - 采用快速下采样网格梯度密度 + 8 邻域连通域聚类算法，耗时控制在 <15ms 以内，零网络开销；
 * - 自动根据尺寸区间（18dp~96dp 等效归一化比例）和宽高比过滤杂波，并根据已有文字框（excludeNormBounds）做 IoU 剔除；
 * - 输出符合标准归一化 [0..999] 区间的四元边界框 [left, top, right, bottom]，直接供画框标注与点击定位。
 */
object EdgeIconDetector {

    /** 快速检测采样的网格宽度 */
    private const val DEFAULT_GRID_W = 160

    /** 快速检测采样的网格高度 */
    private const val DEFAULT_GRID_H = 320

    /** 梯度边缘激活阈值（灰度亮度差） */
    private const val GRADIENT_THRESHOLD = 26

    /** 预留的深度学习推理委托（例如 MNN / TFLite YOLO 权重） */
    @Volatile
    var delegate: MnnIconDetectorDelegate? = null

    /**
     * 检测 Bitmap 中的无字图标候选框
     * @param bitmap 待检测的屏幕或降采样图像
     * @param excludeNormBounds 已有文字或 A11y 控件框的归一化坐标列表 [left, top, right, bottom] (0..999)
     * @return 归一化图标候选框列表，每个框为 IntArray(4): [left, top, right, bottom]
     */
    fun detectBounds(
        bitmap: Bitmap,
        excludeNormBounds: List<IntArray> = emptyList(),
    ): List<IntArray> {
        // 优先尝试外部注册的深度学习 MNN 推理委托
        val delegateResult = runCatching { delegate?.detect(bitmap, excludeNormBounds) }.getOrNull()
        if (delegateResult != null) {
            return delegateResult
        }

        val width = runCatching { bitmap.width }.getOrDefault(0)
        val height = runCatching { bitmap.height }.getOrDefault(0)
        if (width <= 0 || height <= 0) return emptyList()

        val pixels = IntArray(width * height)
        runCatching {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        }.onFailure {
            return emptyList()
        }

        return detectBounds(pixels, width, height, excludeNormBounds)
    }

    /**
     * 基于原始 ARGB 像素数组的高性能端侧梯度连通域检测
     * 该重载无需依赖 Android Graphics 运行时，可在 JVM 单元测试与端侧实时并发中零反射高速执行。
     */
    fun detectBounds(
        pixels: IntArray,
        width: Int,
        height: Int,
        excludeNormBounds: List<IntArray> = emptyList(),
    ): List<IntArray> {
        if (width <= 2 || height <= 2 || pixels.size < width * height) {
            return emptyList()
        }

        val gridW = min(DEFAULT_GRID_W, width)
        val gridH = min(DEFAULT_GRID_H, height)

        // 1. 抽取网格灰度图
        val lumaGrid = IntArray(gridW * gridH)
        for (gy in 0 until gridH) {
            val py = gy * (height - 1) / (gridH - 1).coerceAtLeast(1)
            val rowOffset = py * width
            val gridRowOffset = gy * gridW
            for (gx in 0 until gridW) {
                val px = gx * (width - 1) / (gridW - 1).coerceAtLeast(1)
                val c = pixels[rowOffset + px]
                // 快速灰度转换: (R*299 + G*587 + B*114) / 1000
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                lumaGrid[gridRowOffset + gx] = (r * 299 + g * 587 + b * 114) / 1000
            }
        }

        // 2. 网格梯度检测（绝对差分）标记边缘活跃点
        val edgeGrid = BooleanArray(gridW * gridH)
        for (gy in 1 until gridH - 1) {
            val rowOffset = gy * gridW
            for (gx in 1 until gridW - 1) {
                val idx = rowOffset + gx
                val current = lumaGrid[idx]
                val dx = abs(lumaGrid[idx + 1] - lumaGrid[idx - 1])
                val dy = abs(lumaGrid[idx + gridW] - lumaGrid[idx - gridW])
                if (dx + dy >= GRADIENT_THRESHOLD) {
                    edgeGrid[idx] = true
                }
            }
        }

        // 3. 8-邻域连通域聚类（BFS 队列遍历）
        val visited = BooleanArray(gridW * gridH)
        val queue = IntArray(gridW * gridH)
        val rawCandidates = mutableListOf<IntArray>()

        val dx8 = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val dy8 = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

        for (gy in 1 until gridH - 1) {
            val rowOffset = gy * gridW
            for (gx in 1 until gridW - 1) {
                val startIndex = rowOffset + gx
                if (!edgeGrid[startIndex] || visited[startIndex]) continue

                var head = 0
                var tail = 0
                queue[tail++] = startIndex
                visited[startIndex] = true

                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                var count = 0

                while (head < tail) {
                    val curr = queue[head++]
                    count++
                    val curX = curr % gridW
                    val curY = curr / gridW

                    if (curX < minX) minX = curX
                    if (curX > maxX) maxX = curX
                    if (curY < minY) minY = curY
                    if (curY > maxY) maxY = curY

                    for (d in 0 until 8) {
                        val nx = curX + dx8[d]
                        val ny = curY + dy8[d]
                        if (nx in 0 until gridW && ny in 0 until gridH) {
                            val nIdx = ny * gridW + nx
                            if (edgeGrid[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                // 4. 连通块尺寸与宽高比初筛
                val gridBlockW = maxX - minX + 1
                val gridBlockH = maxY - minY + 1

                // 映射到 0..999 归一化空间
                val normLeft = (minX * 999 / (gridW - 1).coerceAtLeast(1)).coerceIn(0, 999)
                val normRight = (maxX * 999 / (gridW - 1).coerceAtLeast(1)).coerceIn(0, 999)
                val normTop = (minY * 999 / (gridH - 1).coerceAtLeast(1)).coerceIn(0, 999)
                val normBottom = (maxY * 999 / (gridH - 1).coerceAtLeast(1)).coerceIn(0, 999)

                val normW = normRight - normLeft
                val normH = normBottom - normTop

                // 图标等效尺寸区间（归一化 12 到 320 之间，宽高比在 0.25 到 4.0 之间）
                if (count >= 3 &&
                    normW in 10..350 &&
                    normH in 10..350 &&
                    gridBlockW >= 2 &&
                    gridBlockH >= 2
                ) {
                    val aspect = normW.toFloat() / max(1, normH)
                    if (aspect in 0.25f..4.0f) {
                        // 稍微外扩 1% padding 保障边界框包裹完整图标
                        val padX = max(4, normW / 20)
                        val padY = max(4, normH / 20)
                        val boundedBox = intArrayOf(
                            max(0, normLeft - padX),
                            max(0, normTop - padY),
                            min(999, normRight + padX),
                            min(999, normBottom + padY),
                        )
                        rawCandidates.add(boundedBox)
                    }
                }
            }
        }

        // 5. 与已有文字/控件框 (excludeNormBounds) 做重叠与包含过滤
        val filteredByExclude = rawCandidates.filter { candidate ->
            !isExcludedByExisting(candidate, excludeNormBounds)
        }

        // 6. NMS 重叠去重（消除连通域边界抖动产生的重合候选）
        return nonMaxSuppression(filteredByExclude, iouThreshold = 0.35f)
    }

    private fun isExcludedByExisting(candidate: IntArray, excludeList: List<IntArray>): Boolean {
        if (excludeList.isEmpty()) return false
        val cLeft = candidate[0]
        val cTop = candidate[1]
        val cRight = candidate[2]
        val cBottom = candidate[3]
        val cArea = max(1, (cRight - cLeft + 1) * (cBottom - cTop + 1))

        for (ex in excludeList) {
            if (ex.size < 4) continue
            val eLeft = ex[0]
            val eTop = ex[1]
            val eRight = ex[2]
            val eBottom = ex[3]

            val iLeft = max(cLeft, eLeft)
            val iTop = max(cTop, eTop)
            val iRight = min(cRight, eRight)
            val iBottom = min(cBottom, eBottom)

            if (iLeft < iRight && iTop < iBottom) {
                val interArea = (iRight - iLeft + 1) * (iBottom - iTop + 1)
                // 候选框超过 30% 面积与已有文字框重合，说明属于该文字区域
                if (interArea.toFloat() / cArea > 0.30f) {
                    return true
                }
            }
        }
        return false
    }

    private fun nonMaxSuppression(boxes: List<IntArray>, iouThreshold: Float): List<IntArray> {
        if (boxes.size <= 1) return boxes
        // 按面积由大到小排序，若有包围关系的保留外接范围更完整者
        val sorted = boxes.sortedByDescending { box ->
            (box[2] - box[0] + 1) * (box[3] - box[1] + 1)
        }
        val selected = mutableListOf<IntArray>()

        for (current in sorted) {
            var overlap = false
            for (kept in selected) {
                if (computeIoU(current, kept) > iouThreshold) {
                    overlap = true
                    break
                }
            }
            if (!overlap) {
                selected.add(current)
            }
        }
        return selected
    }

    /**
     * 计算两个归一化边界框的 IoU（交并比）
     */
    fun computeIoU(b1: IntArray, b2: IntArray): Float {
        val iLeft = max(b1[0], b2[0])
        val iTop = max(b1[1], b2[1])
        val iRight = min(b1[2], b2[2])
        val iBottom = min(b1[3], b2[3])

        if (iLeft >= iRight || iTop >= iBottom) return 0f

        val interArea = (iRight - iLeft + 1) * (iBottom - iTop + 1)
        val a1 = (b1[2] - b1[0] + 1) * (b1[3] - b1[1] + 1)
        val a2 = (b2[2] - b2[0] + 1) * (b2[3] - b2[1] + 1)
        val unionArea = a1 + a2 - interArea
        return if (unionArea > 0) interArea.toFloat() / unionArea else 0f
    }
}

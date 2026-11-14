package com.hana.agent.vision

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 预留的端侧神经网络图标检测委托接口，支持挂入 MNN / TFLite YOLOv9-tiny 等权重
 */
fun interface MnnIconDetectorDelegate {
    fun detect(bitmap: Bitmap, excludeNormBounds: List<IntArray>): List<IntArray>?
}

/**
 * 带几何语义标签与置信度的端侧检测图标结果
 *
 * @property bounds 归一化 [0..999] 四元边界框 [left, top, right, bottom]
 * @property label 语义标签（如 icon_close、icon_back、icon_search、icon_more、icon_cart、icon_add、icon_share）
 * @property confidence 置信度 [0f..1f]
 */
data class DetectedIcon(
    val bounds: IntArray,
    val label: String,
    val confidence: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetectedIcon) return false
        return bounds.contentEquals(other.bounds) &&
            label == other.label &&
            confidence == other.confidence
    }

    override fun hashCode(): Int {
        var result = bounds.contentHashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + confidence.hashCode()
        return result
    }
}

/**
 * 端侧视觉无字图标检测器 (EdgeIconDetector)：
 * - 参考 OmniParser V2 局部可交互孤立区块提取与语义标注思路，专用于混合页面盲区或纯图标区域；
 * - 针对常见无文字交互元素（关闭叉号、返回箭头、搜索放大镜、更多菜单、购物车、添加加号、分享等）；
 * - 采用快速下采样网格梯度密度 + 8 邻域连通域聚类 + 纯 Kotlin 几何形态与位置先验语义分类器，耗时 <15ms，零网络开销；
 * - 自动根据尺寸区间和宽高比过滤杂波，并根据已有控件/文字框（excludeNormBounds）做 IoU 剔除；
 * - 输出符合标准归一化 [0..999] 区间的四元边界框 [left, top, right, bottom] 及语义标签。
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

    private data class DetectionCoreResult(
        val boxes: List<IntArray>,
        val lumaGrid: IntArray,
        val edgeGrid: BooleanArray,
        val gridW: Int,
        val gridH: Int,
        val imgWidth: Int,
        val imgHeight: Int,
    )

    /**
     * 检测 Bitmap 中的无字图标候选框（兼容旧签名，仅返回归一化边界框）
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

        val pixels = extractPixels(bitmap) ?: return emptyList()
        return detectBounds(pixels, bitmap.width, bitmap.height, excludeNormBounds)
    }

    /**
     * 检测 Bitmap 中的无字图标候选框并输出语义分类标签（OmniParser V2 轻量无依赖方案）
     */
    fun detectIcons(
        bitmap: Bitmap,
        excludeNormBounds: List<IntArray> = emptyList(),
    ): List<DetectedIcon> {
        val delegateResult = runCatching { delegate?.detect(bitmap, excludeNormBounds) }.getOrNull()
        val pixels = extractPixels(bitmap)
        if (delegateResult != null) {
            if (pixels == null) {
                return delegateResult.map { box ->
                    val (label, conf) = classifyByPositionPrior(box)
                    DetectedIcon(box, label, conf)
                }
            }
            val grid = buildEdgeGrid(pixels, bitmap.width, bitmap.height)
            return delegateResult.map { box ->
                val (label, conf) = classifyIcon(box, grid.lumaGrid, grid.edgeGrid, grid.gridW, grid.gridH, bitmap.width, bitmap.height)
                DetectedIcon(box, label, conf)
            }
        }
        if (pixels == null) return emptyList()
        return detectIcons(pixels, bitmap.width, bitmap.height, excludeNormBounds)
    }

    /**
     * 基于原始 ARGB 像素数组的高性能端侧梯度连通域检测（兼容旧签名）
     * 该重载无需依赖 Android Graphics 运行时，可在 JVM 单元测试与端侧实时并发中零反射高速执行。
     */
    fun detectBounds(
        pixels: IntArray,
        width: Int,
        height: Int,
        excludeNormBounds: List<IntArray> = emptyList(),
    ): List<IntArray> {
        return detectCore(pixels, width, height, excludeNormBounds).boxes
    }

    /**
     * 基于原始 ARGB 像素数组提取无字图标候选框，并使用几何形态 + 屏幕位置先验进行语义分类。
     */
    fun detectIcons(
        pixels: IntArray,
        width: Int,
        height: Int,
        excludeNormBounds: List<IntArray> = emptyList(),
    ): List<DetectedIcon> {
        val core = detectCore(pixels, width, height, excludeNormBounds)
        if (core.boxes.isEmpty()) return emptyList()
        return core.boxes.map { box ->
            val (label, confidence) = classifyIcon(box, core.lumaGrid, core.edgeGrid, core.gridW, core.gridH, core.imgWidth, core.imgHeight)
            DetectedIcon(bounds = box, label = label, confidence = confidence)
        }
    }

    private fun extractPixels(bitmap: Bitmap): IntArray? {
        val width = runCatching { bitmap.width }.getOrDefault(0)
        val height = runCatching { bitmap.height }.getOrDefault(0)
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        return runCatching {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            pixels
        }.getOrNull()
    }

    private data class EdgeGridData(
        val lumaGrid: IntArray,
        val edgeGrid: BooleanArray,
        val gridW: Int,
        val gridH: Int,
    )

    private fun buildEdgeGrid(pixels: IntArray, width: Int, height: Int): EdgeGridData {
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
                val dx = abs(lumaGrid[idx + 1] - lumaGrid[idx - 1])
                val dy = abs(lumaGrid[idx + gridW] - lumaGrid[idx - gridW])
                if (dx + dy >= GRADIENT_THRESHOLD) {
                    edgeGrid[idx] = true
                }
            }
        }
        return EdgeGridData(lumaGrid, edgeGrid, gridW, gridH)
    }

    private fun detectCore(
        pixels: IntArray,
        width: Int,
        height: Int,
        excludeNormBounds: List<IntArray>,
    ): DetectionCoreResult {
        if (width <= 2 || height <= 2 || pixels.size < width * height) {
            return DetectionCoreResult(emptyList(), IntArray(0), BooleanArray(0), 0, 0, width, height)
        }

        val (lumaGrid, edgeGrid, gridW, gridH) = buildEdgeGrid(pixels, width, height)

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

                // 图标等效尺寸区间（允许扁平三点菜单等最小边 >= 6，且最大边 >= 12，物理像素宽高比在 0.20 到 5.0 之间）
                if (count >= 3 &&
                    normW in 6..350 &&
                    normH in 6..350 &&
                    max(normW, normH) >= 12 &&
                    gridBlockW >= 2 &&
                    gridBlockH >= 2
                ) {
                    val physicalAspect = (normW.toFloat() * width) / max(1f, normH.toFloat() * height)
                    if (physicalAspect in 0.20f..5.0f) {
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
        val nmsBoxes = nonMaxSuppression(filteredByExclude, iouThreshold = 0.35f)
        return DetectionCoreResult(nmsBoxes, lumaGrid, edgeGrid, gridW, gridH, width, height)
    }

    /**
     * 基于位置先验与局部前景几何形态的无依赖轻量图标语义分类器。
     * 利用子框局部背景对比度提取完整前景笔画掩码（避免梯度双边缘对粗线条造成空洞干扰），
     * 并根据原始屏幕宽高还原物理宽高比（消除竖屏归一化拉伸畸变），
     * 综合位置区域（顶部左/右角、顶部搜索区、底部栏、弹窗角）、物理宽高比、对称度、外围对角臂、外围十字轴及空心圆环+斜柄特征分类。
     */
    internal fun classifyIcon(
        normBounds: IntArray,
        lumaGrid: IntArray,
        edgeGrid: BooleanArray,
        gridW: Int,
        gridH: Int,
        imgWidth: Int = gridW,
        imgHeight: Int = gridH,
    ): Pair<String, Float> {
        if (normBounds.size < 4 || gridW <= 2 || gridH <= 2 || lumaGrid.size < gridW * gridH) {
            return classifyByPositionPrior(normBounds, imgWidth = imgWidth, imgHeight = imgHeight)
        }

        val l = normBounds[0].coerceIn(0, 999)
        val t = normBounds[1].coerceIn(0, 999)
        val r = normBounds[2].coerceIn(l, 999)
        val b = normBounds[3].coerceIn(t, 999)

        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val nw = max(1, r - l)
        val nh = max(1, b - t)
        val safeImgW = imgWidth.coerceAtLeast(1).toFloat()
        val safeImgH = imgHeight.coerceAtLeast(1).toFloat()
        // 物理真实宽高比：将归一化框还原到真实像素长宽比例，避免竖屏 9:16/9:20 下正方形被误判为扁平条
        val aspect = (nw * safeImgW) / max(1f, nh * safeImgH)

        // 映射回 grid 坐标
        val gx0 = (l * (gridW - 1) / 999f).toInt().coerceIn(0, gridW - 1)
        val gx1 = (r * (gridW - 1) / 999f).toInt().coerceIn(gx0, gridW - 1)
        val gy0 = (t * (gridH - 1) / 999f).toInt().coerceIn(0, gridH - 1)
        val gy1 = (b * (gridH - 1) / 999f).toInt().coerceIn(gy0, gridH - 1)

        // 采样边界四角估算局部背景亮度，构建子窗口前景笔画掩码
        val cornerLumas = intArrayOf(
            lumaGrid[gy0 * gridW + gx0],
            lumaGrid[gy0 * gridW + gx1],
            lumaGrid[gy1 * gridW + gx0],
            lumaGrid[gy1 * gridW + gx1],
        ).sortedArray()
        val bgLuma = (cornerLumas[1] + cornerLumas[2]) / 2

        val boxW = max(1, gx1 - gx0 + 1)
        val boxH = max(1, gy1 - gy0 + 1)
        val fgMask = BooleanArray(boxW * boxH)

        var tightMinX = boxW - 1
        var tightMaxX = 0
        var tightMinY = boxH - 1
        var tightMaxY = 0
        var activeCount = 0

        for (ly in 0 until boxH) {
            val gy = gy0 + ly
            val gRow = gy * gridW
            val lRow = ly * boxW
            for (lx in 0 until boxW) {
                val gx = gx0 + lx
                val idx = gRow + gx
                if (abs(lumaGrid[idx] - bgLuma) >= 22) {
                    fgMask[lRow + lx] = true
                    activeCount++
                    if (lx < tightMinX) tightMinX = lx
                    if (lx > tightMaxX) tightMaxX = lx
                    if (ly < tightMinY) tightMinY = ly
                    if (ly > tightMaxY) tightMaxY = ly
                }
            }
        }

        // 若背景估算失败（如实心色块占满四角），回退使用梯度 edgeGrid
        if (activeCount < 4) {
            activeCount = 0
            tightMinX = boxW - 1
            tightMaxX = 0
            tightMinY = boxH - 1
            tightMaxY = 0
            for (ly in 0 until boxH) {
                val gy = gy0 + ly
                val gRow = gy * gridW
                val lRow = ly * boxW
                for (lx in 0 until boxW) {
                    val gx = gx0 + lx
                    if (edgeGrid.getOrNull(gRow + gx) == true) {
                        fgMask[lRow + lx] = true
                        activeCount++
                        if (lx < tightMinX) tightMinX = lx
                        if (lx > tightMaxX) tightMaxX = lx
                        if (ly < tightMinY) tightMinY = ly
                        if (ly > tightMaxY) tightMaxY = ly
                    }
                }
            }
        }
        if (activeCount < 4 || tightMaxX < tightMinX || tightMaxY < tightMinY) {
            return classifyByPositionPrior(normBounds)
        }

        val tw = max(1, tightMaxX - tightMinX + 1)
        val th = max(1, tightMaxY - tightMinY + 1)
        val edgeDensity = activeCount.toFloat() / (tw * th).toFloat()

        // 统计局部几何特征
        var hSymMatch = 0
        var vSymMatch = 0
        var outerActive = 0
        var outerDiagCount = 0
        var outerCrossCount = 0
        var ringCount = 0
        var ringArea = 0
        var innerCoreCount = 0
        var innerCoreArea = 0
        var topHalfCount = 0
        var bottomHalfCount = 0
        var bottomRightCount = 0
        var topRightCount = 0
        var leftYSpreadSum = 0f
        var leftYCount = 0
        var rightYSpreadSum = 0f
        var rightYCount = 0

        for (ly in tightMinY..tightMaxY) {
            val v = if (th <= 1) 0.5f else (ly - tightMinY).toFloat() / (th - 1)
            val mirrorY = tightMaxY - (ly - tightMinY)
            val rowOff = ly * boxW
            val mirrorRowOff = mirrorY * boxW

            for (lx in tightMinX..tightMaxX) {
                val u = if (tw <= 1) 0.5f else (lx - tightMinX).toFloat() / (tw - 1)
                val active = fgMask[rowOff + lx]
                val mirrorX = tightMaxX - (lx - tightMinX)

                val distCenter = hypot((u - 0.5f).toDouble(), (v - 0.5f).toDouble()).toFloat()
                if (distCenter in 0.22f..0.48f) {
                    ringArea++
                    if (active) ringCount++
                } else if (distCenter < 0.16f) {
                    innerCoreArea++
                    if (active) innerCoreCount++
                }

                if (!active) continue

                if (fgMask[rowOff + mirrorX]) hSymMatch++
                if (fgMask[mirrorRowOff + lx]) vSymMatch++

                if (v < 0.5f) topHalfCount++ else bottomHalfCount++
                if (u < 0.5f) {
                    leftYSpreadSum += abs(v - 0.5f)
                    leftYCount++
                } else {
                    rightYSpreadSum += abs(v - 0.5f)
                    rightYCount++
                }
                if (u > 0.55f && v > 0.55f) bottomRightCount++
                if (u > 0.55f && v < 0.45f) topRightCount++

                val maxAxisOffset = max(abs(u - 0.5f), abs(v - 0.5f))
                if (maxAxisOffset > 0.16f) {
                    outerActive++
                    val onMainDiag = abs(u - v) <= 0.20f
                    val onAntiDiag = abs(u + v - 1f) <= 0.20f
                    if (onMainDiag || onAntiDiag) outerDiagCount++

                    val onVertAxis = abs(u - 0.5f) <= 0.16f
                    val onHoriAxis = abs(v - 0.5f) <= 0.16f
                    if (onVertAxis || onHoriAxis) outerCrossCount++
                }
            }
        }

        val hSymmetry = hSymMatch.toFloat() / activeCount
        val vSymmetry = vSymMatch.toFloat() / activeCount
        val outerDiagRatio = if (outerActive > 0) outerDiagCount.toFloat() / outerActive else 0f
        val outerCrossRatio = if (outerActive > 0) outerCrossCount.toFloat() / outerActive else 0f
        val ringDensity = if (ringArea > 0) ringCount.toFloat() / ringArea else 0f
        val innerCoreDensity = if (innerCoreArea > 0) innerCoreCount.toFloat() / innerCoreArea else 0f
        val leftYSpread = if (leftYCount > 0) leftYSpreadSum / leftYCount else 0f
        val rightYSpread = if (rightYCount > 0) rightYSpreadSum / rightYCount else 0f

        // 屏幕区域先验标志
        val isTopBar = cy < 190f
        val isTopLeft = isTopBar && cx < 240f
        val isTopRight = isTopBar && cx > 760f
        val isTopCenterSearch = isTopBar && cx in 180f..820f
        val isBottomBar = cy > 830f
        val isFloatingBottomRight = cy in 680f..900f && cx > 750f
        val isModalCorner = (cy in 180f..780f) && (cx < 260f || cx > 740f)
        val isModalBottomCenterClose = (cy in 700f..880f) && (cx in 400f..600f)

        val hSegments = countAxisSegments(fgMask, boxW, tightMinX, tightMaxX, tightMinY, tightMaxY, horizontal = true)
        val vSegments = countAxisSegments(fgMask, boxW, tightMinX, tightMaxX, tightMinY, tightMaxY, horizontal = false)

        // 1. 强几何特征：关闭叉号 X（外围对角臂显著占优、中心交叉区有笔画交汇且双向对称，排除空心方框/梯形购物车）
        if (aspect in 0.60f..1.55f &&
            outerDiagRatio >= 0.48f &&
            outerDiagRatio >= outerCrossRatio + 0.12f &&
            innerCoreDensity >= 0.22f &&
            hSymmetry >= 0.45f &&
            vSymmetry >= 0.45f
        ) {
            val conf = (0.76f + 0.18f * min(1f, outerDiagRatio) + if (isTopRight || isModalCorner || isModalBottomCenterClose) 0.05f else 0f)
                .coerceAtMost(0.96f)
            return "icon_close" to conf
        }

        // 2. 强几何特征：添加加号 +（外围十字轴显著占优且双向对称）
        if (aspect in 0.60f..1.55f &&
            outerCrossRatio >= 0.50f &&
            outerCrossRatio >= outerDiagRatio + 0.12f &&
            hSymmetry >= 0.50f &&
            vSymmetry >= 0.50f
        ) {
            val conf = (0.75f + 0.16f * min(1f, outerCrossRatio) + if (isFloatingBottomRight || isBottomBar || isTopRight) 0.05f else 0f)
                .coerceAtMost(0.95f)
            return "icon_add" to conf
        }

        // 3. 强几何特征：返回箭头（左上角强位置先验，或左向尖锐非水平对称箭头 < / <-）
        if (isTopLeft && outerDiagRatio < 0.55f) {
            val isArrowLike = (vSymmetry >= 0.45f && hSymmetry < 0.65f) || leftYSpread <= rightYSpread + 0.06f
            val conf = if (isArrowLike) 0.88f else 0.78f
            return "icon_back" to conf
        }
        if (vSymmetry >= 0.55f && hSymmetry < 0.48f && leftYCount > 0 && rightYCount > 0 && leftYSpread + 0.05f < rightYSpread) {
            return "icon_back" to 0.76f
        }

        // 4. 强几何特征：搜索放大镜（空心圆环 + 右下斜柄，优先于更多菜单检查）
        if (aspect in 0.68f..1.45f &&
            ringDensity > innerCoreDensity + 0.10f &&
            (bottomRightCount > topRightCount || isTopCenterSearch)
        ) {
            val handleBonus = if (bottomRightCount > topRightCount) 0.10f else 0f
            val posBonus = if (isTopCenterSearch || isTopRight) 0.06f else 0f
            return "icon_search" to (0.72f + handleBonus + posBonus).coerceAtMost(0.92f)
        }

        // 5. 强几何特征：更多菜单（极扁/极瘦三点，或 3 离散段聚类）
        if (aspect >= 1.65f || aspect <= 0.58f ||
            (hSegments >= 3 && aspect >= 1.20f) ||
            (vSegments >= 3 && (isTopRight || aspect <= 0.85f))
        ) {
            val conf = (0.72f + if (isTopRight || aspect >= 1.65f || aspect <= 0.58f) 0.14f else 0.05f)
                .coerceAtMost(0.92f)
            return "icon_more" to conf
        }

        // 6. 位置+形态特征：购物车（底部栏或右上/右下角，下半轮廓充实或呈现左右对称中空梯形篮筐）
        if ((isBottomBar || isTopRight || isFloatingBottomRight) &&
            aspect in 0.75f..1.65f &&
            hSymmetry >= 0.35f &&
            (
                bottomHalfCount >= (topHalfCount * 1.12f).toInt() ||
                    (
                        hSymmetry >= 0.55f &&
                            innerCoreDensity < 0.18f &&
                            ringDensity > innerCoreDensity + 0.06f &&
                            bottomHalfCount >= (topHalfCount * 0.65f).toInt()
                        )
                )
        ) {
            val conf = if (isBottomBar) 0.76f else 0.68f
            return "icon_cart" to conf
        }

        // 7. 位置+形态特征：分享图标（右上角或底栏/操作区，非水平对称且上部或右上方开放分支）
        if ((isTopRight || isBottomBar) &&
            aspect in 0.65f..1.45f &&
            vSymmetry < 0.58f &&
            (topRightCount > 0 || topHalfCount >= bottomHalfCount)
        ) {
            return "icon_share" to 0.68f
        }

        // 8. 位置先验兜底（处理超小网格图标轮廓退化情况）
        return classifyByPositionPrior(normBounds, edgeDensity, imgWidth, imgHeight)
    }

    private fun countAxisSegments(
        edgeGrid: BooleanArray,
        gridW: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        horizontal: Boolean,
    ): Int {
        var segments = 0
        var inSegment = false
        if (horizontal) {
            for (gx in minX..maxX) {
                var colHasEdge = false
                for (gy in minY..maxY) {
                    if (edgeGrid[gy * gridW + gx]) {
                        colHasEdge = true
                        break
                    }
                }
                if (colHasEdge && !inSegment) {
                    segments++
                    inSegment = true
                } else if (!colHasEdge) {
                    inSegment = false
                }
            }
        } else {
            for (gy in minY..maxY) {
                var rowHasEdge = false
                val rowOff = gy * gridW
                for (gx in minX..maxX) {
                    if (edgeGrid[rowOff + gx]) {
                        rowHasEdge = true
                        break
                    }
                }
                if (rowHasEdge && !inSegment) {
                    segments++
                    inSegment = true
                } else if (!rowHasEdge) {
                    inSegment = false
                }
            }
        }
        return segments
    }

    private fun classifyByPositionPrior(
        normBounds: IntArray,
        edgeDensity: Float = 0.25f,
        imgWidth: Int = DEFAULT_GRID_W,
        imgHeight: Int = DEFAULT_GRID_H,
    ): Pair<String, Float> {
        if (normBounds.size < 4) return "icon_button" to 0.40f
        val l = normBounds[0]
        val t = normBounds[1]
        val r = normBounds[2]
        val b = normBounds[3]
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val aspect = (max(1, r - l).toFloat() * imgWidth.coerceAtLeast(1)) /
            max(1f, max(1, b - t).toFloat() * imgHeight.coerceAtLeast(1))

        return when {
            cy < 190f && cx < 240f -> "icon_back" to 0.70f
            cy < 190f && cx > 760f && (aspect >= 1.55f || aspect <= 0.65f) -> "icon_more" to 0.66f
            cy < 190f && cx > 760f && edgeDensity > 0.35f -> "icon_more" to 0.58f
            cy < 190f && cx > 760f -> "icon_close" to 0.58f
            cy < 190f && cx in 180f..820f -> "icon_search" to 0.60f
            (cy in 180f..780f) && (cx < 260f || cx > 740f) && aspect in 0.7f..1.4f -> "icon_close" to 0.62f
            (cy in 700f..880f) && (cx in 400f..600f) && aspect in 0.7f..1.4f -> "icon_close" to 0.62f
            cy in 680f..900f && cx > 750f -> "icon_add" to 0.60f
            cy > 830f && cx > 720f -> "icon_cart" to 0.56f
            cy > 830f && cx in 400f..600f -> "icon_add" to 0.56f
            else -> "icon_button" to 0.42f
        }
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
                // 候选框超过 30% 面积与已有文字/可点击控件重合，说明属于已覆盖区域
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

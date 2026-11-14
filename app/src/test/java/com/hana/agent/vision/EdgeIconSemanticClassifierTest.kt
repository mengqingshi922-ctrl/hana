package com.hana.agent.vision

import com.hana.agent.core.edge.EdgeDecisionPort
import com.hana.agent.edge.EdgeInferenceService
import com.hana.agent.edge.MnnPocketEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 验证 EdgeIconDetector 几何形态+位置先验语义分类器、MnnPocketEngine 诚实运行态与弹窗扩展规则、
 * 以及 EdgeInferenceService 端侧视觉定位语义关键词回退。
 */
class EdgeIconSemanticClassifierTest {

    private val bgWhite = 0xFFFFFFFF.toInt()
    private val fgBlack = 0xFF000000.toInt()

    private fun newCanvas(w: Int = 320, h: Int = 640): IntArray = IntArray(w * h) { bgWhite }

    private fun drawThickLine(
        pixels: IntArray,
        w: Int,
        h: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        thickness: Int = 3,
    ) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1) * 2
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val cx = (x0 + (x1 - x0) * t).toInt()
            val cy = (y0 + (y1 - y0) * t).toInt()
            for (dy in -thickness..thickness) {
                for (dx in -thickness..thickness) {
                    val px = cx + dx
                    val py = cy + dy
                    if (px in 0 until w && py in 0 until h) {
                        pixels[py * w + px] = fgBlack
                    }
                }
            }
        }
    }

    private fun drawBox(pixels: IntArray, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top..bottom) {
            if (y !in 0 until h) continue
            for (x in left..right) {
                if (x in 0 until w) pixels[y * w + x] = fgBlack
            }
        }
    }

    @Test
    fun classifiesTopLeftBackAndModalCloseAndAddIcons() {
        val w = 320
        val h = 640
        val pixels = newCanvas(w, h)

        // 1. 左上角 '<' 返回箭头（约 norm cx=100, cy=70）
        drawThickLine(pixels, w, h, 42, 32, 22, 48, thickness = 2)
        drawThickLine(pixels, w, h, 22, 48, 42, 64, thickness = 2)

        // 2. 右上角 'X' 关闭叉号（约 norm cx=880, cy=75）
        drawThickLine(pixels, w, h, 268, 34, 296, 62, thickness = 2)
        drawThickLine(pixels, w, h, 296, 34, 268, 62, thickness = 2)

        // 3. 右下角悬浮 '+' 添加加号（约 norm cx=860, cy=800）
        drawThickLine(pixels, w, h, 275, 495, 275, 535, thickness = 2)
        drawThickLine(pixels, w, h, 255, 515, 295, 515, thickness = 2)

        val icons = EdgeIconDetector.detectIcons(pixels, w, h)
        assertTrue("应至少检测到 3 个图标，实际=${icons.size}", icons.size >= 3)

        val backIcon = icons.firstOrNull { it.bounds[0] < 250 && it.bounds[1] < 200 }
        assertNotNull("左上角应检测到返回图标", backIcon)
        assertEquals("icon_back", backIcon!!.label)
        assertTrue(backIcon.confidence >= 0.70f)

        val closeIcon = icons.firstOrNull { it.bounds[2] > 740 && it.bounds[1] < 200 }
        assertNotNull("右上角应检测到关闭图标", closeIcon)
        assertEquals("icon_close", closeIcon!!.label)
        assertTrue(closeIcon.confidence >= 0.75f)

        val addIcon = icons.firstOrNull { it.bounds[0] > 700 && it.bounds[1] > 650 }
        assertNotNull("右下角应检测到加号图标", addIcon)
        assertEquals("icon_add", addIcon!!.label)
        assertTrue(addIcon.confidence >= 0.70f)
    }

    @Test
    fun classifiesSearchMagnifierAndMoreDotsAndBottomCartIcons() {
        val w = 320
        val h = 640
        val pixels = newCanvas(w, h)

        // 1. 顶部搜索区放大镜：空心圆环 + 右下斜柄（cx≈150, cy≈52 -> norm cx≈468, cy≈81）
        val rcx = 148
        val rcy = 48
        val radius = 14f
        for (y in 28..74) {
            for (x in 128..178) {
                val dist = hypot((x - rcx).toDouble(), (y - rcy).toDouble()).toFloat()
                if (abs(dist - radius) <= 2.4f) {
                    pixels[y * w + x] = fgBlack
                }
            }
        }
        drawThickLine(pixels, w, h, 158, 58, 174, 74, thickness = 2)

        // 2. 右上角横向三点 '...' 更多菜单（宽度明显大于高度）
        drawBox(pixels, w, h, 250, 44, 256, 50)
        drawBox(pixels, w, h, 266, 44, 272, 50)
        drawBox(pixels, w, h, 282, 44, 288, 50)

        // 3. 底部右侧购物车图标（下半部分轮廓明显更重，cy > 830）
        drawThickLine(pixels, w, h, 252, 550, 290, 550, thickness = 2)
        drawThickLine(pixels, w, h, 252, 550, 258, 574, thickness = 2)
        drawThickLine(pixels, w, h, 290, 550, 284, 574, thickness = 2)
        drawThickLine(pixels, w, h, 256, 576, 286, 576, thickness = 3)
        drawBox(pixels, w, h, 256, 582, 264, 590)
        drawBox(pixels, w, h, 278, 582, 286, 590)

        val icons = EdgeIconDetector.detectIcons(pixels, w, h)
        assertTrue("应检测到搜索/更多/购物车等图标", icons.isNotEmpty())

        val searchIcon = icons.firstOrNull { (it.bounds[0] + it.bounds[2]) / 2 in 300..650 && it.bounds[1] < 200 }
        assertNotNull("顶部居中区应检测到搜索放大镜图标", searchIcon)
        assertEquals("icon_search", searchIcon!!.label)

        val cartIcon = icons.firstOrNull { (it.bounds[1] + it.bounds[3]) / 2 > 820 && it.bounds[0] > 650 }
        assertNotNull("底栏右侧应检测到购物车图标", cartIcon)
        assertEquals("icon_cart", cartIcon!!.label)

        // 若三点在电网下被聚成单个扁框（或三点），至少右上角框不应误识别为 icon_add
        val topRight = icons.filter { it.bounds[2] > 740 && it.bounds[1] < 200 }
        assertTrue(topRight.none { it.label == "icon_add" })
    }

    @Test
    fun detectsMoreThreeDotsMenuInTopRight() {
        val w = 320
        val h = 640
        val pixels = newCanvas(w, h)
        // 绘制一个连续横向紧凑扁状更多图标（类似三点/三横扁条，宽高比 > 2.0）
        drawBox(pixels, w, h, 245, 42, 254, 52)
        drawBox(pixels, w, h, 255, 45, 258, 49)
        drawBox(pixels, w, h, 259, 42, 268, 52)
        drawBox(pixels, w, h, 269, 45, 272, 49)
        drawBox(pixels, w, h, 273, 42, 282, 52)

        val icons = EdgeIconDetector.detectIcons(pixels, w, h)
        assertEquals(1, icons.size)
        assertEquals("icon_more", icons[0].label)
    }

    @Test
    fun edgeInferenceServiceLocateFromPixelsFindsMatchedSemanticIcon() {
        val w = 320
        val h = 640
        val pixels = newCanvas(w, h)

        // 左上角返回箭头
        drawThickLine(pixels, w, h, 42, 32, 22, 48, thickness = 2)
        drawThickLine(pixels, w, h, 22, 48, 42, 64, thickness = 2)

        // 右上角关闭叉号
        drawThickLine(pixels, w, h, 268, 34, 296, 62, thickness = 2)
        drawThickLine(pixels, w, h, 296, 34, 268, 62, thickness = 2)

        val closeJsonStr = EdgeInferenceService.locateFromPixels(pixels, w, h, query = "关闭弹窗")
        val closeJson = Json.parseToJsonElement(closeJsonStr).jsonObject
        assertEquals("ok", closeJson["status"]?.jsonPrimitive?.content)
        assertFalse(closeJson["native_loaded"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals("icon_close", closeJson["matched"]?.jsonObject?.get("label")?.jsonPrimitive?.content)
        assertTrue((closeJson["candidates"]?.jsonArray?.size ?: 0) >= 2)

        val backJsonStr = EdgeInferenceService.locateFromPixels(pixels, w, h, query = "返回上一页")
        val backJson = Json.parseToJsonElement(backJsonStr).jsonObject
        assertEquals("ok", backJson["status"]?.jsonPrimitive?.content)
        assertEquals("icon_back", backJson["matched"]?.jsonObject?.get("label")?.jsonPrimitive?.content)
    }

    @Test
    fun mnnPocketEngineExposesHonestRuntimeStatusAndExtendedObstacleRules() = runTest {
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Unconfined).apply {
            forceReadyForTesting = true
        }

        // 1. 诚实运行态描述验证
        val status = engine.getRuntimeStatus()
        assertEquals(MnnPocketEngine.ExecutionBackend.KOTLIN_HEURISTIC_ONLY, status.backend)
        assertFalse(status.nativeLibraryLoaded)
        assertTrue(status.description.contains("Kotlin"))

        // 2. 高频中文弹窗变体覆盖验证
        val adCandidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@ad1", "Button", "立即查看"),
            EdgeDecisionPort.EdgeCandidate("@ad2", "TextView", "跳过广告 3s"),
        )
        assertEquals("@ad2", engine.pickObstacleDismissRef("看新闻", "开屏广告", adCandidates))

        val permissionCandidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@p1", "Button", "立即开启"),
            EdgeDecisionPort.EdgeCandidate("@p2", "Button", "暂不开启"),
        )
        assertEquals("@p2", engine.pickObstacleDismissRef("浏览首页", "开启通知权限", permissionCandidates))

        val harshDeclineCandidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@h1", "Button", "抢先体验"),
            EdgeDecisionPort.EdgeCandidate("@h2", "Button", "残忍拒绝"),
        )
        assertEquals("@h2", engine.pickObstacleDismissRef("逛主页", "新人礼包", harshDeclineCandidates))

        val noticeCandidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@n1", "Button", "我知道了"),
            EdgeDecisionPort.EdgeCandidate("@n2", "Button", "查看协议详情"),
        )
        assertEquals("@n1", engine.pickObstacleDismissRef("搜索商品", "系统提示", noticeCandidates))

        val consentCandidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@c1", "Button", "不同意并退出"),
            EdgeDecisionPort.EdgeCandidate("@c2", "Button", "同意并继续"),
        )
        assertEquals("@c2", engine.pickObstacleDismissRef("打开应用", "隐私政策与用户协议", consentCandidates))

        // 确保业务按钮绝不被误判为障碍物
        val businessOnly = listOf(
            EdgeDecisionPort.EdgeCandidate("@b1", "Button", "立即下单"),
            EdgeDecisionPort.EdgeCandidate("@b2", "Button", "加入购物车"),
        )
        assertNull(engine.pickObstacleDismissRef("购买商品", "", businessOnly))
    }
}

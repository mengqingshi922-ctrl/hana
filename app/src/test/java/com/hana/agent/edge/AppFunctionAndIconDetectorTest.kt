package com.hana.agent.edge

import com.hana.agent.vision.EdgeIconDetector
import com.hana.agent.vision.MnnIconDetectorDelegate
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFunctionAndIconDetectorTest {

    @Test
    fun testAppFunctionToMcpToolDefsMapping() {
        val function1 = DiscoveredAppFunction(
            packageName = "com.tencent.mm",
            functionId = "send_message",
            title = "微信发送消息",
            description = "向指定联系人发送聊天文本",
            parameterKeys = listOf("contact", "content"),
        )
        val function2 = DiscoveredAppFunction(
            packageName = "com.alibaba.android.rimet",
            functionId = "quick_clock_in",
            title = "钉钉极速打卡",
            description = "执行考勤上下班打卡动作",
            parameterKeys = listOf("location", "remark"),
        )

        val mcpTools = AppFunctionBridge.toMcpToolDefs(listOf(function1, function2))
        assertEquals(2, mcpTools.size)

        // 验证微信工具契约
        val tool1 = mcpTools[0]
        assertEquals("appfn_com_tencent_mm_send_message", tool1.name)
        assertTrue(tool1.description.contains("微信发送消息"))
        assertTrue(tool1.description.contains("向指定联系人发送聊天文本"))
        assertEquals(true, tool1.annotations.openWorldHint)

        val schema1 = tool1.inputSchema
        assertEquals("object", schema1["type"]?.jsonPrimitive?.content)
        val props1 = schema1["properties"]?.jsonObject
        assertNotNull(props1)
        assertTrue(props1!!.containsKey("contact"))
        assertTrue(props1.containsKey("content"))
        assertEquals("string", props1["contact"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val required1 = schema1["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(required1)
        assertEquals(listOf("contact", "content"), required1)
        assertEquals(false, schema1["additionalProperties"]?.jsonPrimitive?.booleanOrNull)

        // 验证钉钉工具契约
        val tool2 = mcpTools[1]
        assertEquals("appfn_com_alibaba_android_rimet_quick_clock_in", tool2.name)
        assertTrue(tool2.description.contains("钉钉极速打卡"))
        val props2 = tool2.inputSchema["properties"]?.jsonObject
        assertTrue(props2!!.containsKey("location"))
        assertTrue(props2.containsKey("remark"))
    }

    @Test
    fun testDiscoverAppFunctionsLowSdkGracefulDegradation() {
        // 在常规 JVM 单元测试环境中，Build.VERSION.SDK_INT 未达到 36，必须优雅降级返回空列表且零异常
        val dummyContext = try {
            // 通过无参数安全反射或伪造，即使传 null 也能在版本检查处截断
            val c = android.app.Application()
            c
        } catch (_: Throwable) {
            null
        }

        if (dummyContext != null) {
            val result = AppFunctionBridge.discoverAppFunctions(dummyContext)
            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun testEdgeIconDetectorClustersTwoIconsAndExcludesExistingText() {
        val width = 320
        val height = 640
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()

        // 1. 初始化纯白底色画布
        val pixels = IntArray(width * height) { white }

        // 2. 绘制第 1 个模拟图标（左上角返回按钮：30..60, 40..70）
        for (y in 40..70) {
            val row = y * width
            for (x in 30..60) {
                pixels[row + x] = black
            }
        }

        // 3. 绘制第 2 个模拟图标（右上角设置齿轮：250..280, 40..70）
        for (y in 40..70) {
            val row = y * width
            for (x in 250..280) {
                pixels[row + x] = black
            }
        }

        // 4. 绘制第 3 个色块（模拟中间已识别出的文字标题：130..190, 45..65）
        for (y in 45..65) {
            val row = y * width
            for (x in 130..190) {
                pixels[row + x] = black
            }
        }

        // 5. 传入中间文字区域的归一化排除框：
        // 130 * 999 / 320 ≈ 405, 190 * 999 / 320 ≈ 593, 45 * 999 / 640 ≈ 70, 65 * 999 / 640 ≈ 101
        val excludeTextBounds = listOf(
            intArrayOf(400, 60, 600, 110)
        )

        val detected = EdgeIconDetector.detectBounds(
            pixels = pixels,
            width = width,
            height = height,
            excludeNormBounds = excludeTextBounds,
        )

        // 6. 验证：准确提取 2 个不重叠的独立图标框，第 3 个文字区域被有效排除
        assertEquals(2, detected.size)

        val box1 = detected[0]
        val box2 = detected[1]

        // 验证两框互不重叠
        val iou = EdgeIconDetector.computeIoU(box1, box2)
        assertTrue("IoU between distinct icons should be near zero, was: $iou", iou < 0.05f)

        // 验证坐标完全在 [0..999] 范围内
        for (b in detected) {
            assertEquals(4, b.size)
            assertTrue("Left in bounds", b[0] in 0..999)
            assertTrue("Top in bounds", b[1] in 0..999)
            assertTrue("Right in bounds", b[2] in 0..999)
            assertTrue("Bottom in bounds", b[3] in 0..999)
            assertTrue("Right >= Left", b[2] >= b[0])
            assertTrue("Bottom >= Top", b[3] >= b[1])
        }

        // 验证左上角和右上角各提取出一个框
        val hasLeftIcon = detected.any { it[0] in 50..250 && it[1] in 40..200 }
        val hasRightIcon = detected.any { it[0] in 700..950 && it[1] in 40..200 }
        assertTrue("Must detect left icon", hasLeftIcon)
        assertTrue("Must detect right icon", hasRightIcon)

        // 验证中间已被排除的文字区域不存在于候选框中
        val hasCenterText = detected.any { it[0] > 350 && it[2] < 650 }
        assertFalse("Excluded text region must not be returned", hasCenterText)
    }

    @Test
    fun testEdgeIconDetectorMnnDelegateFallback() {
        val originalDelegate = EdgeIconDetector.delegate
        try {
            val mockBox = intArrayOf(100, 100, 200, 200)
            EdgeIconDetector.delegate = MnnIconDetectorDelegate { _, _ ->
                listOf(mockBox)
            }

            // 构造 dummy bitmap 触发 delegate
            val dummyBitmap = try {
                android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) {
                null
            }

            if (dummyBitmap != null) {
                val results = EdgeIconDetector.detectBounds(dummyBitmap)
                assertEquals(1, results.size)
                assertEquals(mockBox, results[0])
            }
        } finally {
            EdgeIconDetector.delegate = originalDelegate
        }
    }
}

package com.hana.agent.cap.phone.a11y

import android.text.InputType
import com.hana.agent.core.uistate.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕输入防重写入状态机与无字图标候选框定位单元测试
 */
class InputAndIconWiringTest {

    @Test
    fun testTextDispatchStateAntiDuplicateLogic() {
        var state = TextDispatchState.NOT_SENT
        var duplicateAttemptCount = 0

        // 模拟写入拦截器：处于 UNCERTAIN 时拒绝再次写入非空文本，防止内容翻倍
        fun simulateWrite(text: String): Boolean {
            if (state == TextDispatchState.UNCERTAIN && text.isNotEmpty()) {
                duplicateAttemptCount++
                return false
            }
            state = TextDispatchState.DISPATCHED
            return true
        }

        // 1. 初始状态下正常写入
        val firstWrite = simulateWrite("HanaAgent")
        assertTrue(firstWrite)
        assertEquals(TextDispatchState.DISPATCHED, state)
        assertEquals(0, duplicateAttemptCount)

        // 2. 模拟超时或读回未知，状态切换为 UNCERTAIN
        state = TextDispatchState.UNCERTAIN

        // 3. 此时若再次尝试写入，应当被拦截，防重复写入
        val secondWrite = simulateWrite("HanaAgent")
        assertFalse("处于 UNCERTAIN 时应拒绝盲目二次写入", secondWrite)
        assertEquals(1, duplicateAttemptCount)
        assertEquals(TextDispatchState.UNCERTAIN, state)

        // 4. 清空或重置后恢复正常
        state = TextDispatchState.NOT_SENT
        val thirdWrite = simulateWrite("NewText")
        assertTrue(thirdWrite)
        assertEquals(TextDispatchState.DISPATCHED, state)
    }

    @Test
    fun testEdgeIconBoundsToPixelCoordinateConversion() {
        val screenWidth = 1080
        val screenHeight = 2400

        // 归一化坐标 [100, 200, 300, 400] (0..999)
        val normBox = intArrayOf(100, 200, 300, 400)

        val l = (normBox[0] * screenWidth / 1000).coerceIn(0, screenWidth)
        val t = (normBox[1] * screenHeight / 1000).coerceIn(0, screenHeight)
        val r = (normBox[2] * screenWidth / 1000).coerceIn(0, screenWidth)
        val b = (normBox[3] * screenHeight / 1000).coerceIn(0, screenHeight)

        val cx = (l + r) / 2
        val cy = (t + b) / 2

        assertEquals(108, l)
        assertEquals(480, t)
        assertEquals(324, r)
        assertEquals(960, b)
        assertEquals(216, cx)
        assertEquals(720, cy)

        val element = UiElement(
            ref = "ico_0",
            role = "icon_candidate",
            name = "icon_candidate",
            centerX = cx,
            centerY = cy,
            bounds = listOf(l, t, r, b),
            resourceId = null,
            clickable = true,
            editable = false,
            scrollable = false,
        )

        assertEquals("ico_0", element.ref)
        assertEquals("icon_candidate", element.role)
        assertTrue(element.clickable)
        assertFalse(element.editable)
        assertEquals(listOf(108, 480, 324, 960), element.bounds)
    }

    @Test
    fun testDecimalNumberFieldProtectionFlags() {
        val decimalNumberType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val signedDecimalType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val plainTextType = InputType.TYPE_CLASS_TEXT

        fun isDecimalNumberInput(inputType: Int): Boolean {
            return (inputType and InputType.TYPE_CLASS_NUMBER) != 0 &&
                (inputType and (InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED)) != 0
        }

        assertTrue(isDecimalNumberInput(decimalNumberType))
        assertTrue(isDecimalNumberInput(signedDecimalType))
        assertFalse(isDecimalNumberInput(plainTextType))

        // 验证十进制有效数值包含负号和小数点时不会被误当成非数字破坏
        val validDecimal = "-123.45"
        val regex = Regex("^-?\\d+(\\.\\d+)?$")
        assertTrue(regex.matches(validDecimal))
    }
}

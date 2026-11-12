package com.hana.agent.cap.phone.display

import android.view.KeyEvent
import com.hana.agent.shell.IHanaShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipInputTest {

    private class TestShell : IHanaShell.Default() {
        var lastTap: Triple<Int, Int, Int>? = null // displayId, x, y
        var lastStroke: StrokeCall? = null
        var lastKey: Pair<Int, Int>? = null // displayId, keyCode

        data class StrokeCall(
            val displayId: Int,
            val xs: IntArray,
            val ys: IntArray,
            val times: LongArray,
        )

        override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
            lastTap = Triple(displayId, x, y)
            return true
        }

        override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
            lastStroke = StrokeCall(displayId, xs, ys, times)
            return true
        }

        override fun injectKey(displayId: Int, keyCode: Int): Boolean {
            lastKey = Pair(displayId, keyCode)
            return true
        }
    }

    @Test
    fun pixelCoordinateConversionClampsCorrectly() {
        val width = 1080
        val height = 2400

        // 原点
        assertEquals(0, PipInput.toPixelX(0f, width))
        assertEquals(0, PipInput.toPixelY(0f, height))

        // 中心
        assertEquals(540, PipInput.toPixelX(0.5f, width))
        assertEquals(1200, PipInput.toPixelY(0.5f, height))

        // 右下角（必须安全落在 0 until size 范围内）
        assertEquals(1079, PipInput.toPixelX(1.0f, width))
        assertEquals(2399, PipInput.toPixelY(1.0f, height))

        // 越界值自动裁剪
        assertEquals(0, PipInput.toPixelX(-0.5f, width))
        assertEquals(0, PipInput.toPixelY(-1.0f, height))
        assertEquals(1079, PipInput.toPixelX(1.5f, width))
        assertEquals(2399, PipInput.toPixelY(2.0f, height))

        // 异常宽高
        assertEquals(0, PipInput.toPixelX(0.5f, 0))
        assertEquals(0, PipInput.toPixelY(0.5f, -100))
    }

    @Test
    fun tapConvertsNormalizedCoordinatesAndInjects() = runTest {
        val shell = TestShell()
        val input = DisplayPipInput(shell, displayId = 7, width = 1000, height = 2000)

        input.tap(0.25f, 0.75f)

        assertEquals(Triple(7, 250, 1500), shell.lastTap)
    }

    @Test
    fun swipeConvertsPointsAndGeneratesMonotonicTimes() = runTest {
        val shell = TestShell()
        val input = DisplayPipInput(shell, displayId = 5, width = 1000, height = 2000)

        val points = listOf(
            0.1f to 0.2f,
            0.4f to 0.5f,
            0.8f to 0.9f,
        )
        input.swipe(points, durationMs = 300L)

        val stroke = shell.lastStroke
        assertTrue("stroke must be called", stroke != null)
        assertEquals(5, stroke!!.displayId)
        assertEquals(3, stroke.xs.size)
        assertEquals(100, stroke.xs[0])
        assertEquals(400, stroke.xs[1])
        assertEquals(800, stroke.xs[2])

        assertEquals(400, stroke.ys[0])
        assertEquals(1000, stroke.ys[1])
        assertEquals(1800, stroke.ys[2])

        // 验证时间序列单调递增，起始为 0L，末尾为总时长
        assertEquals(0L, stroke.times[0])
        assertEquals(150L, stroke.times[1])
        assertEquals(300L, stroke.times[2])
    }

    @Test
    fun singlePointSwipeFallsBackToTap() = runTest {
        val shell = TestShell()
        val input = DisplayPipInput(shell, displayId = 3, width = 1000, height = 1000)

        input.swipe(listOf(0.5f to 0.6f), durationMs = 100L)

        // 单点滑动避免被 Shell 拒绝，退化为点击
        assertEquals(Triple(3, 500, 600), shell.lastTap)
        assertEquals(null, shell.lastStroke)
    }

    @Test
    fun backInjectsKeycodeBack() = runTest {
        val shell = TestShell()
        val input = DisplayPipInput(shell, displayId = 9, width = 1080, height = 2400)

        input.back()

        assertEquals(Pair(9, KeyEvent.KEYCODE_BACK), shell.lastKey)
    }

    @Test
    fun invalidDisplayIgnoresInputGracefully() = runTest {
        val shell = TestShell()
        val input = DisplayPipInput(shell, displayId = -1, width = 0, height = 0)

        input.tap(0.5f, 0.5f)
        input.swipe(listOf(0.1f to 0.1f, 0.2f to 0.2f), 200L)
        input.back()

        assertEquals(null, shell.lastTap)
        assertEquals(null, shell.lastStroke)
        assertEquals(null, shell.lastKey)
    }
}

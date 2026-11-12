package com.hana.agent.task

import com.hana.agent.core.agent.FailKind
import com.hana.agent.core.agent.GateReason
import com.hana.agent.core.agent.RunProgress
import com.hana.agent.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskServiceLiveUpdateTest {

    @Test
    fun buildLiveUpdateSpecHasFourMilestonesSummingTo100() {
        val spec = TaskService.buildLiveUpdateSpec(TaskState.Running(step = 3, note = "点击确认"))
        assertEquals(4, spec.segments.size)
        assertEquals(100, spec.segments.sumOf { it.length })
        assertEquals(100, spec.max)
        assertEquals(3, spec.points.size)
        assertEquals(listOf(20, 50, 85), spec.points.map { it.position })
        assertTrue(spec.progress in 50..92)
        assertEquals("第3步", spec.shortCriticalText)
        assertFalse(spec.indeterminate)
    }

    @Test
    fun buildLiveUpdateSpecCoversReplayAttentionHeldAndTerminalStates() {
        val replay = TaskService.buildLiveUpdateSpec(
            TaskState.Running(step = 2, note = "回放", progress = RunProgress.Replay(step = 3, total = 4)),
        )
        assertEquals(75, replay.progress)
        assertEquals("3/4", replay.shortCriticalText)
        assertEquals(2, replay.stageIndex)

        val waitingApp = TaskService.buildLiveUpdateSpec(
            TaskState.Running(step = 1, note = "等待", progress = RunProgress.WaitingForApp("微信")),
        )
        assertTrue(waitingApp.indeterminate)
        assertEquals("排队中", waitingApp.shortCriticalText)

        val takeover = TaskService.buildLiveUpdateSpec(
            TaskState.WaitingTakeover(reason = "captcha", message = "请完成滑块验证"),
        )
        assertEquals(65, takeover.progress)
        assertEquals("需接管", takeover.shortCriticalText)

        val held = TaskService.buildLiveUpdateSpec(
            TaskState.Held(GateReason(code = "paused", why = "暂停", resumable = true)),
        )
        assertEquals("已暂停", held.shortCriticalText)
        assertTrue(held.indeterminate)

        val done = TaskService.buildLiveUpdateSpec(TaskState.Succeeded("已完成任务"))
        assertEquals(100, done.progress)
        assertEquals("已完成", done.shortCriticalText)
        assertEquals(3, done.stageIndex)

        val failed = TaskService.buildLiveUpdateSpec(TaskState.Failed("err", FailKind.GaveUp))
        assertEquals(0, failed.progress)
        assertEquals("已中断", failed.shortCriticalText)
    }

    @Test
    fun dispatchLiveUpdateUsesProgressStyleOnApi36AndFallsBackOnLowerApi() {
        assertEquals(TaskService.Companion.LiveUpdatePath.LEGACY_BIG_TEXT_FALLBACK, TaskService.selectLiveUpdatePath(35))
        assertEquals(TaskService.Companion.LiveUpdatePath.API_36_PROGRESS_STYLE, TaskService.selectLiveUpdatePath(36))

        var capturedSpec: TaskService.Companion.LiveUpdateSpec? = null
        var capturedFallback: String? = null
        val sink = object : TaskService.Companion.LiveUpdateSink {
            override fun applyProgressStyle(spec: TaskService.Companion.LiveUpdateSpec) {
                capturedSpec = spec
            }

            override fun applyBigTextFallback(body: String) {
                capturedFallback = body
            }
        }

        val state = TaskService.buildLiveUpdateSpec(TaskState.Running(step = 4, note = "输入验证码"))
        assertNotNull(state)

        // API 35 (Android 15) 走无缝回退路径
        val legacyPath = TaskService.dispatchLiveUpdate(
            state = TaskState.Running(step = 4, note = "输入验证码"),
            fallbackBigText = "第4步：输入验证码",
            sdkInt = 35,
            sink = sink,
        )
        assertEquals(TaskService.Companion.LiveUpdatePath.LEGACY_BIG_TEXT_FALLBACK, legacyPath)
        assertNull(capturedSpec)
        assertEquals("第4步：输入验证码", capturedFallback)

        // API 36 (Android 16) 走 ProgressStyle 分段里程碑路径
        capturedFallback = null
        val api36Path = TaskService.dispatchLiveUpdate(
            state = TaskState.Running(step = 4, note = "输入验证码"),
            fallbackBigText = "第4步：输入验证码",
            sdkInt = 36,
            sink = sink,
        )
        assertEquals(TaskService.Companion.LiveUpdatePath.API_36_PROGRESS_STYLE, api36Path)
        assertNotNull(capturedSpec)
        assertEquals("第4步", capturedSpec?.shortCriticalText)
        assertNull(capturedFallback)

        // 若 API 36 样式构建异常，也自动回退 BigTextStyle 不崩溃
        val failingSink = object : TaskService.Companion.LiveUpdateSink {
            override fun applyProgressStyle(spec: TaskService.Companion.LiveUpdateSpec) {
                error("模拟 OEM ProgressStyle 异常")
            }

            override fun applyBigTextFallback(body: String) {
                capturedFallback = body
            }
        }
        val resilientPath = TaskService.dispatchLiveUpdate(
            state = TaskState.Running(step = 2, note = "打开设置"),
            fallbackBigText = "安全回退文本",
            sdkInt = 36,
            sink = failingSink,
        )
        assertEquals(TaskService.Companion.LiveUpdatePath.LEGACY_BIG_TEXT_FALLBACK, resilientPath)
        assertEquals("安全回退文本", capturedFallback)
    }
}

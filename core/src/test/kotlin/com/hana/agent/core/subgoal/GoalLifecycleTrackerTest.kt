package com.hana.agent.core.subgoal

import com.hana.agent.core.tool.ToolExecution
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalLifecycleTrackerTest {

    @Test
    fun testGoalInitializationFromIntentPlan() {
        val tracker = GoalLifecycleTracker()
        val plan = listOf("打开网易云", "搜索歌曲", "点击播放")
        tracker.initialize("播放周杰伦的晴天", explicitPlan = plan)

        assertEquals(3, tracker.trackedCount)
        assertFalse(tracker.isAllCompleted)

        val g0 = tracker.allGoals[0]
        assertEquals("打开网易云", g0.title)
        assertEquals(GoalStatus.IN_PROGRESS, g0.status)

        val g1 = tracker.allGoals[1]
        assertEquals("搜索歌曲", g1.title)
        assertEquals(GoalStatus.PENDING, g1.status)

        val g2 = tracker.allGoals[2]
        assertEquals("点击播放", g2.title)
        assertEquals(GoalStatus.PENDING, g2.status)
    }

    @Test
    fun testGoalInitializationFromTaskStringAutoSplit() {
        val tracker = GoalLifecycleTracker()
        val task = "打开高德地图 -> 搜索附近加油站 -> 选择第一个导航"
        tracker.initialize(task)

        assertTrue(tracker.trackedCount >= 3)
        assertEquals(GoalStatus.IN_PROGRESS, tracker.currentGoal?.status)
        assertEquals("打开高德地图", tracker.currentGoal?.title)
    }

    @Test
    fun testActionExecutionSatisfiesGoalAndAdvances() {
        val checkpointManager = SubgoalCheckpointManager()
        val tracker = GoalLifecycleTracker(checkpointManager)
        tracker.initialize("买咖啡", explicitPlan = listOf("打开美团", "搜索瑞幸", "结算下单"))

        assertEquals(0, tracker.consecutiveFailures)
        assertEquals("打开美团", tracker.currentGoal?.title)

        // 步骤 1：动作执行成功并包含目标标题关键词
        val successExec1 = ToolExecution(buildJsonObject {
            put("ok", true)
            put("result", "打开美团成功")
        })
        tracker.onActionExecuted(successExec1, currentPackage = "com.sankuai.meituan")

        assertEquals(0, tracker.consecutiveFailures)
        assertEquals(GoalStatus.COMPLETED, tracker.allGoals[0].status)
        assertEquals("搜索瑞幸", tracker.currentGoal?.title)
        assertEquals(GoalStatus.IN_PROGRESS, tracker.currentGoal?.status)
        assertEquals(1, checkpointManager.getCheckpoints().size)
        assertEquals("cp_goal_0", checkpointManager.getLatestCheckpoint()?.checkpointId)

        // 步骤 2：使用 SUBGOAL_DONE 标识推进
        val successExec2 = ToolExecution(buildJsonObject {
            put("ok", true)
            put("result", "找到瑞幸咖啡 [SUBGOAL_DONE]")
        })
        tracker.onActionExecuted(successExec2, currentPackage = "com.sankuai.meituan")

        assertEquals(GoalStatus.COMPLETED, tracker.allGoals[1].status)
        assertEquals("结算下单", tracker.currentGoal?.title)

        // 步骤 3：完成最终步骤
        val successExec3 = ToolExecution(buildJsonObject {
            put("ok", true)
            put("result", "结算下单已完成")
        })
        tracker.onActionExecuted(successExec3, currentPackage = "com.sankuai.meituan")

        assertTrue(tracker.isAllCompleted)
        assertEquals(3, checkpointManager.getCheckpoints().size)
    }

    @Test
    fun testActionFailureOrAssertionMismatchMaintainsState() {
        val tracker = GoalLifecycleTracker()
        tracker.initialize("任务", explicitPlan = listOf("步骤一", "步骤二"))

        assertEquals(0, tracker.consecutiveFailures)

        // 失败 1：工具执行报错 ok = false
        val failExec = ToolExecution(buildJsonObject {
            put("ok", false)
            put("error", "element_not_found")
        })
        tracker.onActionExecuted(failExec, currentPackage = "com.example")
        assertEquals(1, tracker.consecutiveFailures)
        assertEquals("步骤一", tracker.currentGoal?.title)
        assertEquals(GoalStatus.IN_PROGRESS, tracker.currentGoal?.status)

        // 失败 2：工具 ok，但未达成断言条件
        val irrelevantExec = ToolExecution(buildJsonObject {
            put("ok", true)
            put("result", "滑动了屏幕")
        })
        tracker.onActionExecuted(irrelevantExec, currentPackage = "com.other.app")
        assertEquals(2, tracker.consecutiveFailures)
        assertEquals(GoalStatus.IN_PROGRESS, tracker.currentGoal?.status)

        tracker.resetFailures()
        assertEquals(0, tracker.consecutiveFailures)
    }

    @Test
    fun testStructuredProgressTreeMarkdownOutput() {
        val tracker = GoalLifecycleTracker()
        tracker.initialize("播放音乐", explicitPlan = listOf("打开网易云", "搜索歌曲", "点击播放"))

        var tree = tracker.formatProgressTree()
        assertTrue(tree.contains("[►] 打开网易云"))
        assertTrue(tree.contains("[ ] 搜索歌曲"))
        assertTrue(tree.contains("[ ] 点击播放"))

        tracker.advanceManual()
        tree = tracker.formatProgressTree()
        assertTrue(tree.contains("[✓] 打开网易云"))
        assertTrue(tree.contains("[►] 搜索歌曲"))
        assertTrue(tree.contains("[ ] 点击播放"))
    }
}

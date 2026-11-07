package com.hana.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AppFastPathTest {
    private val installed = listOf(
        "com.xingin.xhs" to "小红书",
        "com.tencent.mm" to "微信",
        "com.netease.cloudmusic" to "网易云音乐",
        "com.luna.music" to "汽水音乐",
        "com.android.settings" to "设置",
    )

    @Test
    fun matchesPureOpenCommands() {
        val m1 = AppFastPath.match("打开小红书", installed)
        assertNotNull(m1)
        assertEquals("com.xingin.xhs", m1!!.packageName)
        assertEquals("小红书", m1.appLabel)
        assertTrue(m1.isPureOpen)

        val m2 = AppFastPath.match("帮我打开微信app", installed)
        assertNotNull(m2)
        assertEquals("com.tencent.mm", m2!!.packageName)
        assertTrue(m2.isPureOpen)

        val m3 = AppFastPath.match("启动网易云音乐", installed)
        assertNotNull(m3)
        assertEquals("com.netease.cloudmusic", m3!!.packageName)
        assertTrue(m3.isPureOpen)
    }

    @Test
    fun matchesCompositeCommands() {
        val m1 = AppFastPath.match("打开小红书，搜索减脂期高蛋白快手晚餐食谱", installed)
        assertNotNull(m1)
        assertEquals("com.xingin.xhs", m1!!.packageName)
        assertFalse(m1.isPureOpen)

        val m2 = AppFastPath.match("帮我打开汽水音乐 看广告领VIP", installed)
        assertNotNull(m2)
        assertEquals("com.luna.music", m2!!.packageName)
        assertFalse(m2.isPureOpen)
    }

    @Test
    fun ignoresUnrelatedTasks() {
        assertNull(AppFastPath.match("今天天气怎么样", installed))
        assertNull(AppFastPath.match("帮我计算 1+1", installed))
        assertNull(AppFastPath.match("打开一个未安装的神奇应用", installed))
    }

    @Test
    fun agentLoopExecutesPureOpenFastPathWithoutCallingModel() = kotlinx.coroutines.test.runTest {
        val screen = com.hana.agent.core.tool.FakeScreen()
        val tools = com.hana.agent.core.assembly.toolDispatcher(screen)
        val main = com.hana.agent.core.testing.fakeModel("main") {
            // 模型完全不需要被调用
        }
        val loop = AgentLoop(
            main, tools, SystemPrompt.identity(), maxSteps = 10,
            installedApps = { installed },
            flags = SpeedFlags(fastPath = true),
        )

        val end = loop.run("打开小红书")
        assertTrue(end is TaskState.Succeeded)
        assertEquals("已为你打开小红书。", (end as TaskState.Succeeded).answer)
        assertTrue(screen.calls.any { it.startsWith("open com.xingin.xhs") })
        assertEquals("主模型完全不应该被调用，以实现零延迟与零 Token", 0, main.requests.size)
    }

    @Test
    fun agentLoopCompositeCommandPreLaunchesAndLetsModelContinue() = kotlinx.coroutines.test.runTest {
        val screen = com.hana.agent.core.tool.FakeScreen()
        val tools = com.hana.agent.core.assembly.toolDispatcher(screen)
        val main = com.hana.agent.core.testing.fakeModel("main") {
            answer("搜索结果已展示")
        }
        val loop = AgentLoop(
            main, tools, SystemPrompt.identity(), maxSteps = 10,
            installedApps = { installed },
            flags = SpeedFlags(fastPath = true),
        )

        val end = loop.run("打开小红书，搜索减脂晚餐")
        assertTrue(end is TaskState.Succeeded)
        assertTrue(screen.calls.any { it.startsWith("open com.xingin.xhs") })
        assertEquals("主模型只需从已经拉起的前台接力运行 1 步", 1, main.requests.size)
        val firstReq = main.requests.first()
        val hasFastLaunchResult = firstReq.messages.any { msg ->
            msg.parts.any { it is com.hana.agent.core.model.Part.ToolResult && it.callId == "fast_launch_0" }
        }
        assertTrue(hasFastLaunchResult)
    }
}

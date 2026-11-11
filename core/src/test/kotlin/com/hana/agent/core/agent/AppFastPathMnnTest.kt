package com.hana.agent.core.agent

import com.hana.agent.core.edge.EdgeDecisionPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFastPathMnnTest {
    private val installed = listOf(
        "com.xingin.xhs" to "小红书",
        "com.tencent.mm" to "微信",
        "com.taobao.taobao" to "淘宝",
    )

    @Test
    fun mnnResolvesColloquialAliasesAndCompositeSearch() = runTest {
        val stubPort = object : EdgeDecisionPort {
            override val isReady: Boolean = true
            override suspend fun classifyIntent(task: String, recentContextSummary: String, confidenceThreshold: Float) = null
            override suspend fun pickObstacleDismissRef(userGoal: String, dialogClues: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun matchReplayCandidate(stepGoal: String, recordedLabel: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun resolveAppFastAction(
                task: String,
                installedApps: List<Pair<String, String>>,
            ): EdgeDecisionPort.ResolvedAppAction? {
                return if (task.contains("红薯") && task.contains("冬装")) {
                    EdgeDecisionPort.ResolvedAppAction("com.xingin.xhs", "小红书", searchQuery = "冬装穿搭")
                } else null
            }
        }

        val match = AppFastPath.match("去红薯搜一下冬装穿搭", installed, stubPort)
        assertNotNull(match)
        assertEquals("com.xingin.xhs", match!!.packageName)
        assertEquals("小红书", match.appLabel)
        assertFalse(match.isPureOpen)
        assertEquals("冬装穿搭", match.searchQuery)
    }

    @Test
    fun fallsBackToStandardRuleMatchWhenMnnNotReady() = runTest {
        val match = AppFastPath.match("打开微信", installed, EdgeDecisionPort.NONE)
        assertNotNull(match)
        assertEquals("com.tencent.mm", match!!.packageName)
        assertTrue(match.isPureOpen)
    }

    @Test
    fun enrichesDirectRuleMatchWithSearchQueryWhenEdgePortIsReady() = runTest {
        val stubPort = object : EdgeDecisionPort {
            override val isReady: Boolean = true
            override suspend fun classifyIntent(task: String, recentContextSummary: String, confidenceThreshold: Float) = null
            override suspend fun pickObstacleDismissRef(userGoal: String, dialogClues: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun matchReplayCandidate(stepGoal: String, recordedLabel: String, candidates: List<EdgeDecisionPort.EdgeCandidate>, confidenceThreshold: Float) = null
            override suspend fun resolveAppFastAction(
                task: String,
                installedApps: List<Pair<String, String>>,
            ): EdgeDecisionPort.ResolvedAppAction? {
                return if (task.contains("小红书") && task.contains("冬装")) {
                    EdgeDecisionPort.ResolvedAppAction("com.xingin.xhs", "小红书", searchQuery = "冬装穿搭")
                } else null
            }
        }

        // 包含已知前缀“去”和已知应用全称“小红书”，但属于非纯打开复合指令
        val match = AppFastPath.match("去小红书搜一下冬装穿搭", installed, stubPort)
        assertNotNull(match)
        assertEquals("com.xingin.xhs", match!!.packageName)
        assertEquals("小红书", match.appLabel)
        assertFalse(match.isPureOpen)
        assertEquals("冬装穿搭", match.searchQuery)
    }
}

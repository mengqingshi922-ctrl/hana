package com.hana.agent.core.prompt

import com.hana.agent.core.agent.SystemPrompt
import com.hana.agent.core.model.Author
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KvCachePrefixFreezeTest {

    @Test
    fun testTier0GlobalImmutablePrefixByteLevelEquality() {
        val identity = SystemPrompt.identity()
        val coreRules = listOf(
            "Rule 1: Never leak user credentials.",
            "Rule 2: Execute actions step by step with validation.",
            "Rule 3: Keep immutable prefix frozen for RadixAttention KV-Cache hit."
        )

        // 模拟 10 轮交互中的 Tier 0 静态系统提示词
        val turnPrefixes = (1..10).map {
            SystemPrompt.assemble(identity, coreRules)
        }

        assertEquals(10, turnPrefixes.size)
        // 校验 10 轮的 Tier 0 前缀达到 100% 字节级一致
        assertTrue(PrefixFreezeVerifier.verifyPrefixStability(turnPrefixes))
        assertEquals(1, turnPrefixes.distinct().size)

        // 确保不含任何易变状态
        turnPrefixes.forEach {
            StaticPrefixBlock.validateNoDynamicLeaks(it)
        }
    }

    @Test
    fun testTier1TaskLevelStaticContextStability() {
        val userGoal = "在网易云音乐中搜索并播放周杰伦的《晴天》"
        val toolSchema = """{"tools":["phone_tap","phone_type_text","phone_swipe"]}"""

        val staticBlockTurn1 = StaticPrefixBlock(
            systemPrompt = "You are Hana agent.",
            initialGoal = userGoal,
            toolSchemaJson = toolSchema,
            fewShotExamples = listOf("Task: Open settings -> phone_tap(@e1)")
        )

        val staticBlockTurn5 = StaticPrefixBlock(
            systemPrompt = "You are Hana agent.",
            initialGoal = userGoal,
            toolSchemaJson = toolSchema,
            fewShotExamples = listOf("Task: Open settings -> phone_tap(@e1)")
        )

        assertEquals(staticBlockTurn1, staticBlockTurn5)
        assertEquals(userGoal, staticBlockTurn5.initialGoal)
    }

    @Test
    fun testTier2AppendOnlyTextTrajectoryExcludesImages() {
        // Tier 2 历史轨迹必须严格仅包含文本操作与观测摘要，杜绝将大图像塞入永久历史
        val historyTurns = mutableListOf<Message>()

        // 模拟第 1 轮历史追加
        historyTurns.add(Message(Role.User, listOf(Part.Text("请帮我打开相册")), Author.User))
        historyTurns.add(Message(Role.Assistant, listOf(Part.Text("好的，正在打开相册")), Author.Hana))
        historyTurns.add(Message(Role.Tool, listOf(Part.ToolResult("call_1", """{"ok":true,"opened":"com.gallery"}""", false)), Author.Hana))

        // 模拟第 2 轮历史追加
        historyTurns.add(Message(Role.Assistant, listOf(Part.Text("已进入相册，寻找最新照片")), Author.Hana))
        historyTurns.add(Message(Role.Tool, listOf(Part.ToolResult("call_2", """{"ok":true,"target":"photo_item_1"}""", false)), Author.Hana))

        // 验证所有历史消息绝无 Part.Image
        for (msg in historyTurns) {
            assertTrue("Tier 2 trajectory must not contain Part.Image!", msg.parts.none { it is Part.Image })
        }
    }

    @Test
    fun testTier3EphemeralSuffixSingleShotImageDiscard() {
        // Tier 3 临时动态尾部：当前高分辨率截图仅在单步临时提供
        val ephemeralPart = Part.Image("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
        val currentObservation = Message(
            Role.User,
            listOf(Part.Text("当前屏幕状态"), ephemeralPart),
            Author.Hana
        )

        assertTrue(currentObservation.parts.any { it is Part.Image })

        // 决策产出后，将截图转化为文本摘要并永久归入 Tier 2 历史，同时彻底销毁图像 Token
        val compactedSummary = Part.Text("观察摘要：相册首页，展示 12 张照片缩略图，第一张为今天拍摄的风景照")
        val permanentHistoryMessage = Message(
            Role.User,
            listOf(compactedSummary),
            Author.Hana
        )

        assertFalse("Permanent history must discard image token", permanentHistoryMessage.parts.any { it is Part.Image })
        assertTrue((permanentHistoryMessage.parts.first() as Part.Text).text.contains("观察摘要"))
    }

    @Test
    fun testNoTimestampOrStepCounterLeakInPrefix() {
        val baseClean = "Clean prefix without dynamic state."

        // 动态时间戳泄漏检测
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock(systemPrompt = "$baseClean timestamp: 1731046400000")
        }

        // 日期泄漏检测
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock(systemPrompt = "$baseClean Date: 2026-11-08")
        }

        // 电量泄漏检测
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock(systemPrompt = "$baseClean battery: 85%")
        }

        // 会话 ID 泄漏检测
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock(systemPrompt = "$baseClean session-id: a1b2c3d4-e5f6-7890")
        }

        // 动态后缀正确隔离并承接这些易变状态
        val dynamicSuffix = DynamicSuffixBlock(
            nowMs = 1731046400000L,
            localTimeStr = "2026-11-08 14:15",
            batteryLevel = 85,
            extraRuntimeState = mapOf("step" to "3")
        )
        val suffixText = dynamicSuffix.toUserMessagePartText().text
        assertTrue(suffixText.contains("Timestamp: 1731046400000"))
        assertTrue(suffixText.contains("Battery: 85%"))
        assertTrue(suffixText.contains("step: 3"))
    }
}

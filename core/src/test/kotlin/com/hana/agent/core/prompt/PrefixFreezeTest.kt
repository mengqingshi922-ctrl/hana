package com.hana.agent.core.prompt

import com.hana.agent.core.agent.SystemPrompt
import com.hana.agent.core.model.Author
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PrefixFreezeTest {

    @Test
    fun testStaticPrefixBlockValidationSucceedsForCleanPrompt() {
        val cleanPrompt = """
            You are Hana, an autonomous phone AI agent.
            Rule 1: Always verify element bounds before clicking.
            Rule 2: Never disclose sensitive credentials.
        """.trimIndent()

        val block = StaticPrefixBlock(
            systemPrompt = cleanPrompt,
            initialGoal = "Search flight tickets from Beijing to Shanghai",
            toolSchemaJson = """{"tools":["phone_tap","phone_type_text"]}"""
        )

        assertEquals(cleanPrompt, block.systemPrompt)
        assertEquals("Search flight tickets from Beijing to Shanghai", block.initialGoal)
    }

    @Test
    fun testStaticPrefixBlockDetectsDynamicLeaks() {
        // 包含动态时间戳泄漏
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock("System prompt with timestamp=1731046400000")
        }

        // 包含动态日期泄漏
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock("System prompt with current date: 2026-11-08")
        }

        // 包含电量泄漏
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock("System prompt with battery: 85%")
        }

        // 包含临时会话 ID 泄漏
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock("System prompt with session-id: a1b2c3d4-e5f6-7890")
        }
    }

    @Test
    fun testDynamicSuffixBlockFormattingAndAttachment() {
        val suffix = DynamicSuffixBlock(
            nowMs = 1731046400000L,
            localTimeStr = "2026-11-08 14:15",
            currentPlace = "com.coolapk.market",
            batteryLevel = 92,
            networkType = "Wi-Fi",
            screenDiff = "+@e5 [按钮:更新]",
            extraRuntimeState = mapOf("step" to "3", "fps" to "60")
        )

        val part = suffix.toUserMessagePartText()
        assertTrue(part.text.contains("Local time: 2026-11-08 14:15"))
        assertTrue(part.text.contains("Timestamp: 1731046400000"))
        assertTrue(part.text.contains("Current place: com.coolapk.market"))
        assertTrue(part.text.contains("Battery: 92%"))
        assertTrue(part.text.contains("Network: Wi-Fi"))
        assertTrue(part.text.contains("Screen diff: +@e5 [按钮:更新]"))
        assertTrue(part.text.contains("step: 3"))

        // 测试附加到末尾 User 消息
        val history = listOf(
            Message(Role.User, listOf(Part.Text("帮我搜索应用")), Author.User),
            Message(Role.Assistant, listOf(Part.Text("好的，正在打开酷安")), Author.Mika),
            Message(Role.User, listOf(Part.Text("第二轮用户消息")), Author.User)
        )

        val updated = suffix.attachToLastUserMessage(history)
        assertEquals(3, updated.size)
        assertEquals(2, updated.last().parts.size)
        assertEquals("第二轮用户消息", (updated.last().parts[0] as Part.Text).text)
        assertTrue((updated.last().parts[1] as Part.Text).text.contains("[Runtime Dynamic Suffix]"))

        // 测试若末尾不是 User 消息，则作为新的 User 消息追加
        val assistantOnlyHistory = listOf(
            Message(Role.Assistant, listOf(Part.Text("请问还需要什么？")), Author.Mika)
        )
        val appended = suffix.attachToLastUserMessage(assistantOnlyHistory)
        assertEquals(2, appended.size)
        assertEquals(Role.User, appended.last().role)
    }

    @Test
    fun testPrefixFreezeVerifierAcrossTurns() {
        val staticPrefixTurn1 = "System Identity\n\nCore Rules\n\nTool Schemas"
        val staticPrefixTurn2 = "System Identity\n\nCore Rules\n\nTool Schemas"
        val staticPrefixTurn3 = "System Identity\n\nCore Rules\n\nTool Schemas"

        assertTrue(PrefixFreezeVerifier.verifyPrefixStability(listOf(staticPrefixTurn1, staticPrefixTurn2, staticPrefixTurn3)))

        val mutatedTurn3 = "System Identity\n\nCore Rules\n\nTool Schemas [Mutated]"
        assertFalse(PrefixFreezeVerifier.verifyPrefixStability(listOf(staticPrefixTurn1, staticPrefixTurn2, mutatedTurn3)))
    }

    @Test
    fun testSystemPromptAssembleIsCleanAndDeterministic() {
        val id = SystemPrompt.identity()
        val assembled = SystemPrompt.assemble(id, listOf("Section 1", "Section 2"))

        // 验证产出的静态提示词严格不含动态泄漏
        StaticPrefixBlock.validateNoDynamicLeaks(assembled)
        assertEquals(assembled, SystemPrompt.assemble(id, listOf("Section 1", "Section 2")))
    }

    @Test
    fun testStaticPrefixBlockValidatesAllFieldsAgainstLeaks() {
        val clean = "Clean System Prompt"

        // 1. initialGoal 泄漏拦截
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock(clean, initialGoal = "Goal at 2026-11-08 with battery: 80%")
        }

        // 2. toolSchemaJson 泄漏拦截（支持 JSON 键值引号）
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock(clean, toolSchemaJson = """{"session-id": "abcd-1234", "timestamp": 1731046400000}""")
        }

        // 3. fewShotExamples 泄漏拦截
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock(clean, fewShotExamples = listOf("Example with date 2026-11-08 14:15:00"))
        }
    }

    @Test
    fun testStaticPrefixBlockDetectsIso8601DateLeak() {
        assertThrows(IllegalArgumentException::class.java) {
            StaticPrefixBlock("System prompt with ISO date: 2026-11-08T14:15:00Z")
        }
    }

    @Test
    fun testDynamicSuffixBlockAttachToEmptyHistoryPreservesSuffix() {
        val suffix = DynamicSuffixBlock(
            nowMs = 1731046400000L,
            localTimeStr = "2026-11-08 14:15"
        )
        val result = suffix.attachToLastUserMessage(emptyList())
        assertEquals(1, result.size)
        assertEquals(Role.User, result.first().role)
        assertTrue((result.first().parts.first() as Part.Text).text.contains("[Runtime Dynamic Suffix]"))
    }
}

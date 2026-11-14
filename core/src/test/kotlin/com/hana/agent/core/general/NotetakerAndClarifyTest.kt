package com.hana.agent.core.general

import com.hana.agent.core.memory.TaskNotePad
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.EvidenceLog
import com.hana.agent.core.tool.Outcome
import com.hana.agent.core.tool.ToolConfig
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolIndex
import com.hana.agent.core.visual.Shots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mobile-Agent-v3 结构化笔记槽与 MAI-UI 主动澄清歧义工具单元测试。
 */
class NotetakerAndClarifyTest {

    private fun createTestContext(conversationId: String = "conv_test_1"): ToolContext {
        val cfg = ToolConfig(
            visionEnabled = false,
            skills = null,
            recordFrames = false,
            vision = null,
            inlineScreen = false,
            adaptiveVision = false,
            memory = null,
            profile = null,
            utterances = null,
            conversationId = conversationId,
            imageOps = null,
        )
        return ToolContext(cfg, EvidenceLog(), ToolIndex(emptyList()), null, Shots())
    }

    @Before
    @After
    fun cleanup() {
        TaskNotePad.clearAll()
    }

    @Test
    fun testTaskNotePadDeduplicationAndCategories() {
        val pad = TaskNotePad()
        assertTrue(pad.append("price", "iPhone 16", "5999"))
        assertTrue(pad.append("price", "Pixel 9", "5499"))
        // 同 key 更新内容，应覆盖且返回 false（非全新增）
        assertFalse(pad.append("price", "iPhone 16", "5899"))
        assertTrue(pad.append("contact", "Alice", "13800000000"))

        assertEquals(3, pad.totalEntriesCount)
        assertEquals(listOf("price", "contact"), pad.categories())

        val priceNotes = pad.read("price")
        assertEquals(2, priceNotes.size)
        assertEquals("5899", priceNotes.first { it.key == "iPhone 16" }.value)

        val allNotes = pad.read()
        assertEquals(3, allNotes.size)

        val summary = pad.renderCompactSummary()
        assertTrue(summary.contains("### price"))
        assertTrue(summary.contains("- iPhone 16: 5899"))
        assertTrue(summary.contains("### contact"))
        assertTrue(summary.contains("- Alice: 13800000000"))
    }

    @Test
    fun testTaskNotePadConcurrency() = runBlocking {
        val pad = TaskNotePad()
        val jobs = (1..50).map { i ->
            async(Dispatchers.Default) {
                pad.append("category_${i % 5}", "key_$i", "value_$i")
            }
        }
        jobs.awaitAll()
        assertEquals(50, pad.totalEntriesCount)
        assertEquals(5, pad.categories().size)
    }

    @Test
    fun testNoteAppendAndReadTools() = runBlocking {
        val ctx = createTestContext("conv_note_1")

        // 1. 记笔记 note_append
        val appendArgs = Args(NoteAppend.def, buildJsonObject {
            put("category", "price")
            put("key", "MacBook Pro")
            put("value", "$1999")
        })
        val execAppend = NoteAppend.execute(appendArgs, ctx)
        assertTrue(execAppend.content["ok"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals(1, execAppend.content["totalNotes"]?.jsonPrimitive?.intOrNull)

        // 2. 读笔记 note_read
        val readArgs = Args(NoteRead.def, buildJsonObject {
            put("category", "price")
        })
        val execRead = NoteRead.execute(readArgs, ctx)
        assertTrue(execRead.content["ok"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals(1, execRead.content["count"]?.jsonPrimitive?.intOrNull)
        val notesArray = execRead.content["notes"]?.jsonArray
        assertNotNull(notesArray)
        assertEquals(1, notesArray!!.size)
        assertTrue(execRead.content["summary"]?.jsonPrimitive?.content.orEmpty().contains("MacBook Pro"))
    }

    @Test
    fun testClarifyUserTool() = runBlocking {
        val ctx = createTestContext("conv_clarify_1")
        val args = Args(ClarifyUserTool.def, buildJsonObject {
            put("question", "请问您要预订哪个时间段的高铁票？")
            put("options", buildJsonArray {
                add(JsonPrimitive("上午 (08:00 - 12:00)"))
                add(JsonPrimitive("下午 (12:00 - 18:00)"))
                add(JsonPrimitive("晚上 (18:00 后)"))
            })
            put("allowFreeText", true)
            put("missingConstraint", "departure_time_window")
        })

        val execution = ClarifyUserTool.execute(args, ctx)
        assertTrue(execution.content["asked"]?.jsonPrimitive?.booleanOrNull == true)
        assertTrue(execution.content["clarification"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals("departure_time_window", execution.content["missingConstraint"]?.jsonPrimitive?.content)

        val outcome = execution.outcome
        assertTrue(outcome is Outcome.Input)
        val input = outcome as Outcome.Input
        assertEquals("请问您要预订哪个时间段的高铁票？", input.prompt)
        assertEquals(3, input.options?.size)
        assertEquals("上午 (08:00 - 12:00)", input.options?.first())
    }
}

package com.hana.agent.core.agent

import com.hana.agent.core.memory.FlushTriggerReason
import com.hana.agent.core.memory.MemoryFlushHook
import com.hana.agent.core.memory.MemoryPersistence
import com.hana.agent.core.memory.MemoryStore
import com.hana.agent.core.memory.PreCompactFlushRequest
import com.hana.agent.core.memory.StoreBackedMemoryFlushHook
import com.hana.agent.core.memory.TemporalFactGraph
import com.hana.agent.core.model.Author
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import com.hana.agent.core.testing.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFlushHookTest {
    private var seq = 0

    private fun tapRound(note: String = "x".repeat(400)): List<Message> {
        val id = "c${++seq}"
        return listOf(
            Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", """{"target":"按钮"}""")), Author.Hana),
            Message(Role.Tool, listOf(Part.ToolResult(id, """{"ok":true,"pad":"$note"}""", false)), Author.Hana),
        )
    }

    @Test
    fun contextCompressorFiresFlushHookBeforePruningAt80PercentWatermarkAndCompaction() {
        var now = 1_000L
        var memSeq = 0
        val persistence = object : MemoryPersistence {
            var stored = emptyList<com.hana.agent.core.memory.MemoryItem>()
            override fun load() = stored
            override fun save(items: List<com.hana.agent.core.memory.MemoryItem>) { stored = items }
        }
        val store = MemoryStore(persistence, clock = { now }, ids = { "m${++memSeq}" })
        val graph = TemporalFactGraph(clock = { now })
        val hookCalls = mutableListOf<PreCompactFlushRequest>()

        val delegate = StoreBackedMemoryFlushHook(memoryStore = store, factGraph = graph, conversationId = "conv-1")
        val hook = MemoryFlushHook { req ->
            hookCalls += req
            delegate.onPreCompact(req)
        }

        val userTurn = Message(
            Role.User,
            listOf(Part.Text("记住以后点外卖不要香菜。收货地址是张江高科路88号。")),
            Author.User,
        )
        // 制造足够长的历史触发 80% 水位与折叠压缩
        val history = listOf(userTurn) + (1..25).flatMap { tapRound() }

        val compressor = ContextCompressor(TOOLS, keepRecent = 6, memoryFlushHook = hook)
        // 选用 4000 contextTokens (usable = 3000, trigger = 2250, 80% = 2400 -> 超过 trigger 直接压)
        val compressed = compressor.compress(history, contextTokens = 4_000)

        assertTrue(compressed.rewritten)
        assertTrue(hookCalls.isNotEmpty())
        assertEquals(FlushTriggerReason.Compaction, hookCalls.first().reason)
        // 关键用户偏好与地址已在压缩前刷写持久化到 MemoryStore 和 TemporalFactGraph
        assertTrue(store.items.value.any { "不要香菜" in it.content })
        assertTrue(store.items.value.any { "张江高科路88号" in it.content })
        assertEquals("张江高科路88号", graph.queryActive("用户", "常用地址").single().objectValue)
    }

    @Test
    fun contextCompressorFiresAt80PercentBeforeHardTrigger() {
        val hookCalls = mutableListOf<PreCompactFlushRequest>()
        val compressor = ContextCompressor(
            tools = TOOLS,
            keepRecent = 6,
            memoryFlushHook = MemoryFlushHook { hookCalls += it },
        )
        val history = listOf(
            Message(Role.User, listOf(Part.Text("以后都用微信付款")), Author.User),
        ) + (1..4).flatMap { tapRound("x".repeat(200)) }

        // contextTokens = 100_000 -> usable = 80_000, trigger = 67_000, 80% = 64_000
        // 传入 currentTokens = 65_000（达到 80% 水位但尚未到达 67_000 trigger）
        val res = compressor.compress(history, contextTokens = 100_000, currentTokens = 65_000)
        assertEquals(false, res.rewritten)
        assertEquals(1, hookCalls.size)
        assertEquals(FlushTriggerReason.Watermark80, hookCalls.single().reason)
    }

    @Test
    fun historyPrunerInvokesPrePruneHookBeforeCutting() {
        var flushed = false
        val persisted = object : MemoryPersistence {
            var items = emptyList<com.hana.agent.core.memory.MemoryItem>()
            override fun load() = items
            override fun save(items: List<com.hana.agent.core.memory.MemoryItem>) { this.items = items }
        }
        val store = MemoryStore(persisted, clock = { 5_000L }, ids = { "id1" })
        val hook = MemoryFlushHook { req ->
            flushed = true
            assertEquals(FlushTriggerReason.HistoryPrune, req.reason)
            StoreBackedMemoryFlushHook(memoryStore = store).onPreCompact(req)
        }

        val h = listOf(
            Message(Role.User, listOf(Part.Text("记住以后外卖不要辣")), Author.User),
        ) + (1..5).flatMap { tapRound() }

        HistoryPruner.prune(h, TOOLS, HistoryPruner.Cut(2), prePruneHook = hook)
        assertTrue(flushed)
        assertTrue(store.items.value.any { "不要辣" in it.content })
    }
}

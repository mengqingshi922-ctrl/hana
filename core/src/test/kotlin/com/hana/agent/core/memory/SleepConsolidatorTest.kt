package com.hana.agent.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SleepConsolidatorTest {

    @Test
    fun deduplicateMemoriesMergesStatsAndRemovesRedundantEntries() {
        var now = 1_000L
        var seq = 0
        val persistence = object : MemoryPersistence {
            var items = emptyList<MemoryItem>()
            override fun load() = items
            override fun save(items: List<MemoryItem>) { this.items = items }
        }
        val store = MemoryStore(persistence, clock = { now }, ids = { "m${++seq}" })
        val src = MemorySource("manual", atMs = now)

        val a = store.add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), src).getOrThrow().item
        now = 2_000L
        val b = store.add(MemoryKind.preference, "用户点外卖不要香菜！", listOf("忌口"), src).getOrThrow().item
        store.markAccessed(listOf(a.id))
        store.markUsed(listOf(b.id))
        store.setPinned(b.id, true)

        val c = store.add(MemoryKind.fact, "用户住在上海张江", listOf("住址"), src).getOrThrow().item

        val removed = SleepConsolidator.deduplicateMemories(store)
        assertEquals(1, removed)
        assertEquals(2, store.items.value.size)

        val keptPref = store.items.value.first { it.kind == MemoryKind.preference }
        assertTrue(keptPref.pinned)
        assertEquals(setOf("外卖", "忌口"), keptPref.keywords.toSet())
        assertTrue(keptPref.accessCount >= 2)
        assertTrue(keptPref.useCount >= 1)
        assertTrue(store.items.value.any { it.id == c.id })
    }

    @Test
    fun mineRoutinesDetectsFrequentAppSequencesAndTimeWindows() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun morningTs(day: Int) = LocalDateTime.of(2026, 11, day, 8, 30).atZone(zone).toInstant().toEpochMilli()

        val tasks = listOf(
            TaskMemory(
                id = "t1",
                goal = "买咖啡并微信通知同事",
                conclusion = "已下单并发送通知",
                apps = listOf("com.lucky.luckyclient", "com.tencent.mm"),
                timestamp = morningTs(10),
                success = true,
            ),
            TaskMemory(
                id = "t2",
                goal = "早上点瑞幸并在微信群发消息",
                conclusion = "已完成",
                apps = listOf("com.lucky.luckyclient", "com.tencent.mm"),
                timestamp = morningTs(11),
                success = true,
            ),
            TaskMemory(
                id = "t3",
                goal = "早晨瑞幸下单后发微信",
                conclusion = "已完成",
                apps = listOf("com.lucky.luckyclient", "com.tencent.mm"),
                timestamp = morningTs(12),
                success = true,
            ),
            // 失败任务不应计入 Routine
            TaskMemory(
                id = "t_fail",
                goal = "测试失败流",
                conclusion = "失败",
                apps = listOf("com.example.a", "com.example.b"),
                timestamp = morningTs(12),
                success = false,
            ),
        )

        val routines = SleepConsolidator.mineRoutines(tasks, minOccurrences = 2, zoneId = zone)
        assertEquals(1, routines.size)
        val r = routines.single()
        assertEquals(listOf("com.lucky.luckyclient", "com.tencent.mm"), r.appSequence)
        assertEquals(3, r.occurrences)
        assertEquals("早间 (06:00-10:00)", r.timeWindow)
        assertTrue(r.confidence >= 0.85)
    }
}

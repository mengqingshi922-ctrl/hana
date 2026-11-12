package com.hana.agent.memory

import com.hana.agent.core.memory.MarkdownMemoryFile
import com.hana.agent.core.memory.MemoryItem
import com.hana.agent.core.memory.MemoryKind
import com.hana.agent.core.memory.MemoryPersistence
import com.hana.agent.core.memory.MemorySource
import com.hana.agent.core.memory.MemoryStore
import com.hana.agent.core.memory.TaskMemory
import com.hana.agent.core.memory.TaskMemoryStore
import com.hana.agent.core.memory.TemporalFactGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class SleepConsolidationWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun workerDeduplicatesMinesRoutinesRendersMarkdownAndRespectsIdleCooldown() = runTest {
        var now = 1_760_000_000_000L
        var seq = 0
        val zone = ZoneId.of("Asia/Shanghai")
        val persistence = object : MemoryPersistence {
            var stored = emptyList<MemoryItem>()
            override fun load() = stored
            override fun save(items: List<MemoryItem>) { stored = items }
        }
        val memStore = MemoryStore(persistence, clock = { now }, ids = { "m${++seq}" })
        val src = MemorySource("manual", atMs = now)
        memStore.add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), src).getOrThrow()
        memStore.add(MemoryKind.preference, "用户点外卖不要香菜！", listOf("忌口"), src).getOrThrow()

        val taskStore = TaskMemoryStore(File(tmp.root, "task_memory.json"), clock = { now })
        fun evening(day: Int) = LocalDateTime.of(2026, 11, day, 20, 0).atZone(zone).toInstant().toEpochMilli()
        taskStore.add(
            TaskMemory(
                id = "tm1",
                goal = "晚上淘宝买零食并支付宝付款",
                conclusion = "已下单",
                apps = listOf("com.taobao.taobao", "com.eg.android.AlipayGphone"),
                timestamp = evening(10),
            )
        )
        taskStore.add(
            TaskMemory(
                id = "tm2",
                goal = "晚上淘宝买日用品并支付宝支付",
                conclusion = "已下单",
                apps = listOf("com.taobao.taobao", "com.eg.android.AlipayGphone"),
                timestamp = evening(11),
            )
        )

        val factGraph = TemporalFactGraph(File(tmp.root, "fact_graph.json"), clock = { now })
        factGraph.assertFact("用户", "收货地址", "张江高科")

        val mdFile = MarkdownMemoryFile(File(tmp.root, "MEMORY.md"))
        val routinesFile = File(tmp.root, "routines.json")
        val worker = SleepConsolidationWorker(
            memoryStore = memStore,
            taskMemoryStore = taskStore,
            factGraph = factGraph,
            markdownFile = mdFile,
            routinesFile = routinesFile,
            minIntervalMs = 60_000L,
            zoneId = zone,
            clock = { now },
            ioDispatcher = Dispatchers.Unconfined,
        )

        // 非空闲不触发
        assertNull(worker.onIdleTick(isDeviceIdle = false, nowMs = now))

        // 空闲触发整合
        val report = worker.onIdleTick(isDeviceIdle = true, nowMs = now)
        assertNotNull(report)
        assertEquals(1, report!!.deduplicatedCount)
        assertEquals(1, memStore.items.value.size)
        assertEquals(1, report.routinesDetected.size)
        assertEquals(listOf("com.taobao.taobao", "com.eg.android.AlipayGphone"), report.routinesDetected.single().appSequence)

        val mdText = mdFile.readOrNull()!!
        assertTrue(mdText.contains("# Hana Memory (MEMORY.md)"))
        assertTrue(mdText.contains("张江高科"))
        assertTrue(mdText.contains("Taobao → AlipayGphone"))
        assertTrue(routinesFile.exists())

        // 冷却期内即使空闲也不重复运行
        assertNull(worker.onIdleTick(isDeviceIdle = true, nowMs = now + 10_000L))

        // 超过冷却期后允许再次执行
        val report2 = worker.onIdleTick(isDeviceIdle = true, nowMs = now + 120_000L)
        assertNotNull(report2)
    }
}

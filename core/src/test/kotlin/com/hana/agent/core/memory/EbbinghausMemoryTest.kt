package com.hana.agent.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.exp

class EbbinghausMemoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dayMs = 86_400_000L

    @Test
    fun retentionFollowsEbbinghausCurveWithFloor() {
        val t0 = 1_700_000_000_000L
        assertEquals(1.0, EbbinghausRetention.retention(t0, t0, strength = 2.0), 1e-9)
        assertEquals(exp(-1.0 / 2.0), EbbinghausRetention.retention(t0, t0 + dayMs, strength = 2.0), 1e-9)
        // 很久未访问触底保持 0.3
        assertEquals(0.3, EbbinghausRetention.retention(t0, t0 + 30 * dayMs, strength = 1.0), 1e-9)
    }

    @Test
    fun memoryIndexAppliesTimeDecayAndHigherStrengthDecaysSlower() {
        val now = 10_000_000_000L
        val src = MemorySource("manual", atMs = 1L)
        val staleWeak = MemoryItem(
            id = "stale",
            kind = MemoryKind.preference,
            content = "用户点外卖不要香菜",
            keywords = listOf("外卖"),
            source = src,
            status = MemoryStatus.active,
            createdAt = now - 5 * dayMs,
            updatedAt = now - 5 * dayMs,
            lastAccessedAt = now - 5 * dayMs,
            strength = 1.0,
        )
        val freshStrong = MemoryItem(
            id = "fresh",
            kind = MemoryKind.preference,
            content = "用户点外卖不要香菜",
            keywords = listOf("外卖"),
            source = src,
            status = MemoryStatus.active,
            createdAt = now - 5 * dayMs,
            updatedAt = now,
            lastAccessedAt = now,
            strength = 5.0,
        )

        val hits = MemoryIndex(listOf(staleWeak, freshStrong), nowMs = now).search("点外卖不要香菜", limit = 2)
        assertEquals(listOf("fresh", "stale"), hits.map { it.first.id })
        assertTrue(hits[0].second > hits[1].second * 2.5)
    }

    @Test
    fun storeSearchReinforcesStrengthAndAccessStats() {
        var now = 1_000_000_000L
        var seq = 0
        val archiveFile = File(tempFolder.root, "mem_archive.json")
        val persistence = object : MemoryPersistence {
            var items = emptyList<MemoryItem>()
            override fun load() = items
            override fun save(items: List<MemoryItem>) { this.items = items }
        }
        val store = MemoryStore(
            persistence = persistence,
            clock = { now },
            ids = { "m${++seq}" },
            limit = 10,
            archivePersistence = FileMemoryArchivePersistence(archiveFile),
        )
        val src = MemorySource("manual", atMs = now)
        val added = store.add(MemoryKind.preference, "用户喝咖啡不加糖", listOf("咖啡"), src).getOrThrow().item
        assertEquals(0, added.accessCount)
        assertEquals(EbbinghausRetention.DEFAULT_STRENGTH, added.strength, 1e-9)

        now += 2 * dayMs
        val hits = store.search("咖啡", limit = 5)
        assertEquals(1, hits.size)
        val updated = store.items.value.single()
        assertEquals(1, updated.accessCount)
        assertEquals(now, updated.lastAccessedAt)
        assertTrue("间隔 2 天命中应明显强化 strength", updated.strength > added.strength + 1.0)
    }

    @Test
    fun reachingLimitGracefullyArchivesLowScoreOldMemoriesInsteadOfRejecting() {
        var now = 1_000_000_000L
        var seq = 0
        val archiveFile = File(tempFolder.root, "archive.json")
        val memFile = object : MemoryPersistence {
            var items = emptyList<MemoryItem>()
            override fun load() = items
            override fun save(items: List<MemoryItem>) { this.items = items }
        }
        val store = MemoryStore(
            persistence = memFile,
            clock = { now },
            ids = { "m${++seq}" },
            limit = 2,
            archivePersistence = FileMemoryArchivePersistence(archiveFile),
        )
        val src = MemorySource("manual", atMs = now)
        val m1 = store.add(MemoryKind.fact, "老旧且少用的事实", listOf("老旧"), src).getOrThrow().item
        now += dayMs
        val m2 = store.add(MemoryKind.fact, "常用的事实", listOf("常用"), src).getOrThrow().item
        store.markAccessed(listOf(m2.id))

        now += 10 * dayMs
        val m3 = store.add(MemoryKind.fact, "新来的重要事实", listOf("新事实"), src).getOrThrow().item

        // 活跃区保留 limit=2 条（m2 和 m3），m1 优雅转入冷归档文件
        assertEquals(setOf(m2.id, m3.id), store.items.value.map { it.id }.toSet())
        val archived = store.archivedItems()
        assertEquals(listOf(m1.id), archived.map { it.id })
        assertTrue(archiveFile.exists())

        // 冷归档可检索、可按需恢复回活跃区
        assertEquals(listOf(m1.id), store.searchArchive("老旧").map { it.first.id })
        val restored = store.restoreFromArchive(m1.id).getOrThrow()
        assertEquals(1, restored.accessCount)
        assertTrue(store.items.value.any { it.id == m1.id })
        assertEquals(2, store.items.value.size)
    }

    @Test
    fun taskMemoryDecayReinforcementAndColdArchive() {
        var now = 1_000_000_000L
        val file = File(tempFolder.root, "task_memory.json")
        val store = TaskMemoryStore(file = file, limit = 2, clock = { now })

        val tOld = TaskMemory(
            id = "tm_old",
            goal = "在淘宝买纸巾",
            conclusion = "已下单清风纸巾",
            apps = listOf("com.taobao.taobao"),
            timestamp = now,
        )
        store.add(tOld)

        now += dayMs
        val tHot = TaskMemory(
            id = "tm_hot",
            goal = "在美团点瑞幸咖啡",
            conclusion = "已下单生椰拿铁",
            apps = listOf("com.sankuai.meituan"),
            timestamp = now,
        )
        store.add(tHot)
        // 检索命中 tHot，使其强度和访问次数上升
        now += dayMs
        val hits = store.search("瑞幸咖啡")
        assertEquals(1, hits.size)
        assertEquals(1, hits.single().accessCount)
        assertTrue(hits.single().strength > TaskMemory(id = "", goal = "", conclusion = "", timestamp = 0L).strength)

        // 写入第 3 条超出 limit=2：低分老旧的 tm_old 迁入冷归档，高频 tm_hot 与新条保留
        now += 5 * dayMs
        val tNew = TaskMemory(
            id = "tm_new",
            goal = "在京东买显示器",
            conclusion = "已比价加入购物车",
            apps = listOf("com.jingdong.app.mall"),
            timestamp = now,
        )
        store.add(tNew)

        assertNull(store.get("tm_old"))
        assertNotNull(store.get("tm_hot"))
        assertNotNull(store.get("tm_new"))
        assertEquals(listOf("tm_old"), store.archived().map { it.id })
        assertEquals(listOf("tm_old"), store.searchArchive("纸巾").map { it.id })
    }
}

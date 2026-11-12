package com.hana.agent.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

class MarkdownMemoryProjectionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun renderAndSyncEditsRoundTrip() {
        var now = 1_760_000_000_000L
        var seq = 0
        val persistence = object : MemoryPersistence {
            var stored = emptyList<MemoryItem>()
            override fun load() = stored
            override fun save(items: List<MemoryItem>) { stored = items }
        }
        val store = MemoryStore(persistence, clock = { now }, ids = { "m${++seq}" })
        val src = MemorySource("manual", atMs = now)
        val factItem = store.add(MemoryKind.fact, "用户住在浦东", listOf("住址"), src).getOrThrow().item
        store.setPinned(factItem.id, true)
        val prefItem = store.add(MemoryKind.preference, "点外卖不要香菜", listOf("外卖", "忌口"), src).getOrThrow().item

        val graph = TemporalFactGraph(clock = { now }).apply {
            assertFact("用户", "居住地", "浦东", validFrom = now)
        }
        val tasks = listOf(
            TaskMemory(
                id = "tm_1",
                goal = "在美团点午餐",
                conclusion = "已下单黄焖鸡米饭（不要香菜）",
                apps = listOf("com.sankuai.meituan"),
                timestamp = now,
                success = true,
            )
        )
        val routines = listOf(
            RoutineSuggestion(
                id = "rt_1",
                title = "午间习惯：Meituan → Alipay",
                appSequence = listOf("com.sankuai.meituan", "com.eg.android.AlipayGphone"),
                occurrences = 3,
                confidence = 0.9,
                timeWindow = "午间 (10:00-14:00)",
                suggestedTrigger = "一键自动点餐支付",
                detectedAtMs = now,
            )
        )

        val md = MarkdownMemoryProjection.render(
            items = store.items.value,
            facts = graph.allFacts(),
            tasks = tasks,
            routines = routines,
            nowMs = now,
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        assertTrue(md.contains("# Hana Memory (MEMORY.md)"))
        assertTrue(md.contains("[置顶] 用户住在浦东"))
        assertTrue(md.contains("点外卖不要香菜"))
        assertTrue(md.contains("**用户** —[居住地]→ **浦东**"))
        assertTrue(md.contains("在美团点午餐"))
        assertTrue(md.contains("午间习惯：Meituan → Alipay"))

        // 模拟人类直接编辑 MEMORY.md：修改一条、新增一条、删除偏好条
        val edited = md
            .replace("用户住在浦东", "用户住在张江高科")
            .replace(
                "## 偏好 (Preferences)\n",
                "## 偏好 (Preferences)\n- 用户喜欢喝冰美式 _(标签: 咖啡, 饮品)_\n",
            )
            .lines()
            .filterNot { prefItem.id in it }
            .joinToString("\n")

        val mdFile = MarkdownMemoryFile(File(tempFolder.root, "MEMORY.md"))
        mdFile.write(edited)
        val loadedMd = mdFile.readOrNull()!!

        val sync = MarkdownMemoryProjection.syncEditsToStore(loadedMd, store, deleteMissing = true)
        assertEquals(1, sync.updated)
        assertEquals(1, sync.added)
        assertEquals(1, sync.deleted)

        val contents = store.items.value.map { it.content }.toSet()
        assertEquals(setOf("用户住在张江高科", "用户喜欢喝冰美式"), contents)
        assertTrue(store.items.value.first { it.content == "用户住在张江高科" }.pinned)
    }
}

package com.hana.agent.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemporalFactGraphTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun preferenceUpdateSoftInvalidatesOldFactWithoutHardDeletion() {
        var now = 1_000L
        var seq = 0
        val file = File(tempFolder.root, "fact_graph.json")
        val graph = TemporalFactGraph(file = file, clock = { now }, ids = { "f${++seq}" })

        val oldAddr = graph.assertFact("用户", "收货地址", "浦东陆家嘴88号", validFrom = 1_000L)
        val diet = graph.assertFact("用户", "忌口", "不要香菜", validFrom = 1_500L)

        now = 2_000L
        val newAddr = graph.assertFact("用户", "收货地址", "张江高科路100号", validFrom = 2_000L)

        // 新事实有效，旧事实保留在图谱中且标记了 invalidAt 与 supersededBy
        val activeAddr = graph.queryActive(subject = "用户", relation = "收货地址")
        assertEquals(listOf("张江高科路100号"), activeAddr.map { it.objectValue })

        val history = graph.historyOf("用户", "收货地址")
        assertEquals(2, history.size)
        assertEquals(oldAddr.id, history[0].id)
        assertFalse(history[0].isActive)
        assertEquals(2_000L, history[0].invalidAt)
        assertEquals(newAddr.id, history[0].supersededBy)
        assertTrue(history[1].isActive)
        assertNull(history[1].invalidAt)

        // 时点快照回溯：t=1200 时旧地址仍有效；t=2500 时新地址有效
        val snapOld = graph.snapshotAt(1_200L)
        assertEquals(listOf("浦东陆家嘴88号"), snapOld.map { it.objectValue })
        val snapNew = graph.snapshotAt(2_500L)
        assertEquals(setOf("张江高科路100号", "不要香菜"), snapNew.map { it.objectValue }.toSet())
        assertNotNull(diet)

        // 重新加载 JSON 文件验证持久化与序列化格式
        val reloaded = TemporalFactGraph(file = file)
        assertEquals(3, reloaded.allFacts().size)
        assertEquals(2, reloaded.queryActive().size)
        assertTrue(file.readText().contains("\"object\""))
    }

    @Test
    fun entityAssociatedMultiHopRecallFindsConnectedFacts() {
        val graph = TemporalFactGraph(clock = { 10_000L })
        graph.assertFact("用户", "常点外卖餐厅", "蜀大侠火锅")
        graph.assertFact("蜀大侠火锅", "推荐菜品", "鲜毛肚")
        graph.assertFact("用户", "忌口", "不吃香菜")
        graph.assertFact("同事小王", "居住城市", "杭州")

        // 查询“蜀大侠火锅”应召回直接相关边 + 2跳关联到的用户及忌口信息，不召回无关的杭州
        val recalled = graph.relatedFacts("帮我在蜀大侠火锅点个菜", hops = 2, limit = 10)
        val objs = recalled.map { it.objectValue }
        assertTrue("蜀大侠火锅" in objs)
        assertTrue("鲜毛肚" in objs)
        assertTrue("不吃香菜" in objs)
        assertFalse("杭州" in objs)
    }

    @Test
    fun invalidateWhereMarksMultiValuedPreferenceInvalid() {
        var now = 100L
        val graph = TemporalFactGraph(clock = { now })
        graph.assertFact("用户", "忌口", "香菜", supersedeExisting = false)
        graph.assertFact("用户", "忌口", "折耳根", supersedeExisting = false)

        now = 200L
        val invalidated = graph.invalidateWhere("用户", "忌口", "香菜")
        assertEquals(1, invalidated)
        assertEquals(listOf("折耳根"), graph.queryActive("用户", "忌口").map { it.objectValue })
        assertEquals(2, graph.allFacts().size)
    }
}

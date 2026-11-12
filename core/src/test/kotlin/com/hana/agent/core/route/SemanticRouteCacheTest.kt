package com.hana.agent.core.route

import com.hana.agent.core.action.ChainPredicateKind
import com.hana.agent.core.action.MacroStep
import com.hana.agent.core.experience.UiExperienceEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SemanticRouteCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun persistsUiExperienceGraphAndRecoversOnColdStart() = runTest {
        val file = File(tempFolder.root, "semantic_route_cache.json")
        val appId = "com.taobao.taobao"
        val pageSig = "sig_taobao_cart"

        val cache1 = SemanticRouteCache(file)
        cache1.recordSuccess(
            UiExperienceEntry(
                appId = appId,
                pageSignature = pageSig,
                intentKeyword = "打开淘宝购物车并点击去结算按钮",
                macroActions = listOf(
                    MacroStep("phone_tap", buildJsonObject { put("ref", "@e_checkout") }, ChainPredicateKind.NONE),
                ),
                successCount = 3,
            ),
        )
        assertTrue(file.exists())
        assertEquals(1, cache1.size())

        // 模拟进程冷启动：重新从 JSON 文件加载
        val cache2 = SemanticRouteCache(file)
        assertEquals(1, cache2.size())

        val exact = cache2.lookup(appId, pageSig, "打开淘宝购物车并点击去结算按钮")
        assertNotNull(exact)
        assertEquals(3, exact?.successCount)

        // 语序/助词微调的高相似度中文意图应命中语义缓存（余弦 >= 0.90）
        val semanticHit = cache2.lookupSemanticExperience(
            appId = appId,
            pageSignature = pageSig,
            intent = "打开淘宝购物车点击去结算按钮",
        )
        assertNotNull(semanticHit)
        assertTrue("Expected similarity >= 0.90, got ${semanticHit?.similarity}", (semanticHit?.similarity ?: 0.0) >= 0.90)
        assertEquals("打开淘宝购物车并点击去结算按钮", semanticHit?.entry?.intentKeyword)
    }

    @Test
    fun routeGraphSemanticHitAndColdStartRecovery() = runTest {
        val file = File(tempFolder.root, "route_graph_cache.json")
        val nodeHome = RouteGraphNode(nodeId = "n_home", pageSignature = "sig_home")
        val nodeSearch = RouteGraphNode(nodeId = "n_search", pageSignature = "sig_search_page", isTerminal = true)
        val edge = RouteGraphEdge(
            edgeId = "e_search",
            fromNodeId = "n_home",
            toNodeId = "n_search",
            action = MacroStep("phone_tap", buildJsonObject { put("ref", "@search_box") }),
        )
        val graph = RouteGraph(
            graphId = "g_taobao_search_apple",
            intentKeyword = "打开淘宝搜索红富士苹果并按销量排序",
            initialNodeId = "n_home",
            nodes = mapOf("n_home" to nodeHome, "n_search" to nodeSearch),
            edges = listOf(edge),
            successCount = 4,
        )

        val cache1 = SemanticRouteCache(file)
        cache1.putRouteGraph(
            graph = graph,
            appId = "com.taobao.taobao",
            entryNodeId = "n_home",
            intentAliases = listOf("open taobao search red fuji apple sort by sales"),
        )

        // 冷启动恢复
        val cache2 = SemanticRouteCache(file)
        assertEquals(1, cache2.routeGraphCount())

        // 中文近义意图零 Token 命中（余弦 >= 0.90）
        val zhHit = cache2.lookupRouteGraph(
            intent = "打开淘宝搜索红富士苹果按销量排序",
            appId = "com.taobao.taobao",
            pageSignature = "sig_search_page",
        )
        assertNotNull(zhHit)
        assertTrue((zhHit?.similarity ?: 0.0) >= 0.90)
        assertEquals("g_taobao_search_apple", zhHit?.graph?.graphId)
        assertEquals("n_search", zhHit?.targetNode?.nodeId)

        // 英文多语意图同样支持字符 N-gram + TF-IDF 余弦 >= 0.90 命中
        val enHit = cache2.lookupRouteGraph(
            intent = "open taobao search red fuji apples sort by sales",
            appId = "com.taobao.taobao",
        )
        assertNotNull(enHit)
        assertTrue((enHit?.similarity ?: 0.0) >= 0.90)
        assertEquals("n_home", enHit?.targetNode?.nodeId)
    }

    @Test
    fun rejectsLowSimilarityAndNegationMismatch() = runTest {
        val file = File(tempFolder.root, "guard_cache.json")
        val cache = SemanticRouteCache(file)

        val payGraph = RouteGraph(
            graphId = "g_pay",
            intentKeyword = "确认支付宝订单并立即付款",
            initialNodeId = "n0",
            nodes = mapOf("n0" to RouteGraphNode("n0", "sig_pay")),
            edges = emptyList(),
        )
        val appleGraph = RouteGraph(
            graphId = "g_apple",
            intentKeyword = "打开淘宝搜索红富士苹果",
            initialNodeId = "n0",
            nodes = mapOf("n0" to RouteGraphNode("n0", "sig_tb")),
            edges = emptyList(),
        )

        cache.putRouteGraph(payGraph, appId = "com.eg.android.AlipayGphone")
        cache.putRouteGraph(appleGraph, appId = "com.taobao.taobao")

        // 1. 关键槽位不同（苹果 vs 香蕉）不能误命中 >= 0.90
        val wrongItem = cache.lookupRouteGraph("打开淘宝搜索海南香蕉", appId = "com.taobao.taobao")
        assertNull(wrongItem)

        // 2. 否定词双向语义保护：绝不能把“不要付款/取消付款”误匹配成“确认付款”
        val negated = cache.lookupRouteGraph("不要确认支付宝订单并立即付款", appId = "com.eg.android.AlipayGphone")
        assertNull(negated)
    }

    @Test
    fun toleratesCorruptedJsonAndSupportsInvalidate() = runTest {
        val file = File(tempFolder.root, "corrupt_cache.json")
        file.writeText("{corrupted-json-content")

        // 损坏文件不应导致冷启动崩溃
        val cache = SemanticRouteCache(file)
        assertEquals(0, cache.size())
        assertEquals(0, cache.routeGraphCount())

        val graph = RouteGraph(
            graphId = "g_meituan",
            intentKeyword = "打开美团外卖查看订单",
            initialNodeId = "n_order",
            nodes = mapOf("n_order" to RouteGraphNode("n_order", "sig_meituan_order")),
            edges = emptyList(),
        )
        cache.putRouteGraph(graph, appId = "com.sankuai.meituan")
        assertEquals(1, cache.routeGraphCount())

        cache.invalidate("com.sankuai.meituan", "sig_meituan_order")
        assertEquals(0, cache.routeGraphCount())

        val reloaded = SemanticRouteCache(file)
        assertEquals(0, reloaded.routeGraphCount())
    }
}

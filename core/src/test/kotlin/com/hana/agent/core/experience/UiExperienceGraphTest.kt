package com.hana.agent.core.experience

import com.hana.agent.core.action.ChainPredicateKind
import com.hana.agent.core.action.MacroStep
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiExperienceGraphTest {

    @Test
    fun testSerialization() {
        val entry = UiExperienceEntry(
            appId = "com.eg.android.AlipayGphone",
            pageSignature = "sig_alipay_transfer_home",
            intentKeyword = "转账给张三",
            macroActions = listOf(
                MacroStep("phone_tap", buildJsonObject { put("ref", "@e_transfer") }, ChainPredicateKind.NONE),
                MacroStep("phone_type_text", buildJsonObject { put("text", "张三") }, ChainPredicateKind.FOCUSED)
            ),
            successCount = 5,
            lastVerifiedTimestamp = 1731046400000L
        )

        val json = Json.encodeToString(entry)
        val decoded = Json.decodeFromString<UiExperienceEntry>(json)

        assertEquals("com.eg.android.AlipayGphone", decoded.appId)
        assertEquals("sig_alipay_transfer_home", decoded.pageSignature)
        assertEquals("转账给张三", decoded.intentKeyword)
        assertEquals(2, decoded.macroActions.size)
        assertEquals(5, decoded.successCount)
        assertEquals(1731046400000L, decoded.lastVerifiedTimestamp)
    }

    @Test
    fun testRecordSuccessAndLookup() = runTest {
        val graph = InMemoryUiExperienceGraph()
        val appId = "com.jingdong.app.mall"
        val pageSig = "sig_jd_cart"

        val entry1 = UiExperienceEntry(
            appId = appId,
            pageSignature = pageSig,
            intentKeyword = "结算",
            macroActions = listOf(
                MacroStep("phone_tap", buildJsonObject { put("ref", "@checkout") })
            ),
            successCount = 1
        )

        // 记录首次成功
        graph.recordSuccess(entry1)
        assertEquals(1, graph.size())

        // 再次记录成功，successCount 应累加
        graph.recordSuccess(entry1)
        assertEquals(1, graph.size())

        val found = graph.lookup(appId, pageSig, "结算")
        assertNotNull(found)
        assertEquals(2, found?.successCount)
        assertEquals(1, found?.macroActions?.size)

        // 意图模糊匹配
        val fuzzyFound = graph.lookup(appId, pageSig, "去结算")
        assertNotNull(fuzzyFound)
        assertEquals("结算", fuzzyFound?.intentKeyword)
    }

    @Test
    fun testInvalidate() = runTest {
        val graph = InMemoryUiExperienceGraph()
        val appId = "com.tencent.mm"
        val pageSig = "sig_chat_window"

        val entry = UiExperienceEntry(
            appId = appId,
            pageSignature = pageSig,
            intentKeyword = "发送消息",
            macroActions = listOf(
                MacroStep("phone_tap", buildJsonObject { put("ref", "@send_btn") })
            )
        )

        graph.recordSuccess(entry)
        assertNotNull(graph.lookup(appId, pageSig, "发送消息"))

        // 作废失效该页面签名的经验
        graph.invalidate(appId, pageSig)
        assertNull(graph.lookup(appId, pageSig, "发送消息"))
        assertEquals(0, graph.size())
    }

    @Test
    fun testLookupUnrecordedReturnsNull() = runTest {
        val graph = InMemoryUiExperienceGraph()
        assertNull(graph.lookup("com.unknown.app", "sig_unknown", "搜索"))
    }

    @Test
    fun testBlankIntentReturnsNull() = runTest {
        val graph = InMemoryUiExperienceGraph()
        graph.recordSuccess(
            UiExperienceEntry("com.test.app", "sig_1", "支付", emptyList(), successCount = 10)
        )
        assertNull(graph.lookup("com.test.app", "sig_1", ""))
        assertNull(graph.lookup("com.test.app", "sig_1", "   "))
    }

    @Test
    fun testNegationIntentMismatchProtection() = runTest {
        val graph = InMemoryUiExperienceGraph()
        val appId = "com.alipay.android"
        val pageSig = "sig_pay"

        // 库中记录高频肯定条目 "支付"
        graph.recordSuccess(
            UiExperienceEntry(appId, pageSig, "支付", emptyList(), successCount = 100)
        )
        graph.recordSuccess(
            UiExperienceEntry(appId, pageSig, "取消支付", emptyList(), successCount = 2)
        )

        // 用户输入否定意图 "放弃支付"
        val match = graph.lookup(appId, pageSig, "放弃支付")
        // 必须不能匹配高频肯定意图 "支付"
        assertTrue(match == null || match.intentKeyword != "支付")
    }
}

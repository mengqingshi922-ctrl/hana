package com.hana.agent.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动能力规则分流、预算控制与摘要存储核心单元测试。
 */
class ProactiveTriageTest {

    @Test
    fun testProactiveBudgetLimitsAndReset() {
        val budget = ProactiveBudget(maxDailyDecisions = 3, maxDailyTokens = 100)
        val now = 1700000000000L // 固定基准时间

        assertTrue(budget.canDecide(now))
        assertEquals(3, budget.remainingDecisions(now))
        assertEquals(100, budget.remainingTokens(now))

        // 记录 1 次判定，消耗 40 tokens
        assertTrue(budget.recordDecision(tokens = 40, nowMs = now))
        assertEquals(2, budget.remainingDecisions(now))
        assertEquals(60, budget.remainingTokens(now))

        // 记录第 2 次
        assertTrue(budget.recordDecision(tokens = 50, nowMs = now))
        assertEquals(1, budget.remainingDecisions(now))
        assertEquals(10, budget.remainingTokens(now))

        // 记录第 3 次（达到 decision 上限）
        assertTrue(budget.recordDecision(tokens = 5, nowMs = now))
        assertEquals(0, budget.remainingDecisions(now))
        assertFalse(budget.canDecide(now))

        // 超额尝试再记录应返回 false
        assertFalse(budget.recordDecision(tokens = 5, nowMs = now))

        // 跨越到次日（基准时间 + 25 小时）
        val nextDay = now + 25 * 3600 * 1000L
        assertTrue(budget.canDecide(nextDay))
        assertEquals(3, budget.remainingDecisions(nextDay))
        assertEquals(100, budget.remainingTokens(nextDay))
    }

    @Test
    fun testNoticeDeduplicator() {
        val deduplicator = NoticeDeduplicator(maxCacheSize = 10)
        val notice = IncomingNotice(
            id = "n_1",
            packageName = "com.tencent.mm",
            appLabel = "微信",
            title = "工作通知",
            text = "项目明天上线",
            postTimeMs = 1000000L
        )

        // 首次判定非重复
        assertFalse(deduplicator.isDuplicate(notice, windowMs = 300_000L, nowMs = 1000000L))

        // 1 分钟后收到一模一样的通知，应判定为重复
        assertTrue(deduplicator.isDuplicate(notice, windowMs = 300_000L, nowMs = 1060000L))

        // 6 分钟后超过 5 分钟窗口，不再判定为重复
        assertFalse(deduplicator.isDuplicate(notice, windowMs = 300_000L, nowMs = 1400000L))

        // 内容发生变化的通知非重复
        val modifiedNotice = notice.copy(text = "项目改期到下周")
        assertFalse(deduplicator.isDuplicate(modifiedNotice, windowMs = 300_000L, nowMs = 1400100L))
    }

    @Test
    fun testNoticeTriagerRules() {
        val triager = NoticeTriager()

        // 1. 验证码短信 -> IGNORE
        val verificationNotice = IncomingNotice(
            id = "1",
            packageName = "com.android.mms",
            appLabel = "短信",
            title = "95588",
            text = "【工商银行】您正在登录网银，动态验证码为 783921，5分钟内有效，切勿告知他人。"
        )
        assertEquals(TriageResult.IGNORE, triager.triage(verificationNotice))

        // 2. 营销打折广告 -> IGNORE
        val promoNotice = IncomingNotice(
            id = "2",
            packageName = "com.jingdong.app.mall",
            appLabel = "京东",
            title = "好物大促",
            text = "双11狂欢节震撼开启，满300减50，爆款低至5折，退订回TD"
        )
        assertEquals(TriageResult.IGNORE, triager.triage(promoNotice))

        // 3. 系统噪音通知 -> IGNORE
        val systemNoiseNotice = IncomingNotice(
            id = "3",
            packageName = "com.android.systemui",
            appLabel = "系统界面",
            title = "USB 调试已连接",
            text = "点击以关闭调试模式"
        )
        assertEquals(TriageResult.IGNORE, triager.triage(systemNoiseNotice))

        // 4. 快递取件码 -> CAPSULE_BADGE_HINT
        val expressNotice = IncomingNotice(
            id = "4",
            packageName = "com.cainiao.wireless",
            appLabel = "菜鸟",
            title = "取件提醒",
            text = "您的顺丰速运包裹已送达丰巢快递柜，凭取件码 582-192 取件"
        )
        assertEquals(TriageResult.CAPSULE_BADGE_HINT, triager.triage(expressNotice))

        // 5. 会议与日程 -> CAPSULE_BADGE_HINT
        val meetingNotice = IncomingNotice(
            id = "5",
            packageName = "com.tencent.wemeet.app",
            appLabel = "腾讯会议",
            title = "日程提醒",
            text = "您的会议「Hana 架构周会」即将开始，请准时参加"
        )
        assertEquals(TriageResult.CAPSULE_BADGE_HINT, triager.triage(meetingNotice))

        // 6. 社交通讯中的艾特呼叫 -> CAPSULE_BADGE_HINT
        val chatUrgentNotice = IncomingNotice(
            id = "6",
            packageName = "com.tencent.mm",
            appLabel = "微信",
            title = "技术交流群",
            text = "架构师 @了你：请查收紧急部署日志"
        )
        assertEquals(TriageResult.CAPSULE_BADGE_HINT, triager.triage(chatUrgentNotice))

        // 7. 普通应用消息 -> SILENT_DIGEST
        val generalNotice = IncomingNotice(
            id = "7",
            packageName = "com.coolapk.market",
            appLabel = "酷安",
            title = "动态更新",
            text = "您关注的数码博主发布了新图文"
        )
        assertEquals(TriageResult.SILENT_DIGEST, triager.triage(generalNotice))
    }

    @Test
    fun testProactiveDigestStoreSerialization() {
        val store = ProactiveDigestStore()
        val date = "2026-11-14"
        val item = DigestItem(
            id = "item_1",
            timestampMs = 1700000000000L,
            category = "EXPRESS",
            title = "快递到达",
            summary = "丰巢快递柜待取件",
            sourcePackage = "com.cainiao.wireless"
        )

        store.addItem(date, item)
        val rollup = store.getRollup(date)
        assertNotNull(rollup)
        assertEquals(1, rollup!!.items.size)
        assertEquals("快递到达", rollup.items[0].title)

        // 测试 JSON 导出与恢复
        val jsonText = store.exportToJson()
        assertTrue(jsonText.contains("EXPRESS"))
        assertTrue(jsonText.contains("丰巢快递柜待取件"))

        val newStore = ProactiveDigestStore()
        newStore.loadFromJson(jsonText)
        val restoredRollup = newStore.getRollup(date)
        assertNotNull(restoredRollup)
        assertEquals(1, restoredRollup!!.items.size)
        assertEquals("item_1", restoredRollup.items[0].id)
    }
}

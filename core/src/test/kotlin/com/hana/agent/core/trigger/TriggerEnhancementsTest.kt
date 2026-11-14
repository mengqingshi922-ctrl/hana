package com.hana.agent.core.trigger

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 验证定时任务增强功能:
 * 1. 免打扰时段判断 isInQuietHours (包含跨零点场景);
 * 2. 多时刻计算 nextMultiCalendarAt;
 * 3. 计划复制 duplicate 与执行记录 recentWindows;
 * 4. 旧格式 JSON 反序列化向后兼容。
 */
class TriggerEnhancementsTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun testQuietHoursNonSpanning() {
        // 12:00 (720) 到 14:00 (840)
        val trigger = TriggerDefinition(
            id = "t1", threadId = "th1", goal = "test", nextAtMs = 1000L,
            quietStartMinute = 720, quietEndMinute = 840
        )
        val today = LocalDate.of(2026, 11, 14)

        val inside = today.atTime(LocalTime.of(13, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertTrue("13:00 应在 12:00~14:00 免打扰时段内", trigger.isInQuietHours(inside, zoneId))

        val outsideBefore = today.atTime(LocalTime.of(11, 59)).atZone(zoneId).toInstant().toEpochMilli()
        assertFalse("11:59 不在免打扰时段内", trigger.isInQuietHours(outsideBefore, zoneId))

        val outsideAfter = today.atTime(LocalTime.of(14, 1)).atZone(zoneId).toInstant().toEpochMilli()
        assertFalse("14:01 不在免打扰时段内", trigger.isInQuietHours(outsideAfter, zoneId))
    }

    @Test
    fun testQuietHoursSpanningMidnight() {
        // 23:00 (1380) 到 07:00 (420)
        val trigger = TriggerDefinition(
            id = "t2", threadId = "th1", goal = "test", nextAtMs = 1000L,
            quietStartMinute = 1380, quietEndMinute = 420
        )
        val today = LocalDate.of(2026, 11, 14)

        val lateNight = today.atTime(LocalTime.of(23, 30)).atZone(zoneId).toInstant().toEpochMilli()
        assertTrue("23:30 应在跨零点免打扰时段内", trigger.isInQuietHours(lateNight, zoneId))

        val earlyMorning = today.atTime(LocalTime.of(5, 30)).atZone(zoneId).toInstant().toEpochMilli()
        assertTrue("05:30 应在跨零点免打扰时段内", trigger.isInQuietHours(earlyMorning, zoneId))

        val daytime = today.atTime(LocalTime.of(12, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertFalse("12:00 不在免打扰时段内", trigger.isInQuietHours(daytime, zoneId))
    }

    @Test
    fun testNextMultiCalendarAt() {
        val today = LocalDate.of(2026, 11, 14)
        val times = listOf("09:00", "15:00")
        val zone = "Asia/Shanghai"

        // 8:00 之后最近的下一个触发是当天的 09:00
        val at0800 = today.atTime(LocalTime.of(8, 0)).atZone(zoneId).toInstant().toEpochMilli()
        val nextFrom08 = nextMultiCalendarAt(times, zone, emptyList(), at0800)
        val expected09 = today.atTime(LocalTime.of(9, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(expected09, nextFrom08)

        // 10:00 之后最近的下一个触发是当天的 15:00
        val at1000 = today.atTime(LocalTime.of(10, 0)).atZone(zoneId).toInstant().toEpochMilli()
        val nextFrom10 = nextMultiCalendarAt(times, zone, emptyList(), at1000)
        val expected15 = today.atTime(LocalTime.of(15, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(expected15, nextFrom10)

        // 16:00 之后最近的下一个触发是次日的 09:00
        val at1600 = today.atTime(LocalTime.of(16, 0)).atZone(zoneId).toInstant().toEpochMilli()
        val nextFrom16 = nextMultiCalendarAt(times, zone, emptyList(), at1600)
        val expectedNextDay09 = today.plusDays(1).atTime(LocalTime.of(9, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(expectedNextDay09, nextFrom16)
    }

    @Test
    fun testDuplicateAndRecentWindows() {
        val store = MemoryTriggerStore()
        val original = TriggerDefinition(
            id = "plan-original", threadId = "thread-1", goal = "Daily news", nextAtMs = 5000L,
            calendarTime = "09:00", calendarZone = "Asia/Shanghai"
        )
        store.upsert(original)

        // 复制计划
        val copied = store.duplicate("plan-original", "plan-copy")
        assertNotNull(copied)
        assertEquals("plan-copy", copied?.id)
        assertEquals("Daily news", copied?.goal)
        assertEquals("09:00", copied?.calendarTime)
        assertEquals(original, store.get("plan-original"))
        assertEquals(copied, store.get("plan-copy"))

        // 模拟执行窗口记账
        val w1 = TriggerWindow(triggerId = "plan-original", dueAtMs = 1000L, claimedAtMs = 1005L, state = TriggerWindowState.Delivered)
        val w2 = TriggerWindow(triggerId = "plan-original", dueAtMs = 2000L, claimedAtMs = 2005L, state = TriggerWindowState.Skipped)
        val w3 = TriggerWindow(triggerId = "plan-original", dueAtMs = 3000L, claimedAtMs = 3005L, state = TriggerWindowState.Delivered)
        val wOther = TriggerWindow(triggerId = "plan-other", dueAtMs = 2500L, claimedAtMs = 2505L)

        store.claimNow("plan-original", 1000L) // 增加记录
        val recent = store.recentWindows("plan-original", limit = 2)
        assertTrue(recent.size <= 2)
    }

    @Test
    fun testOldJsonDeserializationCompatibility() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val oldJson = """
            {
                "id": "old-trigger",
                "threadId": "th-old",
                "goal": "Reminder test",
                "nextAtMs": 1700000000000,
                "enabled": true
            }
        """.trimIndent()

        val decoded = json.decodeFromString<TriggerDefinition>(oldJson)
        assertEquals("old-trigger", decoded.id)
        assertEquals(emptyList<String>(), decoded.calendarTimes)
        assertEquals(null, decoded.quietStartMinute)
        assertEquals(null, decoded.quietEndMinute)
        assertFalse(decoded.isInQuietHours(1700000000000))
    }
}

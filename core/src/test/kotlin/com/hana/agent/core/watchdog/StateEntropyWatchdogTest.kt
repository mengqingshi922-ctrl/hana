package com.hana.agent.core.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateEntropyWatchdogTest {

    @Test
    fun testDiffCalculation() {
        val watchdog = StateEntropyWatchdog()

        // 相同指纹差异为 0
        assertEquals(0.0, watchdog.diff("page_home_v1", "page_home_v1"), 1e-6)

        // 微小微调 (例如仅时间戳变动一个数字)
        val diffSmall = watchdog.diff("page_home_time_10:00", "page_home_time_10:01")
        assertTrue("diff $diffSmall should be <= 0.1", diffSmall <= 0.1)

        // 完全不同的页面
        val diffLarge = watchdog.diff("page_home_feed", "settings_system_security")
        assertTrue("diff $diffLarge should be > 0.5", diffLarge > 0.5)
    }

    @Test
    fun testStagnationDetection() {
        val watchdog = StateEntropyWatchdog(stagnationWindow = 4)

        // 连续 4 步几乎静止不变 (原地踏步)
        watchdog.recordFrame("static_screen_hash_100")
        assertEquals(WatchdogVerdict.HEALTHY, watchdog.evaluate())

        watchdog.recordFrame("static_screen_hash_100")
        assertEquals(WatchdogVerdict.HEALTHY, watchdog.evaluate())

        watchdog.recordFrame("static_screen_hash_100")
        assertEquals(WatchdogVerdict.HEALTHY, watchdog.evaluate())

        watchdog.recordFrame("static_screen_hash_100")
        // 达到 4 步且差值和 <= 0.05
        assertEquals(WatchdogVerdict.STAGNATION, watchdog.evaluate())
    }

    @Test
    fun testOscillationDetection() {
        val watchdog = StateEntropyWatchdog(oscillationWindow = 8)

        // 模拟 A -> B -> A -> B 震荡循环
        watchdog.recordFrame("screen_page_alpha_details")
        watchdog.recordFrame("screen_page_beta_dialog")
        watchdog.recordFrame("screen_page_alpha_details")
        watchdog.recordFrame("screen_page_beta_dialog")

        assertEquals(WatchdogVerdict.OSCILLATION, watchdog.evaluate())
    }

    @Test
    fun testNormalProgressionIsHealthy() {
        val watchdog = StateEntropyWatchdog()

        // 正常长程探索页面流转
        watchdog.recordFrame("home_feed_list")
        watchdog.recordFrame("search_box_input_view")
        watchdog.recordFrame("search_results_page")
        watchdog.recordFrame("item_detail_overview")
        watchdog.recordFrame("cart_checkout_screen")

        assertEquals(WatchdogVerdict.HEALTHY, watchdog.evaluate())
    }

    @Test
    fun testSelfHealingEscalationTiers() {
        val watchdog = StateEntropyWatchdog()

        // 第 1 级自愈：端侧弹窗与广告消除
        assertEquals(SelfHealingTier.TIER_1_DISMISS_OBSTACLE, watchdog.recommendHealing(0))
        assertEquals(SelfHealingTier.TIER_1_DISMISS_OBSTACLE, watchdog.recommendHealing(1))

        // 第 2 级自愈：系统级 KEYCODE_BACK 回退安全页
        assertEquals(SelfHealingTier.TIER_2_SAFETY_BACK, watchdog.recommendHealing(2))

        // 第 3 级自愈：强杀应用冷启动对齐
        assertEquals(SelfHealingTier.TIER_3_RESTART_APP, watchdog.recommendHealing(3))
        assertEquals(SelfHealingTier.TIER_3_RESTART_APP, watchdog.recommendHealing(5))
    }

    @Test
    fun testResetClearsHistory() {
        val watchdog = StateEntropyWatchdog(stagnationWindow = 4)
        repeat(4) { watchdog.recordFrame("static_hash") }
        assertEquals(WatchdogVerdict.STAGNATION, watchdog.evaluate())

        watchdog.reset()
        assertEquals(WatchdogVerdict.HEALTHY, watchdog.evaluate())
    }

    @Test
    fun testOscillationDetectionPeriod3And4() {
        val watchdog = StateEntropyWatchdog()

        // 周期 3：A-B-C-A-B-C
        watchdog.reset()
        listOf("page_A", "page_B", "page_C", "page_A", "page_B", "page_C").forEach {
            watchdog.recordFrame(it)
        }
        assertEquals(WatchdogVerdict.OSCILLATION, watchdog.evaluate())

        // 周期 4：A-B-C-D-A-B-C-D
        watchdog.reset()
        listOf("p1", "p2", "p3", "p4", "p1", "p2", "p3", "p4").forEach {
            watchdog.recordFrame(it)
        }
        assertEquals(WatchdogVerdict.OSCILLATION, watchdog.evaluate())
    }

    @Test
    fun testShortFingerprintCountdownStagnationDetection() {
        val watchdog = StateEntropyWatchdog(stagnationWindow = 4)
        // 22 字符短指纹，末尾仅有倒计时变动 1 字符
        watchdog.recordFrame("screen_timer_val_00:09")
        watchdog.recordFrame("screen_timer_val_00:08")
        watchdog.recordFrame("screen_timer_val_00:07")
        watchdog.recordFrame("screen_timer_val_00:06")

        assertEquals(WatchdogVerdict.STAGNATION, watchdog.evaluate())
    }
}

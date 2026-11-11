package com.hana.agent.core.watchdog

import com.hana.agent.core.subgoal.SubgoalCheckpoint
import com.hana.agent.core.subgoal.SubgoalCheckpointManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateEntropyWatchdogIntegrationTest {

    @Test
    fun testMainLoopRecordFrameEachStep() {
        val watchdog = StateEntropyWatchdog(stagnationWindow = 4, oscillationWindow = 8)

        // 模拟前台正常流转的 5 个步骤
        val steps = listOf("home_activity_v1", "search_panel_v1", "result_list_v1", "detail_page_v1", "order_page_v1")
        for (step in steps) {
            watchdog.recordFrame(step)
            val verdict = watchdog.evaluate()
            assertEquals(WatchdogVerdict.HEALTHY, verdict)
        }
    }

    @Test
    fun testStagnationTriggersTier1ObstacleDismiss() {
        val watchdog = StateEntropyWatchdog(stagnationWindow = 4)

        // 连续 4 步记录完全相同的静止界面指纹
        repeat(4) {
            watchdog.recordFrame("static_screen_popup_ad_hash")
        }

        val verdict = watchdog.evaluate()
        assertEquals(WatchdogVerdict.STAGNATION, verdict)

        // 首次与第二次触发自愈，应当推荐 Tier 1（端侧消解弹窗/广告）
        assertEquals(SelfHealingTier.TIER_1_DISMISS_OBSTACLE, watchdog.recommendHealing(0))
        assertEquals(SelfHealingTier.TIER_1_DISMISS_OBSTACLE, watchdog.recommendHealing(1))
    }

    @Test
    fun testStagnationEscalatesToTier2SafetyBack() {
        val watchdog = StateEntropyWatchdog(stagnationWindow = 4)
        repeat(4) {
            watchdog.recordFrame("static_blocked_dialog_hash")
        }

        assertEquals(WatchdogVerdict.STAGNATION, watchdog.evaluate())

        // 连续自愈失败第 2 次，应当递增推荐 Tier 2（物理安全 Back 键回退）
        val recommendation = watchdog.recommendHealing(2)
        assertEquals(SelfHealingTier.TIER_2_SAFETY_BACK, recommendation)
    }

    @Test
    fun testDeepLoopTriggersTier3RestartAppWithCheckpoint() {
        val checkpointManager = SubgoalCheckpointManager()
        val cp = SubgoalCheckpoint(
            checkpointId = "cp_steady_state",
            subgoalName = "已到达结算页",
            packageName = "com.shop.app",
            uiSnapshotHash = 10086L,
            registers = mapOf("order_id" to "ORDER_998")
        )
        checkpointManager.saveCheckpoint(cp)

        val watchdog = StateEntropyWatchdog(stagnationWindow = 4)
        repeat(4) {
            watchdog.recordFrame("frozen_app_deadlock_hash")
        }

        // 连续自愈失败 >= 3 次，升级为 Tier 3（冷重启应用并基于检查点自愈）
        val tier = watchdog.recommendHealing(3)
        assertEquals(SelfHealingTier.TIER_3_RESTART_APP, tier)

        val latestCheckpoint = checkpointManager.getLatestCheckpoint()
        assertNotNull(latestCheckpoint)
        assertEquals("com.shop.app", latestCheckpoint?.packageName)
        assertEquals("ORDER_998", latestCheckpoint?.registers?.get("order_id"))

        // 测试 >= 5 次仍保持 Tier 3
        assertEquals(SelfHealingTier.TIER_3_RESTART_APP, watchdog.recommendHealing(5))
    }

    @Test
    fun testOscillationTriggersHealing() {
        val watchdog = StateEntropyWatchdog(oscillationWindow = 8)

        // 模拟 A -> B -> A -> B 交互震荡
        listOf("page_feed", "dialog_coupon", "page_feed", "dialog_coupon").forEach {
            watchdog.recordFrame(it)
        }

        assertEquals(WatchdogVerdict.OSCILLATION, watchdog.evaluate())
        // 首次震荡推荐消解弹窗
        assertEquals(SelfHealingTier.TIER_1_DISMISS_OBSTACLE, watchdog.recommendHealing(0))
    }
}

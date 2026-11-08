package com.hana.agent.capsule

import com.hana.agent.task.TaskService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证三态灵动胶囊状态枚举、吉祥物表情映射与 TaskService 暂停控制
 */
class CapsuleStateTest {

    @Test
    fun verifiesCapsuleStateEnumEntries() {
        val entries = CapsuleState.entries
        assertEquals(3, entries.size)
        assertTrue(entries.contains(CapsuleState.MINI))
        assertTrue(entries.contains(CapsuleState.PILL))
        assertTrue(entries.contains(CapsuleState.EXPANDED))
    }

    @Test
    fun mapsMascotEmotionAccuratelyForCapsuleKinds() {
        // 运行中 -> 思考脉冲
        assertEquals("30", mascotEmotionIdForKind(CapsuleKind.Running))

        // 需要用户处理 -> 惊讶提醒
        assertEquals("13", mascotEmotionIdForKind(CapsuleKind.NeedTakeover))
        assertEquals("13", mascotEmotionIdForKind(CapsuleKind.NeedConfirm))
        assertEquals("13", mascotEmotionIdForKind(CapsuleKind.NeedAnswer))
        assertEquals("13", mascotEmotionIdForKind(CapsuleKind.NeedStep))

        // 成功完成 -> 撒花
        assertEquals("33", mascotEmotionIdForKind(CapsuleKind.Done))

        // 失败或取消 -> 警惕
        assertEquals("34", mascotEmotionIdForKind(CapsuleKind.Failed))
        assertEquals("34", mascotEmotionIdForKind(CapsuleKind.Cancelled))

        // 挂起中 -> 好奇
        assertEquals("03", mascotEmotionIdForKind(CapsuleKind.Held))

        // 待机/其他 -> 待机放空
        assertEquals("02", mascotEmotionIdForKind(CapsuleKind.Idle))
        assertEquals("02", mascotEmotionIdForKind(CapsuleKind.KeptPage))
    }

    @Test
    fun taskServiceExposesPauseControlsAndStateFlow() {
        assertNotNull(TaskService.ACTION_PAUSE)
        assertEquals("com.hana.agent.task.PAUSE", TaskService.ACTION_PAUSE)

        val flow = TaskService.isPaused
        assertNotNull(flow)
        // 默认情况下处于非暂停状态
        assertFalse(flow.value)
    }
}

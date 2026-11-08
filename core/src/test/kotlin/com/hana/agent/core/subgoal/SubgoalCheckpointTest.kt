package com.hana.agent.core.subgoal

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubgoalCheckpointTest {

    @Test
    fun testSerialization() {
        val checkpoint = SubgoalCheckpoint(
            checkpointId = "cp_001",
            subgoalName = "完成购物车加购",
            packageName = "com.taobao.taobao",
            activityName = "com.taobao.tao.detail.activity.DetailActivity",
            uiSnapshotHash = 9876543210L,
            registers = mapOf("\$item_id" to "10086", "\$price" to "299.00"),
            timestamp = 1731046400000L
        )

        val json = Json.encodeToString(checkpoint)
        val decoded = Json.decodeFromString<SubgoalCheckpoint>(json)

        assertEquals("cp_001", decoded.checkpointId)
        assertEquals("完成购物车加购", decoded.subgoalName)
        assertEquals("com.taobao.taobao", decoded.packageName)
        assertEquals("com.taobao.tao.detail.activity.DetailActivity", decoded.activityName)
        assertEquals(9876543210L, decoded.uiSnapshotHash)
        assertEquals("10086", decoded.registers["\$item_id"])
        assertEquals("299.00", decoded.registers["\$price"])
        assertEquals(1731046400000L, decoded.timestamp)
    }

    @Test
    fun testCheckpointManagerLifecycleAndRollback() {
        val manager = SubgoalCheckpointManager()
        assertNull(manager.getLatestCheckpoint())

        val cp1 = SubgoalCheckpoint("cp_1", "打开应用", "com.example.app", uiSnapshotHash = 101L)
        val cp2 = SubgoalCheckpoint("cp_2", "进入搜索页", "com.example.app", uiSnapshotHash = 102L)
        val cp3 = SubgoalCheckpoint("cp_3", "选择规格", "com.example.app", uiSnapshotHash = 103L)
        val cp4 = SubgoalCheckpoint("cp_4", "提交订单", "com.example.app", uiSnapshotHash = 104L)

        manager.saveCheckpoint(cp1)
        manager.saveCheckpoint(cp2)
        manager.saveCheckpoint(cp3)
        manager.saveCheckpoint(cp4)

        assertEquals(4, manager.getCheckpoints().size)
        assertEquals("cp_4", manager.getLatestCheckpoint()?.checkpointId)

        // 局部回滚到 cp_2 (模拟跨应用或长程任务在第 3/4 步失败并回退)
        val rolled = manager.rollbackTo("cp_2")
        assertNotNull(rolled)
        assertEquals("cp_2", rolled?.checkpointId)
        assertEquals("进入搜索页", rolled?.subgoalName)

        // 验证 cp_3 和 cp_4 已被丢弃，当前栈顶为 cp_2
        assertEquals(2, manager.getCheckpoints().size)
        assertEquals("cp_2", manager.getLatestCheckpoint()?.checkpointId)

        // 回滚到不存在的 checkpointId
        val notFound = manager.rollbackTo("cp_999")
        assertNull(notFound)
        assertEquals(2, manager.getCheckpoints().size)

        // 清空
        manager.clear()
        assertNull(manager.getLatestCheckpoint())
        assertEquals(0, manager.getCheckpoints().size)
    }

    @Test
    fun testRegistersDefensiveCopyProtectsSnapshotImmutability() {
        val mutableRegs = mutableMapOf("userId" to "1001", "token" to "secret_abc")
        val cp = SubgoalCheckpoint(
            checkpointId = "cp_immut",
            subgoalName = "登录完成",
            packageName = "com.test.app",
            uiSnapshotHash = 2001L,
            registers = mutableRegs
        )

        // 外部篡改原 MutableMap
        mutableRegs["userId"] = "HACKED_9999"
        mutableRegs["token"] = "CORRUPTED"
        mutableRegs["new_injected"] = "malicious"

        // 验证 Checkpoint 内部寄存器快照保持初始不可变状态
        assertEquals("1001", cp.registers["userId"])
        assertEquals("secret_abc", cp.registers["token"])
        assertNull(cp.registers["new_injected"])
    }
}

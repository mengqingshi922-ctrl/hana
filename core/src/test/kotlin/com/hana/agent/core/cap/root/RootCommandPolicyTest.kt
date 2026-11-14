package com.hana.agent.core.cap.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分级 Root 模式命令安全策略单元测试。
 */
class RootCommandPolicyTest {

    @Test
    fun testSwitchDisabledRejectsAll() {
        val readCmd = RootCommandPolicy.evaluate("ls -la /sdcard", unattended = false, rootCommandEnabled = false)
        assertFalse("开关关闭时拒绝只读命令", readCmd.allowed)
        assertFalse(readCmd.requiresConfirmation)

        val writeCmd = RootCommandPolicy.evaluate("rm -rf /data/local/tmp", unattended = false, rootCommandEnabled = false)
        assertFalse("开关关闭时拒绝高危写命令", writeCmd.allowed)
        assertFalse(writeCmd.requiresConfirmation)
    }

    @Test
    fun testReadOnlyCommandAutoAllowed() {
        val testCommands = listOf(
            "ls /data/local/tmp",
            "cat /proc/version",
            "/system/bin/ps -A",
            "dumpsys battery",
            "getprop ro.build.version.release",
            "id",
            "which su"
        )

        for (cmd in testCommands) {
            val eval = RootCommandPolicy.evaluate(cmd, unattended = false, rootCommandEnabled = true)
            assertEquals("应分类为 READ_ONLY: $cmd", RootCommandRisk.READ_ONLY, eval.risk)
            assertTrue("只读命令应自动放行: $cmd", eval.allowed)
            assertFalse("只读命令不需要二次确认: $cmd", eval.requiresConfirmation)
        }
    }

    @Test
    fun testHighRiskCommandRequiresConfirmationWhenAttended() {
        val highRiskCommands = listOf(
            "rm -rf /data/local/tmp/test",
            "reboot",
            "chmod 777 /data",
            "pm uninstall com.example.app",
            "settings put global airplane_mode_on 1",
            "dd if=/dev/zero of=/dev/block/bootdevice"
        )

        for (cmd in highRiskCommands) {
            val eval = RootCommandPolicy.evaluate(cmd, unattended = false, rootCommandEnabled = true)
            assertEquals("应判定为高危命令: $cmd", RootCommandRisk.HIGH_RISK, eval.risk)
            assertTrue("有人值守时允许通过确认执行: $cmd", eval.allowed)
            assertTrue("有人值守时必须强制确认: $cmd", eval.requiresConfirmation)
        }
    }

    @Test
    fun testUnattendedModeStrictlyRejectsNonReadOnlyCommands() {
        // 1. 无人值守模式下，只读命令依然放行
        val readEval = RootCommandPolicy.evaluate("getprop ro.product.model", unattended = true, rootCommandEnabled = true)
        assertTrue("无人值守模式下只读命令放行", readEval.allowed)
        assertFalse(readEval.requiresConfirmation)

        // 2. 无人值守模式下，写命令和模拟输入直接彻底拒绝
        val tapEval = RootCommandPolicy.evaluate("input tap 500 500", unattended = true, rootCommandEnabled = true)
        assertFalse("无人值守模式下直接拒绝写/输入操作", tapEval.allowed)
        assertFalse("直接拒绝后无需二次弹窗", tapEval.requiresConfirmation)
        assertTrue(tapEval.riskReason?.contains("无人值守") == true)

        // 3. 无人值守模式下，高危命令直接彻底拒绝
        val rmEval = RootCommandPolicy.evaluate("rm -f /data/app.apk", unattended = true, rootCommandEnabled = true)
        assertFalse("无人值守模式下绝对禁止执行高危命令", rmEval.allowed)
        assertFalse(rmEval.requiresConfirmation)
        assertTrue(rmEval.riskReason?.contains("无人值守") == true)
    }

    @Test
    fun testRedirectionClassifiedAsHighRisk() {
        // 包含重定向符的只读程序仍具有写盘破坏性
        val redirectEval = RootCommandPolicy.evaluate("cat foo.txt > /system/bin/bar", unattended = true, rootCommandEnabled = true)
        assertEquals(RootCommandRisk.HIGH_RISK, redirectEval.risk)
        assertFalse("重定向写入在无人值守下必须直接拒绝", redirectEval.allowed)
    }
}

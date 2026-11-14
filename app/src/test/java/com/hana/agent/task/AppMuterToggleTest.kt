package com.hana.agent.task

import com.hana.agent.cap.phone.shell.AppAudioKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [AppMuter] 的声音状态查询与动态切换功能 (toggleMute / isMuted):
 * 1. 初始未静音时，isMuted 返回 false;
 * 2. 调用 toggleMute 成功静音并返回 true，此时 isMuted 为 true;
 * 3. 再次调用 toggleMute 恢复声音并返回 false，此时 isMuted 为 false。
 */
class AppMuterToggleTest {

    private class FakeAudioPort : AudioPort {
        val modes = mutableMapOf<String, String>()
        val actions = mutableListOf<String>()

        override fun mode(packageName: String): String? = modes[packageName] ?: "default"

        override fun mute(packageName: String): Boolean {
            actions += "mute:$packageName"
            modes[packageName] = "ignore"
            return true
        }

        override fun setMode(packageName: String, mode: String): Boolean {
            actions += "restore:$packageName:$mode"
            modes[packageName] = mode
            return true
        }
    }

    private val audio = FakeAudioPort()
    private val store = MemoryMarker()
    private val record = AudioMute(audio, store)
    private val failed = mutableListOf<String>()
    private val muter = AppMuter(
        record,
        enabled = { true },
        mutable = { true },
        onMainScreen = { _, _ -> false },
        failed = { failed += it },
    )

    @Test
    fun testToggleMuteTogglesCorrectly() {
        val pkg = "com.sample.video"
        val user: Int? = null

        // 初始未静音
        assertFalse("初始状态应未静音", muter.isMuted(pkg, user))

        // 第一次切换: 未静音 -> 静音
        val muteResult = muter.toggleMute(pkg, user)
        assertTrue("toggleMute 静音应返回 true", muteResult)
        assertTrue("静音后 isMuted 应为 true", muter.isMuted(pkg, user))
        assertTrue("应记录了 mute 动作", audio.actions.contains("mute:$pkg"))

        // 第二次切换: 静音 -> 恢复出声
        val restoreResult = muter.toggleMute(pkg, user)
        assertFalse("toggleMute 恢复应返回 false", restoreResult)
        assertFalse("恢复后 isMuted 应为 false", muter.isMuted(pkg, user))
    }
}

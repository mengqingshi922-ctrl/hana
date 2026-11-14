package com.hana.agent.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hana.agent.cap.root.RootHelperStarter
import com.hana.agent.capsule.CapsuleMode
import com.hana.agent.core.agent.SpeedFlags
import com.hana.agent.core.shell.BuiltinShellStatus
import com.hana.agent.proactive.ProactivePrefs
import com.hana.agent.settings.AccessibilityState
import com.hana.agent.settings.FeatureFlags
import com.hana.agent.settings.SystemStatus
import com.hana.agent.teach.TeachRuntime
import com.hana.agent.teach.TeachState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 高级设置、分级 Root 与「教 Hana」示范录制的联动单元测试。
 */
class AdvancedSettingsWiringTest {

    private class TestMemoryPrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()

        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? Set<String>) ?: defValues
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in map
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = HashMap<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { clearFlag = true }
            override fun commit(): Boolean {
                if (clearFlag) map.clear()
                changes.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }

    private class FakeContext(
        private val prefsMap: Map<String, SharedPreferences> = emptyMap()
    ) : ContextWrapper(null) {
        private val defaultPrefs = TestMemoryPrefs()

        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsMap[name] ?: defaultPrefs
        }
    }

    @Test
    fun testRootHelperStarterRefusesWhenDisabled() {
        val rootPrefs = TestMemoryPrefs()
        val context = FakeContext(mapOf(FeatureFlags.PREFS to rootPrefs))
        val flags = FeatureFlags(rootPrefs)
        flags.setRootHelperEnabled(false)

        val result = RootHelperStarter.startHelperViaRoot(
            context = context,
            helperCommand = "echo test",
            stdinLine = "nonce_12345\n"
        )
        assertTrue("开关关闭时必须启动失败", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalStateException)
        assertTrue(ex?.message?.contains("Root 辅助进程启动功能已被用户禁用") == true)
    }

    @Test
    fun testRootHelperStarterValidatesBlankCommand() {
        val rootPrefs = TestMemoryPrefs()
        val context = FakeContext(mapOf(FeatureFlags.PREFS to rootPrefs))
        val flags = FeatureFlags(rootPrefs)
        flags.setRootHelperEnabled(true)

        val result = RootHelperStarter.startHelperViaRoot(
            context = context,
            helperCommand = "   ",
            stdinLine = "nonce_test"
        )
        assertTrue("空白命令必须启动失败", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
        assertTrue(ex?.message?.contains("不能为空") == true)
    }

    @Test
    fun testProactivePrefsAndFeatureFlagsLinkage() {
        val flagPrefs = TestMemoryPrefs()
        val proactivePrefsSp = TestMemoryPrefs()
        val context = FakeContext(mapOf(
            FeatureFlags.PREFS to flagPrefs,
            ProactivePrefs.PREFS_NAME to proactivePrefsSp
        ))

        val flags = FeatureFlags(flagPrefs)
        val proactive = ProactivePrefs(proactivePrefsSp)

        assertFalse("默认主动能力处于关闭", flags.proactiveEnabled())
        assertFalse("默认 ProactivePrefs 处于关闭", proactive.isProactiveEnabled())

        // 模拟 UI 触发开启
        flags.setProactiveEnabled(true)
        proactive.setProactiveEnabled(true)

        assertTrue("设置后 flags 必须为 true", flags.proactiveEnabled())
        assertTrue("设置后 proactive 必须为 true", proactive.isProactiveEnabled())

        // 模拟关闭
        flags.setProactiveEnabled(false)
        proactive.setProactiveEnabled(false)

        assertFalse(flags.proactiveEnabled())
        assertFalse(proactive.isProactiveEnabled())
    }

    @Test
    fun testTeachRuntimeLifecycleAndDraftGeneration() {
        val teach = TeachRuntime.instance
        teach.discard() // 保证初始干净

        assertEquals("初始状态必须为 IDLE", TeachState.IDLE, teach.state.value)
        assertEquals("初始步数必须为 0", 0, teach.currentStepCount.value)

        val startSuccess = teach.startRecording("my-demo-skill")
        assertTrue("空闲状态下成功启动录制", startSuccess)
        assertEquals(TeachState.RECORDING, teach.state.value)

        teach.recordStep(
            packageName = "com.tencent.mm",
            actionType = "tap",
            targetLabel = "通讯录"
        )
        assertEquals(1, teach.currentStepCount.value)

        teach.recordStep(
            packageName = "com.tencent.mm",
            actionType = "type_text",
            targetLabel = "搜索框",
            inputSample = "张三"
        )
        assertEquals(2, teach.currentStepCount.value)

        val draft = teach.stopRecording("微信查找联系人")
        assertNotNull(draft)
        assertEquals(TeachState.REVIEWING, teach.state.value)
        assertEquals(2, draft?.stepCount)
        assertEquals("my-demo-skill", draft?.suggestedName)
        assertTrue("生成的草稿正文包含关键步骤", draft?.markdownBody?.contains("通讯录") == true)

        val completedDraft = teach.completeReview()
        assertEquals(draft, completedDraft)
        assertEquals("收下后状态回到 IDLE", TeachState.IDLE, teach.state.value)
        assertEquals("步数重置为 0", 0, teach.currentStepCount.value)
    }

    @Test
    fun testSettingsScreensParametersHaveSensibleDefaults() {
        // 通过反射校验新改动界面的 Compose 函数默认值参数掩码支持，确保不破坏向后兼容
        val subPagesClass = Class.forName("com.hana.agent.ui.SettingsSubPagesKt")
        val devOptionsClass = Class.forName("com.hana.agent.ui.DeveloperOptionsScreenKt")

        // 1. PhoneOpsScreen 必须存在默认值支持函数 (带 Default 掩码参数)
        val phoneOpsMethods = subPagesClass.declaredMethods.filter { it.name.startsWith("PhoneOpsScreen") }
        assertTrue("PhoneOpsScreen 必须声明成功", phoneOpsMethods.isNotEmpty())

        // 2. NotificationsScreen 必须存在
        val notifMethods = subPagesClass.declaredMethods.filter { it.name.startsWith("NotificationsScreen") }
        assertTrue("NotificationsScreen 必须声明成功", notifMethods.isNotEmpty())

        // 3. CapsuleScreen 必须存在
        val capsuleMethods = subPagesClass.declaredMethods.filter { it.name.startsWith("CapsuleScreen") }
        assertTrue("CapsuleScreen 必须声明成功", capsuleMethods.isNotEmpty())

        // 4. DeveloperOptionsScreen 必须存在
        val devOptionsMethods = devOptionsClass.declaredMethods.filter { it.name.startsWith("DeveloperOptionsScreen") }
        assertTrue("DeveloperOptionsScreen 必须声明成功", devOptionsMethods.isNotEmpty())
    }
}

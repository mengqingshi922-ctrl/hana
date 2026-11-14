package com.hana.agent.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 FeatureFlags 新增功能特性开关的默认值与持久化读写逻辑。
 */
class FeatureFlagsNewOptionsTest {

    @Test
    fun testDefaultValuesForNewFeatureFlags() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)

        // 默认值验证
        assertFalse("主动能力默认关闭", flags.proactiveEnabled())
        assertFalse("Root 辅助启动默认关闭", flags.rootHelperEnabled())
        assertFalse("Root 命令执行默认关闭", flags.rootCommandEnabled())
        assertTrue("Icon SoM 语义切分默认开启", flags.edgeIconSomEnabled())
        assertTrue("紧凑 DSL 默认开启", flags.compactDslEnabled())
    }

    @Test
    fun testMutatingNewFeatureFlags() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)

        // 修改开关值
        flags.setProactiveEnabled(true)
        flags.setRootHelperEnabled(true)
        flags.setRootCommandEnabled(true)
        flags.setEdgeIconSomEnabled(false)
        flags.setCompactDslEnabled(false)

        // 校验修改后的新实例读取
        val newFlags = FeatureFlags(prefs)
        assertTrue(newFlags.proactiveEnabled())
        assertTrue(newFlags.rootHelperEnabled())
        assertTrue(newFlags.rootCommandEnabled())
        assertFalse(newFlags.edgeIconSomEnabled())
        assertFalse(newFlags.compactDslEnabled())
    }
}

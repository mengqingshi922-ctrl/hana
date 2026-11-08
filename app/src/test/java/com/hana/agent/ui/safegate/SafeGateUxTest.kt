package com.hana.agent.ui.safegate

import android.content.ContextWrapper
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 验证安全接管门触觉震动管理器在受限或 Mock 环境下的稳健性
 */
class SafeGateUxTest {

    private class TestContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun triggerClickThumpExecutesSafelyWithMockContext() {
        val testContext = TestContext()
        // 验证即使在无硬件震动器的 Mock/JVM 环境下，triggerClickThump 也具备完备的容错与安全守卫，不发生崩溃
        SafeGateHaptics.triggerClickThump(testContext)
        assertNotNull(SafeGateHaptics)
    }
}

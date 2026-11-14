package com.hana.agent.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证在不同屏幕亮灭与锁屏状态下 [LockState.Reading.locked] 的判断逻辑。
 * 特别针对 HyperOS 等 ROM 在解锁后 [android.app.KeyguardManager.isKeyguardLocked] 仍返回 true 的场景，
 * 采用 deviceLocked 作为真实锁屏依据。
 */
class DeviceLockStateTest {

    @Test
    fun testInteractiveWithKeyguardLockedButNotDeviceLocked() {
        // 当屏幕亮起且 keyguard 报 locked，但设备未真正上锁 (PIN/密码/图案已解锁) 时，不应判定为 locked
        val reading = LockState.Reading(interactive = true, keyguardLocked = true, deviceLocked = false)
        assertFalse("亮屏且 deviceLocked 为 false 时应该未锁定", reading.locked)
    }

    @Test
    fun testScreenOffIsAlwaysLocked() {
        // 熄屏无论锁屏状态均应判定为 locked
        val reading1 = LockState.Reading(interactive = false, keyguardLocked = false, deviceLocked = false)
        assertTrue("熄屏应视为 locked", reading1.locked)

        val reading2 = LockState.Reading(interactive = false, keyguardLocked = true, deviceLocked = true)
        assertTrue("熄屏且锁屏应视为 locked", reading2.locked)
    }

    @Test
    fun testDeviceLockedIsLocked() {
        // 亮屏但设备真正锁定时应判定为 locked
        val reading = LockState.Reading(interactive = true, keyguardLocked = true, deviceLocked = true)
        assertTrue("亮屏但 deviceLocked 为 true 时应为 locked", reading.locked)
    }
}

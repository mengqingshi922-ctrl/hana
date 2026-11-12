package com.hana.agent.edge

import android.os.PowerManager
import com.hana.agent.core.agent.ThermalLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceThermalMonitorTest {

    @Test
    fun mapsPowerManagerThermalConstantsCorrectly() {
        assertEquals(ThermalLevel.NONE, DeviceThermalMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_NONE))
        assertEquals(ThermalLevel.LIGHT, DeviceThermalMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(ThermalLevel.MODERATE, DeviceThermalMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(ThermalLevel.SEVERE, DeviceThermalMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(ThermalLevel.CRITICAL, DeviceThermalMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals(ThermalLevel.CRITICAL, DeviceThermalMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_EMERGENCY))
    }
}

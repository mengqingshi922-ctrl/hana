package com.hana.agent.edge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.hana.agent.core.agent.DeviceHealth
import com.hana.agent.core.agent.ModelRouter
import com.hana.agent.core.agent.ThermalLevel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 端侧设备热状态与电量水位监听器（位于 :app/edge）：
 * 订阅系统 [PowerManager] 热状态变化与电量/省电模式变化，并实时回调给 [ModelRouter]，
 * 在设备过热或低电量时触发并发缩减与轻量模型降级。
 */
class DeviceThermalMonitor(private val context: Context) {

    fun interface Listener {
        fun onHealthChanged(health: DeviceHealth)
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var started = false
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var batteryReceiver: BroadcastReceiver? = null

    @Volatile
    var latestHealth: DeviceHealth = probeCurrentHealth()
        private set

    fun bind(router: ModelRouter): AutoCloseable {
        val listener = Listener { health -> router.onDeviceHealthChanged(health) }
        addListener(listener)
        router.onDeviceHealthChanged(latestHealth)
        start()
        return AutoCloseable {
            removeListener(listener)
        }
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            val appCtx = context.applicationContext
            val powerManager = runCatching {
                appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            }.getOrNull()

            if (powerManager != null) {
                val tl = PowerManager.OnThermalStatusChangedListener { status ->
                    updateHealth(latestHealth.copy(thermalLevel = mapThermalStatus(status)))
                }
                runCatching {
                    powerManager.addThermalStatusListener(appCtx.mainExecutor, tl)
                    thermalListener = tl
                }
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    updateHealth(readFromContextAndIntent(appCtx, intent))
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    appCtx.registerReceiver(receiver, filter)
                }
                batteryReceiver = receiver
            }
            updateHealth(probeCurrentHealth())
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            val appCtx = context.applicationContext
            val powerManager = runCatching {
                appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            }.getOrNull()
            thermalListener?.let { tl ->
                runCatching { powerManager?.removeThermalStatusListener(tl) }
            }
            thermalListener = null
            batteryReceiver?.let { br ->
                runCatching { appCtx.unregisterReceiver(br) }
            }
            batteryReceiver = null
        }
    }

    fun probeCurrentHealth(): DeviceHealth = readFromContextAndIntent(context.applicationContext, null)

    private fun updateHealth(next: DeviceHealth) {
        val changed: Boolean
        synchronized(lock) {
            changed = next != latestHealth
            latestHealth = next
        }
        if (changed) {
            listeners.forEach { it.onHealthChanged(next) }
        }
    }

    private fun readFromContextAndIntent(appCtx: Context, batteryIntent: Intent?): DeviceHealth {
        val power = runCatching { appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
        val bm = runCatching { appCtx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }.getOrNull()

        val thermalStatus = runCatching { power?.currentThermalStatus }.getOrNull() ?: PowerManager.THERMAL_STATUS_NONE
        val powerSave = runCatching { power?.isPowerSaveMode }.getOrNull() ?: false

        val intent = batteryIntent ?: runCatching {
            appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val pct = if (intent != null && intent.hasExtra(BatteryManager.EXTRA_LEVEL) && intent.hasExtra(BatteryManager.EXTRA_SCALE)) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
                .getOrNull()?.takeIf { it in 0..100 } ?: 100
        }

        val charging = if (intent != null && intent.hasExtra(BatteryManager.EXTRA_STATUS)) {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } else {
            runCatching { bm?.isCharging }.getOrNull() ?: false
        }

        return DeviceHealth(
            thermalLevel = mapThermalStatus(thermalStatus),
            batteryPct = pct,
            isCharging = charging,
            isPowerSaveMode = powerSave,
        )
    }

    companion object {
        fun mapThermalStatus(status: Int): ThermalLevel = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
            status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            status >= PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            else -> ThermalLevel.NONE
        }
    }
}

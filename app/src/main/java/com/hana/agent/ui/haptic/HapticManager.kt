package com.hana.agent.ui.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 四阶触觉反馈模式定义：
 * - [LightTick]：轻微刻度齿轮感（滚轮微调、列表触底）
 * - [ElasticSnap]：弹性吸附冲击感（胶囊磁吸贴边、弹簧回弹）
 * - [SuccessBloom]：多段阶梯成功盛开感（任务达成、核验通过）
 * - [GateWarning]：两段式警示阻断重击（高危安全接管放行、删除/支付门禁）
 */
enum class HapticPattern {
    LightTick, ElasticSnap, SuccessBloom, GateWarning
}

/**
 * 四阶统一触觉中枢 (Unified Haptic Central Manager)
 *
 * 核心技术规范：
 * 1. 硬件感知与马达特性识别：自动检测线性马达 (LRA) 与偏心转子马达 (ERM)；
 * 2. 偏心转子马达 (ERM) 紧凑降级：ERM 存在 30~50ms 机械起停惯性，若直接播放复合波形（如 20ms+40ms+60ms），
 *    会导致机械起停无法完成而物理糊化成 120ms 连续刺耳蜂鸣。HapticManager 对 ERM 自动降级为单次紧凑脉冲
 *    （GateWarning 降级为精准 35ms 单次脉冲）；
 * 3. 稳健容错：对 JVM 虚拟化、单元测试 Mock Context 及无振动硬件环境 100% 空指针防崩守卫。
 */
object HapticManager {

    /**
     * 判断当前设备是否具备高精线性马达（LRA / Linear Resonant Actuator）。
     * 在 Android 11+ 上可以通过 hasAmplitudeControl 与振动组合原语等能力进行判定；
     * 偏心转子马达（ERM）通常不支持丰富的振幅精细控制。
     */
    fun isLinearActuator(context: Context): Boolean {
        return try {
            val vibrator = getVibrator(context) ?: return false
            if (!vibrator.hasVibrator()) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.hasAmplitudeControl()
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 触发对应模式的触觉反馈 */
    fun trigger(context: Context, pattern: HapticPattern) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            val isLra = isLinearActuator(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (isLra) {
                    when (pattern) {
                        HapticPattern.LightTick -> {
                            // LRA = 8ms(60) 极致细腻微刻度
                            VibrationEffect.createOneShot(8, 60)
                        }
                        HapticPattern.ElasticSnap -> {
                            // LRA = 15ms(120) 弹性贴边吸附
                            VibrationEffect.createOneShot(15, 120)
                        }
                        HapticPattern.SuccessBloom -> {
                            // LRA = 20ms(80) + 30ms(0) + 35ms(180) 盛开式双峰波形
                            val timings = longArrayOf(0, 20, 30, 35)
                            val amplitudes = intArrayOf(0, 80, 0, 180)
                            VibrationEffect.createWaveform(timings, amplitudes, -1)
                        }
                        HapticPattern.GateWarning -> {
                            // LRA = 20ms(160) + 40ms(0) + 60ms(255) 饱满重击复合两段式
                            val timings = longArrayOf(0, 20, 40, 60)
                            val amplitudes = intArrayOf(0, 160, 0, 255)
                            VibrationEffect.createWaveform(timings, amplitudes, -1)
                        }
                    }
                } else {
                    // ERM 偏心转子马达降级：避免起停惯性糊化，全部采用紧凑单次脉冲
                    when (pattern) {
                        HapticPattern.LightTick -> {
                            // ERM 降级 = 单次 15ms 柔和脉冲
                            VibrationEffect.createOneShot(15, 50)
                        }
                        HapticPattern.ElasticSnap -> {
                            // ERM 降级 = 单次 25ms 弹性冲击
                            VibrationEffect.createOneShot(25, 100)
                        }
                        HapticPattern.SuccessBloom -> {
                            // ERM 降级 = 单次 40ms 柔和振动
                            VibrationEffect.createOneShot(40, 120)
                        }
                        HapticPattern.GateWarning -> {
                            // ERM 降级 = 单次 35ms 紧凑脉冲（严格规避 120ms 连续蜂鸣）
                            VibrationEffect.createOneShot(35, 200)
                        }
                    }
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                when (pattern) {
                    HapticPattern.LightTick -> vibrator.vibrate(15)
                    HapticPattern.ElasticSnap -> vibrator.vibrate(25)
                    HapticPattern.SuccessBloom -> vibrator.vibrate(40)
                    HapticPattern.GateWarning -> {
                        if (isLra) vibrator.vibrate(longArrayOf(0, 20, 40, 60), -1)
                        else vibrator.vibrate(35)
                    }
                }
            }
        } catch (_: Throwable) {
            // 容错：防止受限环境、权限缺失或单元测试纯 Mock Context 崩溃
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator ?: context.getSystemService(Vibrator::class.java)
            } else {
                context.getSystemService(Vibrator::class.java)
            }
        } catch (_: Throwable) {
            null
        }
    }
}

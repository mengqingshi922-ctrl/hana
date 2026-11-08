package com.hana.agent.core.edge

/**
 * 端侧神经核运行档位枚举
 */
enum class EdgeModelTier {
    NONE,                   // 硬件不达标，纯云端推理
    POCKET_0_3B_CLASSIFIER, // 0.3B 轻量语义/分类反射引擎 (常驻内存 <= 120MB)
    VLM_1_5B_FULL,          // 1.5B 多模态视觉神经核 (内存占用约 1.1GB, 需 NPU 加速)
}

/**
 * 物理硬件规格描述
 */
data class HardwareSpec(
    val totalRamBytes: Long,
    val hasDedicatedNpu: Boolean,
    val socModel: String? = null,
    val cpuCoreCount: Int = Runtime.getRuntime().availableProcessors(),
    val batteryPct: Int? = null,
    val isThermalThrottling: Boolean = false,
) {
    val totalRamGb: Double get() = totalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    // 内存 < 8GB (7.5GB 容差) 或无专用 NPU 或温控严重/电量过低时强制封杀 1.5B 载入
    val isEligibleFor1_5B: Boolean get() = totalRamGb >= 7.5 && hasDedicatedNpu && !isThermalThrottling && (batteryPct == null || batteryPct >= 15)
    val isEligibleFor0_3B: Boolean get() = totalRamGb >= 3.5 && !isThermalThrottling && (batteryPct == null || batteryPct >= 10)
}

/**
 * 硬件探针接口契约：
 * 负责评估设备内存、NPU、温控与电池，约束端侧小模型运行档位。
 */
interface HardwareProbe {
    fun probe(): HardwareSpec

    fun evaluateAllowedTier(): EdgeModelTier {
        val spec = probe()
        return when {
            spec.isEligibleFor1_5B -> EdgeModelTier.VLM_1_5B_FULL
            spec.isEligibleFor0_3B -> EdgeModelTier.POCKET_0_3B_CLASSIFIER
            else -> EdgeModelTier.NONE
        }
    }
}

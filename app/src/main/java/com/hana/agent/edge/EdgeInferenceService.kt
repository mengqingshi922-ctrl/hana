package com.hana.agent.edge

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import com.hana.agent.vision.DetectedIcon
import com.hana.agent.vision.EdgeIconDetector
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 硬件探针 (Hardware Probe)
 *
 * 评估端侧硬件算力规格与实时热状态，实施模型分级加载与自适应降级：
 * - 物理 RAM >= 8GB 且具备 NPU/APU 硬件加速 -> 允许激活 1.5B VLM 双环引擎
 * - 物理 RAM < 8GB 或缺乏专用 NPU -> 强制禁用 1.5B VLM，仅允许运行 0.3B 轻量反射核
 * - 低电量 (< 15%) 或系统温控达 THERMAL_STATUS_SEVERE -> 平滑降级为全云端推理，保护电池与主进程存活
 */
object HardwareProbe {

    data class HardwareProfile(
        val totalRamBytes: Long,
        val hasNpu: Boolean,
        val batteryLevel: Int = 100, // 0..100
        val isThermalThrottling: Boolean = false,
    )

    enum class ModelTier {
        CloudOnly,
        Lightweight03B,
        DualEngine15B,
    }

    const val MIN_RAM_FOR_15B_BYTES: Long = 8L * 1024L * 1024L * 1024L // 8GB

    fun evaluateTier(profile: HardwareProfile): ModelTier {
        if (profile.batteryLevel < 15 || profile.isThermalThrottling) {
            return ModelTier.CloudOnly
        }
        if (profile.totalRamBytes >= MIN_RAM_FOR_15B_BYTES && profile.hasNpu) {
            return ModelTier.DualEngine15B
        }
        return ModelTier.Lightweight03B
    }

    fun canRun15bVlm(profile: HardwareProfile): Boolean =
        evaluateTier(profile) == ModelTier.DualEngine15B

    fun isNpuAvailable(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            pm.hasSystemFeature("android.hardware.npu") ||
                Build.HARDWARE.contains("qcom", ignoreCase = true) ||
                Build.HARDWARE.contains("kirin", ignoreCase = true) ||
                Build.HARDWARE.contains("dimensity", ignoreCase = true) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (Build.SOC_MANUFACTURER.contains("Qualcomm", ignoreCase = true) ||
                        Build.SOC_MANUFACTURER.contains("MediaTek", ignoreCase = true)))
        } catch (_: Throwable) {
            false
        }
    }

    fun probeSystem(context: Context): HardwareProfile {
        val ram = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            memInfo.totalMem
        } catch (_: Throwable) {
            4L * 1024L * 1024L * 1024L
        }

        val npu = isNpuAvailable(context)

        val bm = try {
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        } catch (_: Throwable) { null }
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

        val power = try {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (_: Throwable) { null }
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) {
            try {
                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            } catch (_: Throwable) { false }
        } else false

        return HardwareProfile(
            totalRamBytes = ram,
            hasNpu = npu,
            batteryLevel = battery,
            isThermalThrottling = thermal,
        )
    }
}

/**
 * 端侧推理独立子进程服务 (:edge_inference)
 *
 * 架构考量：
 * 1. 独立进程隔离：声明 android:process=":edge_inference"，拥有独立的 Linux 进程空间与 Native 堆；
 * 2. 内存与 OOM 保护：1.5B/0.3B 模型加载与推理发生在该子进程中，即使触发极端内存压力被系统 LMK 强杀，
 *    也不会波及主无障碍服务 (:app)，主服务感知 Binder 死亡通知后可毫秒级平滑回退至云端推理；
 * 3. 硬件启动探针：绑定与推理前经 HardwareProbe 校验硬件资格。
 */
class EdgeInferenceService : Service() {

    private val binder = object : IEdgeInference.Stub() {
        override fun isAvailable(): Boolean {
            val profile = HardwareProbe.probeSystem(this@EdgeInferenceService)
            if (HardwareProbe.evaluateTier(profile) == HardwareProbe.ModelTier.CloudOnly) return false
            val engine = runCatching { MnnPocketEngine.getInstance(this@EdgeInferenceService) }.getOrNull()
            return engine?.isReady == true || HardwareProbe.canRun15bVlm(profile)
        }

        override fun classifyIntent(task: String?): String {
            if (task.isNullOrBlank()) return "general"
            val engine = runCatching { MnnPocketEngine.getInstance(this@EdgeInferenceService) }.getOrNull()
            if (engine != null) {
                val intent = runCatching {
                    kotlinx.coroutines.runBlocking { engine.classifyIntent(task) }
                }.getOrNull()
                if (intent != null) {
                    return when (intent) {
                        com.hana.agent.core.agent.IntentGate.Intent.Act -> "act"
                        com.hana.agent.core.agent.IntentGate.Intent.Read -> "read"
                        com.hana.agent.core.agent.IntentGate.Intent.Discuss -> "discuss"
                    }
                }
            }
            return fallbackClassifyIntent(task)
        }

        override fun locateVisualElement(jpegShot: ByteArray?, query: String?): String {
            val engine = runCatching { MnnPocketEngine.getInstance(this@EdgeInferenceService) }.getOrNull()
            val runtimeDesc = engine?.describeRuntimeMode()
                ?: if (MnnPocketEngine.isNativeLoaded) "native_so_loaded" else "kotlin_heuristic"
            val nativeLoaded = MnnPocketEngine.isNativeLoaded

            if (jpegShot == null || jpegShot.isEmpty()) {
                return buildLocateJson(
                    status = "degraded",
                    backend = runtimeDesc,
                    nativeLoaded = nativeLoaded,
                    query = query.orEmpty(),
                    reason = "empty_screenshot",
                    matched = null,
                    candidates = emptyList(),
                )
            }

            val bmp = runCatching { BitmapFactory.decodeByteArray(jpegShot, 0, jpegShot.size) }.getOrNull()
                ?: return buildLocateJson(
                    status = "degraded",
                    backend = runtimeDesc,
                    nativeLoaded = nativeLoaded,
                    query = query.orEmpty(),
                    reason = "invalid_image_bytes",
                    matched = null,
                    candidates = emptyList(),
                )

            return try {
                val w = bmp.width
                val h = bmp.height
                if (w <= 0 || h <= 0) {
                    buildLocateJson("degraded", runtimeDesc, nativeLoaded, query.orEmpty(), "invalid_bitmap_size", null, emptyList())
                } else {
                    val pixels = IntArray(w * h)
                    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                    locateFromPixels(pixels, w, h, query, runtimeDesc, nativeLoaded)
                }
            } finally {
                bmp.recycle()
            }
        }

        override fun runInference(input: Bundle?): Bundle {
            val action = input?.getString("action") ?: "health_check"
            val engine = runCatching { MnnPocketEngine.getInstance(this@EdgeInferenceService) }.getOrNull()
            val status = engine?.getRuntimeStatus()
            val nativeLoaded = MnnPocketEngine.isNativeLoaded
            val backendName = status?.backend?.name
                ?: if (nativeLoaded) "NATIVE_MNN_WITH_HEURISTIC_FALLBACK" else "KOTLIN_HEURISTIC_ONLY"
            val runtimeDesc = status?.description
                ?: if (nativeLoaded) "Native SO 已加载" else "纯 Kotlin 启发式规则引擎（Native SO 未载入）"

            return Bundle().apply {
                putBoolean("success", true)
                putString("result", if (status?.engineReady == true) "ok" else "degraded_heuristic")
                putString("action", action)
                putBoolean("in_process_engine_available", engine != null)
                putBoolean("engine_ready", status?.engineReady == true)
                putBoolean("native_loaded", nativeLoaded)
                putBoolean("model_weights_ready", status?.modelReady == true)
                putBoolean("memory_constrained", status?.isMemoryConstrained == true)
                putString("backend", backendName)
                putString("runtime_description", runtimeDesc)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        /** 纯 Kotlin 语义关键词意图回退分类 */
        internal fun fallbackClassifyIntent(task: String): String {
            val clean = task.trim()
            if (clean.isBlank()) return "general"
            if (clean.contains("支付") || clean.contains("买") || clean.contains("转账") || clean.contains("下单")) {
                return "transaction"
            }
            if (clean.startsWith("查") || clean.startsWith("看") || clean.contains("查询") || clean.contains("搜索")) {
                return "read"
            }
            if (clean.contains("打开") || clean.contains("启动") || clean.contains("点击") || clean.contains("发给")) {
                return "act"
            }
            return "navigation"
        }

        /**
         * 基于纯 Kotlin 端侧视觉检测（EdgeIconDetector）与语义关键词匹配的视觉定位实现，
         * 支持在无 Native VLM 权重时输出结构化候选坐标与降级状态，可在 JVM 单元测试直接验证。
         */
        internal fun locateFromPixels(
            pixels: IntArray,
            width: Int,
            height: Int,
            query: String?,
            backend: String = if (MnnPocketEngine.isNativeLoaded) "native_mnn" else "kotlin_heuristic",
            nativeLoaded: Boolean = MnnPocketEngine.isNativeLoaded,
        ): String {
            val icons = EdgeIconDetector.detectIcons(pixels, width, height)
            val cleanQuery = query?.trim().orEmpty()
            val matched = selectBestSemanticMatch(icons, cleanQuery)
            val status = when {
                icons.isEmpty() -> "no_candidates"
                cleanQuery.isNotEmpty() && matched == null -> "no_match"
                else -> "ok"
            }
            return buildLocateJson(
                status = status,
                backend = backend,
                nativeLoaded = nativeLoaded,
                query = cleanQuery,
                reason = if (icons.isEmpty()) "no_visual_icons_detected" else null,
                matched = matched,
                candidates = icons,
            )
        }

        private fun selectBestSemanticMatch(icons: List<DetectedIcon>, query: String): DetectedIcon? {
            if (icons.isEmpty() || query.isBlank()) return null
            val targetLabel = when {
                listOf("关闭", "叉", "关掉", "取消", "跳过", "close", "dismiss").any { query.contains(it, ignoreCase = true) } -> "icon_close"
                listOf("返回", "后退", "上一页", "back").any { query.contains(it, ignoreCase = true) } -> "icon_back"
                listOf("搜索", "查找", "搜", "放大镜", "search").any { query.contains(it, ignoreCase = true) } -> "icon_search"
                listOf("更多", "菜单", "设置", "选项", "more", "menu").any { query.contains(it, ignoreCase = true) } -> "icon_more"
                listOf("购物", "加购", "车", "cart").any { query.contains(it, ignoreCase = true) } -> "icon_cart"
                listOf("添加", "新建", "加号", "发布", "add", "plus").any { query.contains(it, ignoreCase = true) } -> "icon_add"
                listOf("分享", "转发", "share").any { query.contains(it, ignoreCase = true) } -> "icon_share"
                else -> null
            }

            if (targetLabel != null) {
                icons.filter { it.label == targetLabel }
                    .maxByOrNull { it.confidence }
                    ?.let { return it }
            }

            // 直接按英文标签名片段回退匹配
            return icons.filter { query.contains(it.label, ignoreCase = true) || query.contains(it.label.removePrefix("icon_"), ignoreCase = true) }
                .maxByOrNull { it.confidence }
        }

        private fun buildLocateJson(
            status: String,
            backend: String,
            nativeLoaded: Boolean,
            query: String,
            reason: String?,
            matched: DetectedIcon?,
            candidates: List<DetectedIcon>,
        ): String = buildJsonObject {
            put("status", status)
            put("backend", backend)
            put("native_loaded", nativeLoaded)
            put("query", query)
            if (reason != null) put("reason", reason)
            if (matched != null) {
                putJsonObject("matched") {
                    put("label", matched.label)
                    put("confidence", matched.confidence)
                    put("bounds", buildJsonArray { matched.bounds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    put("centerX", (matched.bounds[0] + matched.bounds[2]) / 2)
                    put("centerY", (matched.bounds[1] + matched.bounds[3]) / 2)
                }
            }
            put("candidates", buildJsonArray {
                for (icon in candidates) {
                    add(buildJsonObject {
                        put("label", icon.label)
                        put("confidence", icon.confidence)
                        put("bounds", buildJsonArray { icon.bounds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    })
                }
            })
        }.toString()
    }
}

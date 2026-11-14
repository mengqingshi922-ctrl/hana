package com.hana.agent.core.agent

import com.hana.agent.core.model.ChatModel
import com.hana.agent.core.tool.Verb
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 异构模型角色槽位场景：
 * - [Chat]：常规对话与任务规划
 * - [VisionAction]：视觉识屏与 UI 动作执行
 * - [MemorySummary]：历史压缩与记忆摘要归纳
 * - [EdgeReflex]：端侧快速反射与轻量局部步爆发
 */
enum class ModelScene {
    Chat,
    VisionAction,
    MemorySummary,
    EdgeReflex,
}

/**
 * 分场景模型角色槽位配置。
 */
data class ModelSlots(
    val chat: ChatModel,
    val visionAction: ChatModel = chat,
    val memorySummary: ChatModel? = null,
    val edgeReflex: ChatModel? = null,
)

/**
 * 端侧设备热状态等级（与 Android PowerManager.THERMAL_STATUS_* 对齐）。
 */
enum class ThermalLevel(val severity: Int) {
    NONE(0),
    LIGHT(1),
    MODERATE(2),
    SEVERE(3),
    CRITICAL(4),
}

/**
 * 端侧设备实时热状态与电量水位快照。
 */
data class DeviceHealth(
    val thermalLevel: ThermalLevel = ThermalLevel.NONE,
    val batteryPct: Int = 100,
    val isCharging: Boolean = false,
    val isPowerSaveMode: Boolean = false,
) {
    val clampedBatteryPct: Int get() = batteryPct.coerceIn(0, 100)
    val isLowBattery: Boolean get() = !isCharging && clampedBatteryPct < LOW_BATTERY_PCT
    val isCriticalBattery: Boolean get() = !isCharging && clampedBatteryPct < CRITICAL_BATTERY_PCT
    val isThermalThrottling: Boolean get() = thermalLevel >= ThermalLevel.SEVERE

    companion object {
        const val LOW_BATTERY_PCT = 20
        const val CRITICAL_BATTERY_PCT = 15
        val HEALTHY = DeviceHealth()
    }
}

/**
 * 根据设备热状态与电量水位计算出的动态调度策略。
 */
data class RoutingPolicy(
    val maxConcurrency: Int,
    val preferLightweightModel: Boolean,
    val allowHeavyLocalVlm: Boolean,
    val maxConsecutiveLightSteps: Int,
    val reason: String,
)

/** 决定下一步用哪个模型时看的上一步情况。 */
data class StepContext(
    val step: Int,
    val lastOk: Boolean,
    val lastHadGuard: Boolean,
    /** 上一步之后画面变没变;读屏一类不报时为 null。 */
    val lastChanged: Boolean?,
    /** 回放交还、批量停止、核查退回、接管或追问恢复。 */
    val lastWasRecovery: Boolean,
    val sameAppAsLast: Boolean,
    /** 要发出的上下文里有截图;不看图的轻量模型不能接。 */
    val hasImages: Boolean = false,
    /** 当前调用所属的场景角色槽位。 */
    val scene: ModelScene = ModelScene.VisionAction,
)

/**
 * 置信度门控信号输入。
 * @property stepConfidence 单步动作置信度（0.0 ~ 1.0）
 * @property treeQualityPoor 无障碍控件树质量是否低下（盲区大）
 * @property consecutiveLowConfidence 连续低置信度步数
 * @property ambiguityDetected 是否检测到意图/操作歧义
 */
data class ConfidenceSignal(
    val stepConfidence: Float,
    val treeQualityPoor: Boolean = false,
    val consecutiveLowConfidence: Int = 0,
    val ambiguityDetected: Boolean = false,
)

/**
 * 路由决策分级阶梯：
 * - [EDGE_REFLEX]：端侧即时反射轻量模型槽位
 * - [LIGHTWEIGHT]：轻量辅助模型槽位（如 MemorySummary / EdgeReflex）
 * - [CLOUD_MAIN]：云端主力高认知大模型
 */
enum class RouteTier {
    EDGE_REFLEX,
    LIGHTWEIGHT,
    CLOUD_MAIN,
}

/**
 * 路由门控决策结果。
 */
data class TierDecision(
    val tier: RouteTier,
    val model: ChatModel,
    val reason: String,
)

/**
 * 热感知多维度异构模型路由器：
 * 1. 支持按连续步数与纠错状态在主模型与轻量模型之间动态切换；
 * 2. 支持按场景槽位（Chat / VisionAction / MemorySummary / EdgeReflex）精确分配模型；
 * 3. 支持接收端侧设备热状态（PowerManager）与电量水位回调，在过热或低电量时自动缩减并发并降级轻量模型。
 */
class ModelRouter(
    val slots: ModelSlots,
    private val baseConcurrency: Int = DEFAULT_BASE_CONCURRENCY,
    initialHealth: DeviceHealth = DeviceHealth.HEALTHY,
) {
    init {
        require(baseConcurrency >= 1) { "baseConcurrency must be >= 1" }
    }

    /** 兼容旧双参数构造：主模型承担 Chat 与 VisionAction，轻量模型承担 EdgeReflex 与 MemorySummary。 */
    constructor(main: ChatModel, light: ChatModel?) : this(
        slots = ModelSlots(
            chat = main,
            visionAction = main,
            memorySummary = light,
            edgeReflex = light,
        ),
    )

    fun interface PolicyListener {
        fun onPolicyChanged(health: DeviceHealth, policy: RoutingPolicy)
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<PolicyListener>()

    private var forcedMain = 0
    private var disabled = false
    private var consecutiveLightSteps = 0

    @Volatile
    private var health: DeviceHealth = initialHealth

    @Volatile
    private var policy: RoutingPolicy = evaluatePolicy(initialHealth, baseConcurrency)

    val deviceHealth: DeviceHealth get() = health
    val currentPolicy: RoutingPolicy get() = policy
    val recommendedConcurrency: Int get() = policy.maxConcurrency

    /** 轻量模型能不能接手机步;不能时 lightSteps 不生效。 */
    val active: Boolean
        get() = !disabled && (slots.edgeReflex ?: slots.memorySummary)?.capabilities?.toolCalls == true

    /** 轻量模型出了重试也没用的错(Key、额度、模型名、请求不对):余下都用主模型。一个 router 只服务一次任务。 */
    fun disable() {
        synchronized(lock) {
            disabled = true
            consecutiveLightSteps = 0
        }
    }

    fun addPolicyListener(listener: PolicyListener) {
        listeners.addIfAbsent(listener)
    }

    fun removePolicyListener(listener: PolicyListener) {
        listeners.remove(listener)
    }

    /** 端侧热状态与电量水位统一回调入口。 */
    fun onDeviceHealthChanged(newHealth: DeviceHealth): RoutingPolicy {
        val nextPolicy: RoutingPolicy
        synchronized(lock) {
            health = newHealth
            nextPolicy = evaluatePolicy(newHealth, baseConcurrency)
            policy = nextPolicy
        }
        listeners.forEach { it.onPolicyChanged(newHealth, nextPolicy) }
        return nextPolicy
    }

    /** 系统 PowerManager 热状态回调快捷入口。 */
    fun onThermalStatusChanged(level: ThermalLevel): RoutingPolicy =
        onDeviceHealthChanged(health.copy(thermalLevel = level))

    /** 电量水位回调快捷入口。 */
    fun onBatteryLevelChanged(
        batteryPct: Int,
        isCharging: Boolean = health.isCharging,
        isPowerSaveMode: Boolean = health.isPowerSaveMode,
    ): RoutingPolicy = onDeviceHealthChanged(
        health.copy(
            batteryPct = batteryPct.coerceIn(0, 100),
            isCharging = isCharging,
            isPowerSaveMode = isPowerSaveMode,
        ),
    )

    fun pick(ctx: StepContext): ChatModel = pickForScene(ctx.scene, ctx)

    fun pickForScene(scene: ModelScene, ctx: StepContext? = null): ChatModel {
        val hasImages = ctx?.hasImages == true
        val currentPolicy = policy
        return when (scene) {
            ModelScene.MemorySummary -> {
                val summaryModel = (slots.memorySummary ?: slots.edgeReflex)
                    ?.takeIf { !disabled && (!hasImages || it.capabilities.vision) }
                summaryModel ?: slots.chat
            }

            ModelScene.EdgeReflex -> {
                val reflex = slots.edgeReflex
                    ?.takeIf { !disabled && it.capabilities.toolCalls && (!hasImages || it.capabilities.vision) }
                reflex ?: slots.visionAction
            }

            ModelScene.Chat -> {
                if (currentPolicy.preferLightweightModel && !disabled) {
                    val lightFallback = (slots.edgeReflex ?: slots.memorySummary)
                        ?.takeIf { !hasImages || it.capabilities.vision }
                    if (lightFallback != null) return lightFallback
                }
                slots.chat
            }

            ModelScene.VisionAction -> pickVisionAction(ctx, currentPolicy)
        }
    }

    private fun pickVisionAction(ctx: StepContext?, currentPolicy: RoutingPolicy): ChatModel {
        val mainAction = slots.visionAction
        val lightAction = (slots.edgeReflex ?: slots.memorySummary)?.takeIf { !disabled && it.capabilities.toolCalls }

        if (ctx == null) {
            return if (currentPolicy.preferLightweightModel && lightAction != null) lightAction else mainAction
        }

        synchronized(lock) {
            if (!ctx.lastOk || ctx.lastHadGuard || ctx.lastChanged == false) {
                noteTroubleLocked()
            }
            if (forcedMain > 0) {
                forcedMain--
                consecutiveLightSteps = 0
                return mainAction
            }
            val l = lightAction ?: return mainAction
            val visionOk = !ctx.hasImages || l.capabilities.vision
            val eligible = if (currentPolicy.preferLightweightModel) {
                !ctx.lastWasRecovery && visionOk
            } else {
                ctx.step > 1 && !ctx.lastWasRecovery && ctx.sameAppAsLast && visionOk
            }

            return if (eligible && consecutiveLightSteps < currentPolicy.maxConsecutiveLightSteps) {
                consecutiveLightSteps++
                l
            } else {
                consecutiveLightSteps = 0
                mainAction
            }
        }
    }

    /**
     * MAI-UI 端云协同置信度路由门控：
     * 根据单步置信度、UI 树质量、歧义检测及历史纠错状态决定模型调度分级阶梯。
     * 低置信度、树盲、歧义或恢复态强制上云端主模型；高置信度且无图时下放端侧轻量/反射槽位。
     */
    fun routeWithConfidence(
        ctx: StepContext,
        signal: ConfidenceSignal,
        threshold: Float = 0.65f,
    ): TierDecision {
        synchronized(lock) {
            val mainModel = slots.visionAction
            // 1. 强制上云主模型（CLOUD_MAIN）的各种边界与阻断条件
            if (disabled) {
                return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "router_disabled")
            }
            if (ctx.lastWasRecovery) {
                noteTroubleLocked()
                return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "recovery_state")
            }
            if (!ctx.lastOk || ctx.lastHadGuard || ctx.lastChanged == false) {
                noteTroubleLocked()
                return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "trouble_state")
            }
            if (forcedMain > 0) {
                forcedMain--
                consecutiveLightSteps = 0
                return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "forced_main")
            }
            if (signal.ambiguityDetected) {
                noteTroubleLocked()
                return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "ambiguity_detected")
            }
            if (signal.treeQualityPoor) {
                noteTroubleLocked()
                return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "tree_quality_poor")
            }
            if (signal.consecutiveLowConfidence > 0) {
                noteTroubleLocked()
                return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "consecutive_low_confidence")
            }
            if (signal.stepConfidence < threshold) {
                noteTroubleLocked()
                return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "low_confidence_${signal.stepConfidence}")
            }

            // 2. 检查是否有可用的轻量模型
            val reflexModel = slots.edgeReflex?.takeIf { it.capabilities.toolCalls }
            val lightModel = (slots.edgeReflex ?: slots.memorySummary)?.takeIf { it.capabilities.toolCalls }
            if (lightModel == null) {
                return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "no_lightweight_available")
            }

            // 3. 上下文带图时，若轻量模型不支持视觉则必须上云
            if (ctx.hasImages && !lightModel.capabilities.vision) {
                return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "has_images_unsupported_by_lightweight")
            }

            // 4. 高置信度且无图（或轻量模型支持看图）：下放轻量/反射槽位
            if (reflexModel != null && (ctx.scene == ModelScene.EdgeReflex || (!ctx.hasImages && ctx.sameAppAsLast && ctx.step > 1))) {
                if (consecutiveLightSteps < policy.maxConsecutiveLightSteps) {
                    consecutiveLightSteps++
                    return TierDecision(RouteTier.EDGE_REFLEX, reflexModel, "high_confidence_edge_reflex")
                }
            }

            if (consecutiveLightSteps < policy.maxConsecutiveLightSteps) {
                consecutiveLightSteps++
                return TierDecision(RouteTier.LIGHTWEIGHT, lightModel, "high_confidence_lightweight")
            }

            // 达到连续轻量步数上限，主动回退云端主模型复核
            consecutiveLightSteps = 0
            return TierDecision(RouteTier.CLOUD_MAIN, mainModel, "max_consecutive_light_steps_reached")
        }
    }

    /** 出了状况(含轻量模型调用失败、回复被改派给主模型):之后 [TROUBLE_STEPS] 步用主模型。 */
    fun noteTrouble() {
        synchronized(lock) {
            noteTroubleLocked()
        }
    }

    private fun noteTroubleLocked() {
        forcedMain = TROUBLE_STEPS
        consecutiveLightSteps = 0
    }

    fun isLight(m: ChatModel): Boolean =
        m !== slots.chat &&
            m !== slots.visionAction &&
            ((slots.edgeReflex != null && m === slots.edgeReflex) ||
                (slots.memorySummary != null && m === slots.memorySummary))

    companion object {
        const val TROUBLE_STEPS = 2
        const val DEFAULT_BASE_CONCURRENCY = 4
        /** 端侧轻量小模型最大连续自主爆发步数，到期主动回退主模型复核。 */
        const val MAX_CONSECUTIVE_LIGHT_STEPS = 8
        const val MAX_CONSECUTIVE_LIGHT_STEPS_THROTTLED = 16

        internal fun evaluatePolicy(health: DeviceHealth, baseConcurrency: Int): RoutingPolicy = when {
            health.isThermalThrottling || health.isCriticalBattery -> RoutingPolicy(
                maxConcurrency = 1,
                preferLightweightModel = true,
                allowHeavyLocalVlm = false,
                maxConsecutiveLightSteps = MAX_CONSECUTIVE_LIGHT_STEPS_THROTTLED,
                reason = if (health.isThermalThrottling) "thermal_${health.thermalLevel.name.lowercase()}" else "critical_battery_${health.clampedBatteryPct}pct",
            )
            health.thermalLevel == ThermalLevel.MODERATE || health.isLowBattery || health.isPowerSaveMode -> RoutingPolicy(
                maxConcurrency = maxOf(1, baseConcurrency / 2),
                preferLightweightModel = health.isLowBattery || health.isPowerSaveMode,
                allowHeavyLocalVlm = !health.isPowerSaveMode,
                maxConsecutiveLightSteps = MAX_CONSECUTIVE_LIGHT_STEPS,
                reason = when {
                    health.thermalLevel == ThermalLevel.MODERATE -> "thermal_moderate"
                    health.isPowerSaveMode -> "power_save_mode"
                    else -> "low_battery_${health.clampedBatteryPct}pct"
                },
            )
            else -> RoutingPolicy(
                maxConcurrency = baseConcurrency,
                preferLightweightModel = false,
                allowHeavyLocalVlm = true,
                maxConsecutiveLightSteps = MAX_CONSECUTIVE_LIGHT_STEPS,
                reason = "normal",
            )
        }

        /** 轻量模型给出这些调用(还有 do_steps)时不执行,这一步改问主模型:放弃、确认、接管、追问、沙箱命令都由主模型决定。 */
        private val MAIN_ONLY_VERBS = setOf(Verb.EndNotDone, Verb.RequestConfirmation, Verb.HandToUser, Verb.AskUser, Verb.HandleObstacle,
            Verb.PhoneRun, Verb.Collect, Verb.PhoneFind, Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneCommand, Verb.FindVisually) +
            // 沙箱里跑的是模型写的命令:只让主模型写
            setOf(Verb.RunCommand, Verb.RunBundledScript, Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile)

        /** 只能由主模型给出的调用([name] 是工具名,[verb] 是它做的动作)。 */
        fun mainOnly(name: String, verb: Verb?): Boolean = name == DO_STEPS_TOOL || verb in MAIN_ONLY_VERBS

        /** 这些动作之后的一步算恢复/纠错(等过用户、回退退出、视觉定位、等待)。严禁轻量模型无脑抢权！ */
        internal val RECOVERY_VERBS = setOf(
            Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation,
            Verb.Back, Verb.FindVisually, Verb.Diagnose, Verb.Wait
        )

        /** 这些动作会换前台 App 或网页(打开网址、换标签);之后的一步不算同一个地方。 */
        internal val APP_SWITCH_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.Navigate, Verb.Tabs)
    }
}

/** AgentLoop 用:记下每一步的情况,拼出下一步的 [StepContext]。 */
internal class StepTrack {
    private var ok = true
    private var guard = false
    private var changed: Boolean? = null
    private var recovery = false
    private var app: String? = null
    private var appBefore: String? = null
    private var switched = false

    fun context(step: Int, hasImages: Boolean) =
        StepContext(step, ok, guard, changed, recovery, !switched && ((app != null && app == appBefore) || (app == null && appBefore == null && step > 2)), hasImages)

    /** [app] 为 null 表示这一步的结果没报前台 App,沿用之前的。 */
    /** 刚从回放交还、批量停止这类状况里恢复:下一步按恢复步算。在 [after] 之后调用。 */
    fun markRecovery() { recovery = true }

    fun after(verb: Verb?, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) {
        this.ok = ok
        this.guard = guard
        this.changed = changed
        this.recovery = recovery || verb in ModelRouter.RECOVERY_VERBS
        appBefore = this.app
        if (app != null) this.app = app
        switched = verb in ModelRouter.APP_SWITCH_VERBS
    }
}

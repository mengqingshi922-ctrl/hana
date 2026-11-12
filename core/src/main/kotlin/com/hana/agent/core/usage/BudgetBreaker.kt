package com.hana.agent.core.usage

import com.hana.agent.core.model.Usage
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 任务预算熔断的四维指标。 */
enum class BudgetDimension(val key: String) {
    Tokens("tokens"),
    CostUsd("costUsd"),
    WallTimeMs("wallTimeMs"),
    ExternalCalls("externalCalls"),
}

/** 单价配置（每百万 Token 美元价格），用于将 [Usage] 折算为估算美元开销。 */
data class BudgetPricing(
    val inputPerMillionUsd: Double = 0.0,
    val outputPerMillionUsd: Double = 0.0,
    val cachedPerMillionUsd: Double = 0.0,
) {
    init {
        require(inputPerMillionUsd >= 0.0 && outputPerMillionUsd >= 0.0 && cachedPerMillionUsd >= 0.0) {
            "pricing rates must be non-negative"
        }
    }

    /** 按输入（扣除缓存命中）、缓存命中、输出三部分估算本次调用的美元费用。 */
    fun estimateCostUsd(usage: Usage): Double {
        val nonCachedInput = (usage.inputTokens - usage.cachedInputTokens).coerceAtLeast(0)
        return (nonCachedInput * inputPerMillionUsd +
            usage.cachedInputTokens * cachedPerMillionUsd +
            usage.outputTokens * outputPerMillionUsd) / 1_000_000.0
    }
}

/**
 * 四维任务硬预算配置：任一非空维度达到 [hardRatio]（默认 100%）触发熔断；
 * 达到 [warnRatio]（默认 80%）触发预警并建议降级到 `light` 模型。
 */
data class TaskBudget(
    val maxTokens: Long? = null,
    val maxCostUsd: Double? = null,
    val maxWallTimeMs: Long? = null,
    val maxExternalCalls: Int? = null,
    val warnRatio: Double = DEFAULT_WARN_RATIO,
    val hardRatio: Double = DEFAULT_HARD_RATIO,
) {
    init {
        require(maxTokens == null || maxTokens > 0L) { "maxTokens must be > 0" }
        require(maxCostUsd == null || maxCostUsd > 0.0) { "maxCostUsd must be > 0" }
        require(maxWallTimeMs == null || maxWallTimeMs > 0L) { "maxWallTimeMs must be > 0" }
        require(maxExternalCalls == null || maxExternalCalls > 0) { "maxExternalCalls must be > 0" }
        require(warnRatio in 0.0..1.0) { "warnRatio must be in 0.0..1.0" }
        require(hardRatio >= warnRatio && hardRatio > 0.0) { "hardRatio must be >= warnRatio and > 0" }
    }

    val isUnlimited: Boolean
        get() = maxTokens == null && maxCostUsd == null && maxWallTimeMs == null && maxExternalCalls == null

    companion object {
        const val DEFAULT_WARN_RATIO = 0.80
        const val DEFAULT_HARD_RATIO = 1.00
        val UNLIMITED = TaskBudget()
    }
}

/** 某一时刻四维预算消耗快照。 */
data class BudgetSnapshot(
    val tokensUsed: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val costUsd: Double,
    val wallTimeMs: Long,
    val externalCalls: Int,
    val budget: TaskBudget,
    val ratios: Map<BudgetDimension, Double>,
    val maxRatio: Double,
    val warnDimensions: Set<BudgetDimension>,
    val exceededDimensions: Set<BudgetDimension>,
) {
    val suggestLightDowngrade: Boolean get() = warnDimensions.isNotEmpty() || exceededDimensions.isNotEmpty()
    val exceeded: Boolean get() = exceededDimensions.isNotEmpty()
}

/** 预算水位检查结果：正常、80% 预警（建议降级 `light`）、100% 熔断挂起信号。 */
sealed interface BudgetWatermark {
    val snapshot: BudgetSnapshot

    data class Normal(override val snapshot: BudgetSnapshot) : BudgetWatermark

    data class Warning(
        override val snapshot: BudgetSnapshot,
        val dimensions: Set<BudgetDimension>,
        val maxRatio: Double,
        val suggestLightDowngrade: Boolean = true,
    ) : BudgetWatermark

    data class Exceeded(
        override val snapshot: BudgetSnapshot,
        val dimensions: Set<BudgetDimension>,
        val maxRatio: Double,
    ) : BudgetWatermark
}

/** 当任务四维预算达到硬限制（100%）时抛出的熔断异常。 */
class BudgetExceeded(
    val dimensions: Set<BudgetDimension>,
    val snapshot: BudgetSnapshot,
    message: String = formatMessage(dimensions, snapshot),
) : IllegalStateException(message) {
    companion object {
        internal fun formatMessage(dimensions: Set<BudgetDimension>, s: BudgetSnapshot): String {
            val detail = dimensions.joinToString(", ") { d ->
                when (d) {
                    BudgetDimension.Tokens -> "tokens=${s.tokensUsed}/${s.budget.maxTokens}"
                    BudgetDimension.CostUsd -> "costUsd=${"%.6f".format(s.costUsd)}/${s.budget.maxCostUsd}"
                    BudgetDimension.WallTimeMs -> "wallTimeMs=${s.wallTimeMs}/${s.budget.maxWallTimeMs}"
                    BudgetDimension.ExternalCalls -> "externalCalls=${s.externalCalls}/${s.budget.maxExternalCalls}"
                }
            }
            return "Task budget exceeded ($detail)"
        }
    }
}

/**
 * 四维任务预算熔断器：实时统计并约束单次任务的总 Token、美元费用、挂钟时长与外部调用次数。
 *
 * 支持作为协程上下文元素挂载到单次任务运行中，也可由循环和调度器直接持有并检查水位：
 * - 任一维度达到 80%（[TaskBudget.warnRatio]）返回 [BudgetWatermark.Warning]，建议降级使用 `light` 模型；
 * - 任一维度达到 100%（[TaskBudget.hardRatio]）返回 [BudgetWatermark.Exceeded]，并在 [checkOrThrow] 时抛出 [BudgetExceeded]。
 */
class BudgetBreaker(
    val budget: TaskBudget = TaskBudget.UNLIMITED,
    private val defaultPricing: BudgetPricing = BudgetPricing(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BudgetBreaker>

    private val lock = Any()
    private var startMs: Long = clock()
    private var inputTokens: Long = 0L
    private var outputTokens: Long = 0L
    private var cachedTokens: Long = 0L
    private var extraTokens: Long = 0L
    private var costUsd: Double = 0.0
    private var externalCalls: Int = 0

    /** 重置计数与起始挂钟时间。 */
    fun reset() {
        synchronized(lock) {
            startMs = clock()
            inputTokens = 0L
            outputTokens = 0L
            cachedTokens = 0L
            extraTokens = 0L
            costUsd = 0.0
            externalCalls = 0
        }
    }

    /** 记录一次模型调用（计入一次外部调用、Token 与费用），并返回最新水位。 */
    fun recordModelCall(
        usage: Usage?,
        pricing: BudgetPricing = defaultPricing,
        explicitCostUsd: Double? = null,
    ): BudgetWatermark {
        require(explicitCostUsd == null || explicitCostUsd >= 0.0) { "explicitCostUsd must be >= 0" }
        synchronized(lock) {
            externalCalls += 1
            if (usage != null) {
                inputTokens += usage.inputTokens.toLong().coerceAtLeast(0L)
                outputTokens += usage.outputTokens.toLong().coerceAtLeast(0L)
                cachedTokens += usage.cachedInputTokens.toLong().coerceAtLeast(0L)
                costUsd += explicitCostUsd ?: pricing.estimateCostUsd(usage)
            } else if (explicitCostUsd != null) {
                costUsd += explicitCostUsd
            }
            return evaluateLocked()
        }
    }

    /** 记录非模型外部调用（如外部工具、MCP、网络 API 调用）并返回最新水位。 */
    fun recordExternalCall(
        calls: Int = 1,
        costUsd: Double = 0.0,
        tokens: Long = 0L,
    ): BudgetWatermark {
        require(calls >= 0 && costUsd >= 0.0 && tokens >= 0L) { "usage deltas must be non-negative" }
        synchronized(lock) {
            externalCalls += calls
            this.costUsd += costUsd
            extraTokens += tokens
            return evaluateLocked()
        }
    }

    /** 检查当前四维水位（不抛异常）。 */
    fun check(): BudgetWatermark = synchronized(lock) { evaluateLocked() }

    /** 检查当前水位，若任一维度达到 100% 硬限制则抛出 [BudgetExceeded]。 */
    fun checkOrThrow(): BudgetWatermark {
        val watermark = check()
        if (watermark is BudgetWatermark.Exceeded) {
            throw BudgetExceeded(watermark.dimensions, watermark.snapshot)
        }
        return watermark
    }

    /** 是否已进入预警/降级水位（>= 80%），建议切换至 `light` 模型。 */
    fun shouldDowngradeToLight(): Boolean = check() !is BudgetWatermark.Normal

    /** 是否已触发 100% 硬熔断。 */
    fun isExceeded(): Boolean = check() is BudgetWatermark.Exceeded

    /** 获取当前消耗快照。 */
    fun snapshot(): BudgetSnapshot = synchronized(lock) { snapshotLocked() }

    /** 到达预警水位时生成简洁的降级提示文案；未达预警时为 null。 */
    fun warningNotice(): String? = when (val w = check()) {
        is BudgetWatermark.Warning -> {
            val pct = (w.maxRatio * 100).toInt()
            val dims = w.dimensions.joinToString(",") { it.key }
            "Task budget warning ($pct% on $dims): prefer concise steps or light model."
        }
        is BudgetWatermark.Exceeded -> BudgetExceeded.formatMessage(w.dimensions, w.snapshot)
        is BudgetWatermark.Normal -> null
    }

    private fun evaluateLocked(): BudgetWatermark {
        val snap = snapshotLocked()
        return when {
            snap.exceededDimensions.isNotEmpty() ->
                BudgetWatermark.Exceeded(snap, snap.exceededDimensions, snap.maxRatio)
            snap.warnDimensions.isNotEmpty() ->
                BudgetWatermark.Warning(snap, snap.warnDimensions, snap.maxRatio, suggestLightDowngrade = true)
            else -> BudgetWatermark.Normal(snap)
        }
    }

    private fun snapshotLocked(): BudgetSnapshot {
        val wallMs = (clock() - startMs).coerceAtLeast(0L)
        val totalTokens = inputTokens + outputTokens + extraTokens
        val ratios = linkedMapOf<BudgetDimension, Double>()
        budget.maxTokens?.let { ratios[BudgetDimension.Tokens] = totalTokens.toDouble() / it.toDouble() }
        budget.maxCostUsd?.let { ratios[BudgetDimension.CostUsd] = costUsd / it }
        budget.maxWallTimeMs?.let { ratios[BudgetDimension.WallTimeMs] = wallMs.toDouble() / it.toDouble() }
        budget.maxExternalCalls?.let { ratios[BudgetDimension.ExternalCalls] = externalCalls.toDouble() / it.toDouble() }

        val exceededDims = ratios.filterValues { it >= budget.hardRatio }.keys.toSet()
        val warnDims = ratios.filterValues { it >= budget.warnRatio }.keys.toSet()
        val maxRatio = ratios.values.maxOrNull() ?: 0.0

        return BudgetSnapshot(
            tokensUsed = totalTokens,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedTokens = cachedTokens,
            costUsd = costUsd,
            wallTimeMs = wallMs,
            externalCalls = externalCalls,
            budget = budget,
            ratios = ratios,
            maxRatio = maxRatio,
            warnDimensions = warnDims,
            exceededDimensions = exceededDims,
        )
    }
}

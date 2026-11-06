package com.wangzan101.mika.core.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.math.min

/**
 * 多通道模型池（同一服务商的多 Key，或两个主模型）。
 *
 * 三条策略都按《The Tail at Scale》(Dean & Barroso, CACM 2013) 和这两年的 LLM 网关实践来：
 *
 * 1. **亲和优先**：平时固定用第一个健康通道。前缀缓存是按账号的，同一条通道连着用才热
 *    （实测命中 2.4 万 token 时单步 3 秒，冷启动 34 秒）；轮询会把缓存打散。
 * 2. **按首字对冲（hedge）**：主通道超时没出**第一个字**，就并发发备用，谁先出字用谁，另一条取消。
 *    判定用首字而不是总时长——卡顿都发生在排队和 prefill，生成阶段是稳定的。
 *    对冲点**不是固定值**：取该通道最近首字耗时的 p90（论文的做法），冷启动用 [HedgePolicy.seedDelayMs]。
 * 3. **对冲预算**：令牌桶限制对冲比例（默认最多 20%），过载时不会雪上加霜；没预算就退回串行。
 *
 * 冷却也分级：限流 60 秒、配额 5 分钟、鉴权 30 分钟、5xx/网络 30 秒、输了对冲 10 秒。
 * 全部冷却时立即失败，把最早解封时间交给上层，不再打必然被拒的请求。
 */
class LoadBalancedChatModel(
    val models: List<ChatModel>,
    /** 限流后的冷却；其余错误见 [cooldownFor]。 */
    private val cooldownMs: Long = 60_000,
    private val hedge: HedgePolicy = HedgePolicy(),
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 观测:每次对冲的触发、赢家、耗时。默认丢掉;App 层接 logcat。 */
    private val onEvent: (String) -> Unit = {},
) : StreamingChatModel {

    /**
     * 对冲策略。
     * [minDelayMs]/[maxDelayMs] 夹住自适应算出来的对冲点；[seedDelayMs] 是样本不足时的起点。
     * [budget]/[refillPerCall] 是令牌桶:每次调用补 [refillPerCall] 个令牌、最多存 [budget] 个，
     * 一次对冲花 1 个 —— 长期对冲比例约等于 [refillPerCall]。
     */
    data class HedgePolicy(
        val enabled: Boolean = true,
        val minDelayMs: Long = 3_000,
        val maxDelayMs: Long = 30_000,
        val seedDelayMs: Long = 15_000,
        val budget: Double = 2.0,
        val refillPerCall: Double = 0.2,
        /** 算出对冲点至少要几个样本。 */
        val minSamples: Int = 5,
    ) {
        init {
            require(minDelayMs <= maxDelayMs) { "minDelayMs 不能大于 maxDelayMs" }
            require(budget >= 0 && refillPerCall >= 0) { "预算不能为负" }
        }
    }

    init {
        require(models.isNotEmpty()) { "LoadBalancedChatModel requires at least one model" }
    }

    /** 池的能力取**最小**窗口:任一通道超限都算失败，不能按最大的那个放行。 */
    override val capabilities: Capabilities
        get() = models.map { it.capabilities }.reduce { a, b ->
            a.copy(
                toolCalls = a.toolCalls && b.toolCalls,
                vision = a.vision && b.vision,
                contextTokens = minOf(a.contextTokens, b.contextTokens),
                promptCaching = if (a.promptCaching == b.promptCaching) a.promptCaching else PromptCaching.Implicit,
                // 触发点跟主通道走:备用通道更小是"切换时可能超限"的问题，
                // 不该反过来把主通道的上下文策略也拉窄(那会让每一步都多背几万 token)
                compactionTriggerTokens = a.compactionTriggerTokens ?: b.compactionTriggerTokens,
            )
        }

    override val name: String get() = "LoadBalanced(${models.joinToString { it.name }})"

    private val cooldownUntil = LongArray(models.size) { 0L }

    /** 每个通道最近的首字耗时(毫秒)，用来算 p90 对冲点。 */
    private val firstTokenSamples = Array(models.size) { ArrayDeque<Long>() }

    /** 对冲令牌桶。 */
    private var hedgeTokens = hedge.budget

    /** 观测计数:对冲次数、主/备各赢多少次。 */
    private var hedges = 0
    private var primaryWins = 0
    private var backupWins = 0

    private fun recordFirstToken(index: Int, ms: Long) {
        val q = firstTokenSamples[index]
        if (q.size >= SAMPLE_CAP) q.removeFirst()
        q.addLast(ms)
    }

    /** 对冲点:该通道首字耗时的 p90;样本不足时用种子值。 */
    internal fun hedgeDelayFor(index: Int): Long {
        val q = firstTokenSamples[index]
        val base = if (q.size >= hedge.minSamples) {
            val sorted = q.sorted()
            sorted[((sorted.size - 1) * 0.9).toInt()]
        } else {
            hedge.seedDelayMs
        }
        return base.coerceIn(hedge.minDelayMs, hedge.maxDelayMs)
    }

    /** 有预算才对冲: 过载时宁可串行，也别把请求翻倍。 */
    private fun takeHedgeBudget(): Boolean {
        if (!hedge.enabled || hedge.budget <= 0) return false
        if (hedgeTokens < 1.0) return false
        hedgeTokens -= 1.0
        return true
    }

    /** 当前该打哪个通道:第一个没在冷却的;全在冷却返回 -1。 */
    internal fun selectIndex(nowMs: Long = now()): Int {
        for (i in models.indices) if (nowMs >= cooldownUntil[i]) return i
        return -1
    }

    private fun nextHealthy(except: Int): Int {
        val nowMs = now()
        for (i in models.indices) if (i != except && nowMs >= cooldownUntil[i]) return i
        return -1
    }

    private fun earliestCooldownLeft(nowMs: Long): Long =
        (cooldownUntil.minOrNull() ?: nowMs) - nowMs

    /** 不同错误晾多久:输了对冲只晾 10 秒，限流才 60 秒。 */
    private fun cooldownFor(e: Throwable): Long? = when (e) {
        is ModelError.RateLimited -> (e.retryAfterMs ?: cooldownMs).coerceIn(1_000, 10 * 60_000)
        is ModelError.QuotaExceeded -> 5 * 60_000
        is ModelError.Auth -> 30 * 60_000
        is ModelError.Server -> if (e.status in 500..599) 30_000 else null
        is ModelError.Network, is ModelError.StreamCut -> 30_000
        else -> null
    }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        execute(system, messages, tools, stream = null)

    override suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): ModelReply = execute(system, messages, tools, stream = onText)

    /** 一次并发尝试:[first] 在第一个字到达时完成，[done] 在结束时(成功或失败)完成。 */
    private class Race(val index: Int, val job: Deferred<*>, val launchedAt: Long) {
        val first = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Result<ModelReply>>()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun execute(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        stream: ((String) -> Unit)?,
    ): ModelReply = coroutineScope {
        var lastError: Throwable? = null
        val attempted = HashSet<Int>()
        // 每次调用补一点对冲预算
        hedgeTokens = min(hedge.budget, hedgeTokens + hedge.refillPerCall)

        // 对冲时保证多个通道的流式文本不串扰交错：谁先吐字谁独占输出通道
        val streamMux = stream?.let { StreamMux(it) }

        fun start(index: Int): Race {
            lateinit var race: Race
            val launched = now()
            val channelStream = streamMux?.forChannel(index)
            val job = async {
                val r = runCatching {
                    attempt(index, system, messages, tools, channelStream) {
                        if (!race.first.isCompleted) {
                            race.first.complete(Unit)
                            // 首字耗时只用来算对冲点:流式才有这个信号
                            if (stream != null) recordFirstToken(index, now() - launched)
                        }
                    }
                }
                // 关键容错：工具调用（ToolCall）没有正文，但成功完成时同样提供了宝贵的响应时延样本
                if (r.isSuccess && !race.first.isCompleted) {
                    race.first.complete(Unit)
                    if (stream != null) recordFirstToken(index, now() - launched)
                }
                race.done.complete(r)
                Unit
            }
            race = Race(index, job, launched)
            return race
        }

        suspend fun settleRace(race: Race): ModelReply? {
            val result = race.done.await()
            val e = result.exceptionOrNull() ?: return result.getOrThrow()
            if (e is CancellationException) throw e
            val cool = cooldownFor(e) ?: throw e
            cooldownUntil[race.index] = now() + cool
            lastError = e
            attempted += race.index
            return null
        }

        fun recordHedgeOutcome(winner: Race, primary: Race, index: Int, backup: Int) {
            val spent = now() - primary.launchedAt
            if (winner.index == backup) {
                backupWins++
                // 重点：绝不能将输掉对冲的通道打入冷却！
                // 备用通道先返回仅代表本次对冲速度快，主通道并未发生故障（无 5xx/429/网络异常）。
                // 若将主通道放入 cooldown，会导致下一次调用强切备用通道，彻底击穿大模型的 Prompt Cache（前缀缓存），
                // 引发主备通道轮流冷启动 Prefill（30,000+ tokens 冷启动耗时 40~50 秒）的灾难性震荡。
                onEvent("hedge.backup_wins primary=$index backup=$backup 用时=${spent}ms")
            } else {
                primaryWins++
                onEvent("hedge.primary_wins primary=$index 用时=${spent}ms")
            }
        }

        suspend fun raceTwo(primary: Race, second: Race, index: Int, backup: Int): ModelReply? {
            val firstDone = select<Race> {
                primary.done.onAwait { primary }
                second.done.onAwait { second }
            }
            val other = if (firstDone === primary) second else primary
            val firstResult = firstDone.done.await()

            if (firstResult.isSuccess) {
                // 真正成功的通道才是赢家，立刻取消败者
                other.job.cancel()
                recordHedgeOutcome(firstDone, primary, index, backup)
                return firstResult.getOrThrow()
            }

            // 先结束的通道报错了（如 503 / 429）：记录其错误和冷却，但绝不掐死正在运行的另一个通道！
            settleRace(firstDone)

            val otherResult = other.done.await()
            if (otherResult.isSuccess) {
                recordHedgeOutcome(other, primary, index, backup)
                return otherResult.getOrThrow()
            }

            // 两个通道均失败
            settleRace(other)
            return null
        }

        while (true) {
            val index = selectIndex()
            if (index < 0) {
                throw lastError ?: ModelError.RateLimited(earliestCooldownLeft(now()).coerceAtLeast(1_000))
            }
            val backup = if (stream != null && takeHedgeBudget()) nextHealthy(index) else -1

            if (backup < 0) {
                val solo = start(index)
                settleRace(solo)?.let { return@coroutineScope it }
                if (attempted.size >= models.size) throw lastError!!
                continue
            }

            val delay = hedgeDelayFor(index)
            val primary = start(index)
            val early = select<Boolean> {
                primary.first.onAwait { true }
                primary.done.onAwait { false }
                onTimeout(delay) { false }
            }
            if (early) {
                settleRace(primary)?.let { return@coroutineScope it }
                if (attempted.size >= models.size) throw lastError!!
                continue
            }
            if (primary.done.isCompleted) {
                settleRace(primary)?.let { return@coroutineScope it }
                if (attempted.size >= models.size) throw lastError!!
                continue
            }

            // 到点还没出字:并发发备用
            hedges++
            val second = start(backup)
            onEvent("hedge.start primary=$index backup=$backup delay=${delay}ms p90=${firstTokenSamples[index].size}样本")

            val reply = raceTwo(primary, second, index, backup)
            if (reply != null) {
                return@coroutineScope reply
            }
            if (attempted.size >= models.size) throw lastError!!
        }
        @Suppress("UNREACHABLE_CODE") throw lastError ?: ModelError.BadResponse("unreachable")
    }

    /**
     * 打一次。[onFirst] 在第一次收到非空文本时回调(对流式就是对冲的"谁先出字")；
     * 非流式没有中间信号，只在结束时由 [Race.done] 体现。
     */
    private suspend fun attempt(
        index: Int,
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        stream: ((String) -> Unit)?,
        onFirst: () -> Unit,
    ): ModelReply {
        val model = models[index]
        return when {
            stream == null -> model.complete(system, messages, tools)
            model is StreamingChatModel -> model.completeStreaming(system, messages, tools) { t ->
                if (t.isNotEmpty()) onFirst()
                stream(t)
            }
            else -> model.complete(system, messages, tools).also { r ->
                r.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                    .takeIf(String::isNotEmpty)?.let(stream)
                onFirst()
            }
        }
    }

    /** 观测快照:对冲次数、主备谁赢、各通道样本数。 */
    data class Stats(val hedges: Int, val primaryWins: Int, val backupWins: Int, val samples: List<Int>)

    fun stats(): Stats = Stats(hedges, primaryWins, backupWins, firstTokenSamples.map { it.size })

    private companion object {
        /** 输了对冲的通道只是慢，晾 10 秒就够；别按限流那样晾一分钟。 */
        const val SLOW_COOLDOWN_MS = 10_000L
        const val SAMPLE_CAP = 32
    }
}

/** 对冲时防止两个通道并发向同一个流式回调输出文本造成乱序交错。首个吐字的通道独占流式管道。 */
private class StreamMux(private val downstream: (String) -> Unit) {
    @Volatile
    private var owner = -1

    fun forChannel(index: Int): (String) -> Unit = { text ->
        if (text.isNotEmpty()) {
            if (owner == -1) {
                synchronized(this) {
                    if (owner == -1) owner = index
                }
            }
            if (owner == index) {
                downstream(text)
            }
        }
    }
}

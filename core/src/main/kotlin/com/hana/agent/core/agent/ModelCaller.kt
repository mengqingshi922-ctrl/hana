package com.hana.agent.core.agent

import com.hana.agent.core.trace.RunTrace
import com.hana.agent.core.memory.MemoryFlushHook
import com.hana.agent.core.usage.BudgetBreaker
import com.hana.agent.core.vision.ImageBudget
import com.hana.agent.core.action.ConfirmDecision
import com.hana.agent.core.model.Author
import com.hana.agent.core.model.ChatModel
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.ModelError
import com.hana.agent.core.model.ModelReply
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import com.hana.agent.core.model.StopReason
import com.hana.agent.core.model.StreamingChatModel
import com.hana.agent.core.model.ToolSpec
import com.hana.agent.core.model.Usage
import com.hana.agent.core.record.Redactor
import com.hana.agent.core.record.RunEvent
import com.hana.agent.core.tool.ToolDispatcher
import com.hana.agent.core.tool.parseArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 一次模型调用:历史只追加,估算越过触发点才压缩一次([ContextCompressor],完整历史不动)、主模型一时的故障按 [retry] 重试、上下文超长时压得更狠再发一次,
 * lightSteps 打开时按 [router] 在大小模型之间分工(轻量模型出错不重试,这一步直接改问主模型)。每个任务开始时 [reset]。
 */
internal class ModelCaller(
    private val model: ChatModel,
    private val tools: ToolDispatcher,
    private val router: ModelRouter?,
    private val flags: SpeedFlags,
    private val retry: RetryPolicy,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
    private val autonomous: Boolean = false,
    private val trace: RunTrace? = null,
    private val edgePort: com.hana.agent.core.edge.EdgeDecisionPort = com.hana.agent.core.edge.EdgeDecisionPort.NONE,
    private val memoryFlushHook: MemoryFlushHook? = null,
    private val budgetBreaker: BudgetBreaker? = null,
) {
    /** 每个模型一份压缩状态:各自的上下文、各自发出的那份历史,轻量模型那次压缩不连累主模型的请求前缀。 */
    private val compressors = java.util.IdentityHashMap<ChatModel, ContextCompressor>()
    private fun compressorFor(m: ChatModel) =
        compressors.getOrPut(m) { ContextCompressor(tools.index, structured = flags.structuredCompaction,
            diffScreens = tools.inlineScreen, triggerOverride = m.capabilities.compactionTriggerTokens,
            memoryFlushHook = memoryFlushHook) }
    private val compactions = java.util.IdentityHashMap<ChatModel, CompactionState>()
    /** 本任务里已为重试等过的时间(对着 [RetryPolicy.taskWaitBudgetMs])。 */
    private var taskRetryWaitMs = 0L

    /** 本任务的系统提示词:开工时按发给模型的同一份工具列表拼([SystemPrompt.assemble]),任务内不变。 */
    var systemPrompt: String = ""

    /** 最近一次调用在成功那次请求之前花掉的时间(失败的请求、退避)。 */
    var lastRetryWaitMs = 0L
        private set

    /** 本任务里轻量模型给出、已进历史的回复;换模型时按它去掉别家模型的私有数据。 */
    private val lightReplies: MutableSet<Message> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    /** 带真实用量的回复(按消息身份)和给出它的模型,是量请求大小的锚点([UsageMeter]);压缩改写前缀后清空。 */
    private val anchors = java.util.IdentityHashMap<Message, Pair<ChatModel, UsageMeter.Anchor>>()
    /** 每个模型最近一次请求的固定开销估算;回复进历史时和用量一起记进锚点。 */
    private val lastOverhead = java.util.IdentityHashMap<ChatModel, Int>()

    fun reset(prompt: String) {
        systemPrompt = prompt
        compactions.clear()
        taskRetryWaitMs = 0
        compressors.values.forEach { it.forget() }
        lightReplies.clear()
        anchors.clear()
        lastOverhead.clear()
    }

    /** 回复进了历史:是轻量模型给的就记下;有真实 usage 的记成上下文大小的锚点。 */
    fun noteReply(m: Message, used: ChatModel, usage: Usage?) {
        if (router?.isLight(used) == true) lightReplies += m
        val overhead = lastOverhead[used]
        if (usage != null && overhead != null) anchors[m] = used to UsageMeter.Anchor(usage, overhead)
    }

    /** 发给 [using] 的整份请求有多大:最近一条它给的、带真实用量的回复作锚点,没有就全量估算(见 [UsageMeter])。 */
    private fun requestTokens(messages: List<Message>, using: ChatModel, overhead: Int, scale: Double): Int =
        UsageMeter.of(messages, overhead, scale) { m -> anchors[m]?.takeIf { it.first === using }?.second }

    /** 本任务里分工是否在起作用:开关打开、轻量模型能接手机步、没因不可恢复的错误停用。 */
    private val routing: Boolean get() = flags.lightSteps && router?.active == true

    /**
     * 检查最新一条工具回包是否包含空的控件树节点（无障碍盲区/树质量低下）。
     */
    private fun isLatestTreePoor(history: List<Message>): Boolean {
        val toolMsg = history.lastOrNull { it.role == Role.Tool } ?: return false
        val text = toolMsg.parts.joinToString("") { part ->
            when (part) {
                is Part.Text -> part.text
                is Part.ToolResult -> part.content
                else -> ""
            }
        }
        return text.contains("\"nodes\":[]") || text.contains("nodes\": []")
    }

    /** 开了分工就在运行记录里记每次调用的模型和角色(停用之后也记,便于对比)。 */
    val recordRoles: Boolean get() = flags.lightSteps && router != null

    fun roleOf(m: ChatModel) = if (router?.isLight(m) == true) ROLE_LIGHT else ROLE_MAIN

    /**
     * 按 [router] 选模型。轻量模型出错、没调工具(不带调用的回复是最终回答,只由主模型给),或要放弃、确认、接管、追问、做不可逆的事,
     * 它的回复不执行(作为 [ROLE_LIGHT_REJECTED] 记下用量),这一步改问主模型。
     * 返回回复、实际用的模型和起问时刻(切换为主模型时为新起问时刻)。
     */
    suspend fun routed(
        history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int,
        onText: ((String) -> Unit)? = null,
    ): RoutedOutcome {
        val startAsked = now()
        val userGoal = history.firstOrNull { it.author == Author.User }
            ?.parts?.filterIsInstance<Part.Text>()?.firstOrNull()?.text.orEmpty()

        // 【第一道防线：端侧 1ms 规则反射直通引擎】
        // 遇到日常阻断弹窗（允许/跳过广告/知道了/开心收下/残忍拒绝等），直接在端侧 1ms 生成物理点击，0 Token，0 网络延迟
        if (userGoal.isNotBlank()) {
            val reflex = EdgeFastReflex.inspect(history, userGoal, step, edgePort)
            if (reflex != null) {
                emit(RunEvent.State(now(), "edge_fast_reflex", reflex.reason))
                return RoutedOutcome(reflex.reply, model, startAsked)
            }
        }

        val budgetDowngrade = budgetBreaker?.shouldDowngradeToLight() == true
        val r = router?.takeIf { routing || budgetDowngrade } ?: return RoutedOutcome(ask(history, specs, step, onText = onText), model, startAsked)
        val latestHasImage = history.takeLast(2).any { m -> m.parts.any { it is Part.Image } }
        val ctx = track.context(step, latestHasImage)
        val picked = if (budgetDowngrade) {
            val bp = r.pickForScene(ModelScene.EdgeReflex, ctx).takeIf { r.isLight(it) } ?: r.pick(ctx)
            if (!r.isLight(bp)) return RoutedOutcome(ask(history, specs, step, onText = onText), model, startAsked)
            bp
        } else {
            val signal = ConfidenceSignal(
                stepConfidence = if (!ctx.lastOk || ctx.lastChanged == false || ctx.lastHadGuard) 0.40f else 0.85f,
                treeQualityPoor = isLatestTreePoor(history),
                ambiguityDetected = false,
                consecutiveLowConfidence = if (!ctx.lastOk || ctx.lastChanged == false) 1 else 0,
            )
            // 先执行基础路由启发式（首步主模型 / 切应用主模型 / 看图兼容 / forcedMain 计数递减），避免与 routeWithConfidence 重复叠加 forcedMain
            val policyPick = r.pick(ctx)
            if (!r.isLight(policyPick)) {
                val confidenceReason = when {
                    signal.treeQualityPoor -> "tree_quality_poor"
                    signal.ambiguityDetected -> "ambiguity_detected"
                    !ctx.lastOk || ctx.lastChanged == false -> "trouble_state"
                    else -> null
                }
                if (confidenceReason != null) {
                    emit(RunEvent.State(now(), "confidence_route_main", confidenceReason))
                }
                return RoutedOutcome(ask(history, specs, step, onText = onText), model, startAsked)
            }
            // 启发式允许下放时，再经 MAI-UI 置信度与树质量门控二次核验
            if (signal.treeQualityPoor || signal.ambiguityDetected || signal.stepConfidence < 0.65f || signal.consecutiveLowConfidence > 0) {
                val decision = r.routeWithConfidence(ctx, signal)
                if (decision.tier == RouteTier.CLOUD_MAIN) {
                    if (decision.reason != "no_lightweight_available") {
                        emit(RunEvent.State(now(), "confidence_route_main", decision.reason))
                    }
                    return RoutedOutcome(ask(history, specs, step, onText = onText), model, startAsked)
                }
            }
            policyPick
        }
        if (!r.isLight(picked)) return RoutedOutcome(ask(history, specs, step, onText = onText), model, startAsked)

        // 轻量模型的上下文可能更小:它报超长、它那次折叠多少,都不连累主模型(压缩状态按模型分开记)
        var lightErrorDetail: String? = null
        val light = try {
            complete(viewFor(history, picked), specs, picked, RetryPolicy.NONE, step, onText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lightErrorDetail = e.message ?: e.javaClass.simpleName
            // Key、额度、模型名、请求本身不对,重试也没用:本任务余下的步都用主模型
            if (e is ModelError.Auth || e is ModelError.QuotaExceeded || e is ModelError.ModelNotFound ||
                e is ModelError.ModelNotAvailable || e is ModelError.ToolsUnsupported ||
                e is ModelError.ChatGptLimitReached || e is ModelError.ChatGptNotEligible || e is ModelError.SignInExpired || e is ModelError.AccountPlanNotAllowed) r.disable()
            null
        }
        val text = light?.parts?.filterIsInstance<Part.Text>()?.joinToString("") { it.text }?.trim().orEmpty()
        var first = light?.parts?.filterIsInstance<Part.ToolCall>()?.firstOrNull()
        var effectiveLight = light

        if (first == null && text.isNotBlank()) {
            val rescued = extractToolCallFromText(text, specs)
            if (rescued != null) {
                first = rescued
                effectiveLight = ModelReply(
                    parts = listOf(Part.Text(text), rescued),
                    stopReason = StopReason.ToolUse,
                    usage = light?.usage
                )
            }
        }

        val chosenReply = effectiveLight ?: light
        if (first != null && chosenReply != null && !needsMain(first)) return RoutedOutcome(chosenReply, picked, startAsked)
        r.noteTrouble()
        emit(RunEvent.ModelReply(
            now(), step, RunEvent.clip(Redactor.text(text)), first?.name, first?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = true) },
            light?.usage?.inputTokens, light?.usage?.outputTokens, light?.usage?.cachedInputTokens, now() - startAsked,
            picked.name, ROLE_LIGHT_REJECTED,
        ))
        emit(RunEvent.State(now(), "light_to_main", first?.name ?: if (light == null) (lightErrorDetail ?: "light_error") else "no_tool"))
        onText?.invoke("")
        val mainAsked = now()
        return RoutedOutcome(ask(history, specs, step, onText = onText), model, mainAsked)
    }

    private fun needsMain(call: Part.ToolCall): Boolean =
        ModelRouter.mainOnly(call.name, tools.verb(call.name)) ||
            (!autonomous && ConfirmDecision.mayBeIrreversible(tools, call.name, parseArgs(call.argsJson)))

    /** 问 [using](默认主模型):发出的是给它看的那份历史([viewFor])压缩后的副本。 */
    suspend fun ask(history: List<Message>, specs: List<ToolSpec>, step: Int, using: ChatModel = model, onText: ((String) -> Unit)? = null): ModelReply =
        complete(viewFor(history, using), specs, using, retry, step, onText)

    /**
     * 发给 [to] 的历史:别的模型给出的回复去掉思考内容和调用上的私有数据(这些只能原样回给产生它的那个模型)。
     * 只看字段在不在,不分服务商。发给轻量小脑模型时实施敏捷微沙箱脱水(sanitizeForLight)，斩除旧屏死重与数万 Token 布局树，
     * 若小脑支持视觉能力，则保留当前最新屏幕截图，赋予小脑秒级识别弹窗/倒计时的视觉决策力。
     */
    private fun viewFor(history: List<Message>, to: ChatModel): List<Message> {
        val toLight = router?.isLight(to) == true
        val cleaned = if (!toLight && lightReplies.isEmpty()) history else {
            history.map { m ->
                val foreign = m.role == Role.Assistant && (m in lightReplies) != toLight
                if (!foreign || m.parts.none { it is Part.Thinking || (it is Part.ToolCall && it.opaque != null) }) m
                else m.copy(parts = m.parts.mapNotNull { p ->
                    when {
                        p is Part.Thinking -> null
                        p is Part.ToolCall && p.opaque != null -> p.copy(opaque = null)
                        else -> p
                    }
                })
            }
        }
        return cleaned
    }

    /** 上次记过的压缩触发点:只在变化或真压缩时打日志。 */
    private var lastTriggerLogged = -1

    private suspend fun complete(history: List<Message>, specs: List<ToolSpec>, using: ChatModel, policy: RetryPolicy, step: Int,
        onText: ((String) -> Unit)? = null, customPrompt: String? = null): ModelReply {
        var retries = 0
        lastRetryWaitMs = 0
        val began = now()
        val effectivePrompt = customPrompt ?: systemPrompt
        val overhead = TokenEstimate.of(effectivePrompt) + specs.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        val contextTokens = using.capabilities.contextTokens
        val compressor = compressorFor(using)
        var compaction = compactions[using] ?: CompactionState()
        var overflowRetried = false
        var overflowRewrote = false
        while (true) {
            val current = ContextRecovery.ensure(history, compressor.current(history, contextTokens))
            val currentTokens = requestTokens(current, using, overhead, compressor.scale)
            val raw = compressor.compress(history, contextTokens, currentTokens, compaction.aggressive, overhead, using.capabilities.maxImages)
            if (raw.rewritten) anchors.clear()
            val restored = ContextRecovery.ensure(history, raw.messages)
            val sent = if (restored === raw.messages) raw else raw.copy(messages = restored, estimate = TokenEstimate.of(restored))
            val (next, report) = CompactionPolicy.sent(compaction, sent.foldedSteps)
            if (report) emit(RunEvent.Compressed(now(), sent.foldedSteps))
            // 只在真的压了时记一条:不改变运行事件的既有形状，也能看到当时的触发点
            if (raw.rewritten) {
                lastTriggerLogged = raw.trigger
                emit(RunEvent.State(now(), "ctx", "size=${raw.size} trigger=${raw.trigger} est=${raw.estimate} rewritten=${raw.rewritten}"))
            }
            compaction = next
            compactions[using] = compaction
            // 移动端操作低延迟守护（借鉴 AutoGLM / Mobile-Agent-v2 最佳实践）：
            // 决策当前步骤只需当前最新的一屏控件与全局 Goal，历史屏幕只需保留“动作与结局”，旧屏幕无须携带成百上千个死重控件。
            // 使用 HistoryPruner.LAST（Cut(1)）保留当前最新屏幕的全量控件树，脱水更早之前的历史屏幕，
            // 确保 Prompt 稳定在数千 tokens，消除 3.8 万 Token 带来的 40 秒 Prefill 与网关 503 排队爆满。
            val prunedMessages = if (flags.structuredCompaction) {
                val toolRounds = sent.messages.count { m -> m.role == Role.Assistant && m.parts.any { it is Part.ToolCall } }
                HistoryPruner.prune(
                    sent.messages,
                    tools.index,
                    HistoryPruner.steppedCut(toolRounds, stride = 3, minWindow = 1),
                    prePruneHook = memoryFlushHook,
                )
            } else sent.messages
            val imageLimits = ImageBudget.Limits(
                maxTotalBytes = ImageBudget.DEFAULT_MAX_BYTES,
                maxImages = if (flags.structuredCompaction) ImageBudget.DEFAULT_MAX_IMAGES else Int.MAX_VALUE,
            )
            val budgetResult = ImageBudget.enforce(prunedMessages, imageLimits)
            if (budgetResult.foldedCount > 0) {
                emit(RunEvent.State(now(), "image_budget_folded", "folded=${budgetResult.foldedCount} bytes=${budgetResult.foldedBytes}"))
            }
            val optimizedMessages = budgetResult.messages
            budgetBreaker?.checkOrThrow()
            try {
                // 差异的 base 被裁掉或折叠时,下一次写操作要发全量
                if (tools.inlineScreen) tools.setVisibleObservations(visibleReads(optimizedMessages))
                // 这一次之前花掉的(失败的请求、退避)都算重试等待
                lastRetryWaitMs = now() - began
                suspend fun send(): ModelReply = if (onText == null) using.complete(effectivePrompt, optimizedMessages, specs)
                    else if (using is StreamingChatModel) using.completeStreaming(effectivePrompt, optimizedMessages, specs, onText)
                    else using.complete(effectivePrompt, optimizedMessages, specs).also { result ->
                        result.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                            .takeIf(String::isNotEmpty)?.let(onText)
                    }
                val reply = if (trace == null) send() else {
                    val change = when {
                        overflowRewrote -> "overflow_compaction folded=" + raw.foldedSteps
                        raw.rewritten && current != sent.messages -> "history_rewritten folded=" + raw.foldedSteps
                        else -> null
                    }
                    overflowRewrote = false
                    trace.modelCall(step, using, tools::verb, change, optimizedMessages) { send() }
                }
                return reply.also { r ->
                    lastOverhead[using] = overhead
                    r.usage?.let { compressor.calibrate(sent.estimate + overhead, it.inputTokens) }
                }
            } catch (e: ModelError.ContextTooLong) {
                onText?.invoke("")
                val (after, again) = CompactionPolicy.overflow(compaction, overflowRetried) {
                    compressor.overflow(history, contextTokens, overhead)
                }
                compaction = after
                compactions[using] = compaction
                overflowRetried = true
                if (again) { anchors.clear(); overflowRewrote = true }
                if (!again) throw e
            } catch (e: ModelError) {
                onText?.invoke("")
                val wait = policy.delayFor(e, retries, taskRetryWaitMs) ?: throw e
                retries += 1
                taskRetryWaitMs += wait
                emit(RunEvent.State(now(), "model_retry", "${roleOf(using)} ${RetryPolicy.label(e)} #$retries ${wait}ms"))
                // 等待中取消任务就此结束
                delay(wait)
            }
        }
    }

    companion object {
        /** [RunEvent.ModelReply.role] 的取值;分工没起作用时为 null。 */
        const val ROLE_MAIN = "main"
        const val ROLE_LIGHT = "light"
        /** 轻量模型的回复没执行,这一步改问了主模型;仍是一次模型调用,用量照记。 */
        const val ROLE_LIGHT_REJECTED = "light_rejected"
        /** 敏捷小脑高频核心动作工具集。包含点击、输入、滑动、返回、等待、观察等日常操作，决策迅速且完全闭环 */
        val LIGHT_ACTION_TOOLS = setOf(
            "phone_tap", "phone_type_text", "phone_type", "phone_swipe", "phone_back", "phone_wait", "phone_observe", "phone_hotkey"
        )

        /**
         * 自动从自然语言/漏出来的模型文本中提取结构化工具调用，兜底拯救小模型的纯文本动作输出。
         */
        internal fun extractToolCallFromText(
            text: String,
            allowedTools: List<ToolSpec>
        ): Part.ToolCall? {
            if (text.isBlank()) return null
            val allowedNames = allowedTools.map { it.name }.toSet()

            // 1. 尝试匹配 XML 风格的 <tool_call> 标签
            val toolCallTagRegex = Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
            toolCallTagRegex.find(text)?.groupValues?.get(1)?.let { rawJson ->
                tryParseToolJson(rawJson, allowedNames)?.let { return it }
            }

            // 2. 尝试匹配伪代码函数调用：例如 phone_tap(ref="@e1") 或 phone_wait(timeoutMs=3000)
            val funcCallRegex = Regex("""(phone_\w+)\s*\((.*?)\)""")
            funcCallRegex.find(text)?.let { match ->
                val funcName = match.groupValues[1]
                val argsContent = match.groupValues[2].trim()
                if (funcName in allowedNames) {
                    val parsedArgs = parseSimpleArgs(argsContent)
                    return Part.ToolCall(
                        id = "rescued_${System.currentTimeMillis()}",
                        name = funcName,
                        argsJson = parsedArgs.toString()
                    )
                }
            }

            // 3. 尝试匹配整段或局部 JSON 对象
            val jsonBlockRegex = Regex("""(\{\s*"name"\s*:\s*".*?"\s*,\s*"(?:arguments|parameters)"\s*:\s*\{.*?\}\s*\})""", RegexOption.DOT_MATCHES_ALL)
            jsonBlockRegex.find(text)?.groupValues?.get(1)?.let { rawJson ->
                tryParseToolJson(rawJson, allowedNames)?.let { return it }
            }

            // 4. 尝试匹配引用元素：例如 "@e1"
            val refRegex = Regex("""(?:phone_tap\s+|点击\s+|tap\s+)?(@e\d+)""")
            refRegex.find(text)?.let { match ->
                val ref = match.groupValues[1]
                if ("phone_tap" in allowedNames) {
                    return Part.ToolCall(
                        id = "rescued_${System.currentTimeMillis()}",
                        name = "phone_tap",
                        argsJson = buildJsonObject {
                            put("ref", ref)
                        }.toString()
                    )
                }
            }

            // 4.1 尝试匹配 UI-TARS / 视觉动作坐标：例如 click(832, 399)、point=[832, 399] 或 [832, 399]
            val coordRegex = Regex("""(?:click\s*\(?|point\s*=\s*\[?|tap\s*\(?|\[)?\s*(\d{2,4})\s*,\s*(\d{2,4})\s*[\)\]]?""", RegexOption.IGNORE_CASE)
            coordRegex.find(text)?.let { match ->
                val x = match.groupValues[1].toIntOrNull()
                val y = match.groupValues[2].toIntOrNull()
                if (x != null && y != null && "phone_tap" in allowedNames) {
                    return Part.ToolCall(
                        id = "rescued_${System.currentTimeMillis()}",
                        name = "phone_tap",
                        argsJson = buildJsonObject {
                            put("x", x)
                            put("y", y)
                        }.toString()
                    )
                }
            }

            // 5. 尝试匹配返回动作（注意：轻量小模型输出 wait 表示弃权无法匹配，严禁自动转为 phone_wait，交由主模型接管）
            if (text.contains("back", ignoreCase = true) && "phone_back" in allowedNames) {
                return Part.ToolCall(
                    id = "rescued_${System.currentTimeMillis()}",
                    name = "phone_back",
                    argsJson = buildJsonObject { }.toString()
                )
            }

            return null
        }

        private fun tryParseToolJson(raw: String, allowedNames: Set<String>): Part.ToolCall? {
            return try {
                val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return null
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return null
                if (name !in allowedNames) return null
                val args = obj["arguments"] ?: obj["parameters"] ?: JsonObject(emptyMap())
                Part.ToolCall(
                    id = "rescued_${System.currentTimeMillis()}",
                    name = name,
                    argsJson = args.toString()
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun parseSimpleArgs(argsStr: String): JsonObject {
            val map = mutableMapOf<String, JsonElement>()
            val pairs = argsStr.split(",")
            for (p in pairs) {
                val kv = p.split("=", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim().removeSurrounding("\"").removeSurrounding("'")
                    val v = kv[1].trim().removeSurrounding("\"").removeSurrounding("'")
                    val intVal = v.toIntOrNull()
                    val boolVal = v.toBooleanStrictOrNull()
                    when {
                        intVal != null -> map[k] = JsonPrimitive(intVal)
                        boolVal != null -> map[k] = JsonPrimitive(boolVal)
                        else -> map[k] = JsonPrimitive(v)
                    }
                }
            }
            return JsonObject(map)
        }
    }
}

data class RoutedOutcome(val reply: ModelReply, val used: ChatModel, val askedAtMs: Long)

/** 发出的历史里还完整可见的读取(按编号):带元素列表的读取结果与带 screen 对象的动作结果。 */
internal fun visibleReads(messages: List<Message>): Set<String> {
    val out = HashSet<String>()
    for (m in messages) for (p in m.parts) {
        if (p !is Part.ToolResult) continue
        val obj = try { Json.parseToJsonElement(p.content) as? JsonObject } catch (e: SerializationException) { null } ?: continue
        val shown = (obj["screen"] as? JsonObject) ?: obj.takeIf { it["elements"] is JsonArray } ?: continue
        (shown["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let(out::add)
    }
    return out
}

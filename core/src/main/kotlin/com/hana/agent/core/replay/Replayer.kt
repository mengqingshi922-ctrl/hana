package com.hana.agent.core.replay

import com.hana.agent.core.action.ActionExecutor
import com.hana.agent.core.action.ActionOutcome
import com.hana.agent.core.action.Origin
import com.hana.agent.core.model.Part
import com.hana.agent.core.record.Redactor
import com.hana.agent.core.record.RunEvent
import com.hana.agent.core.record.RunSink
import com.hana.agent.core.uistate.ElementLocator
import com.hana.agent.core.uistate.RefStabilizer
import com.hana.agent.core.uistate.Relocation
import com.hana.agent.core.uistate.Resolved
import com.hana.agent.core.uistate.ScreenSnapshot
import com.hana.agent.core.uistate.UiElement
import com.hana.agent.core.uistate.interfaces
import com.hana.agent.core.uistate.normalize
import com.hana.agent.core.uistate.relocate
import com.hana.agent.core.tool.ToolDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.hana.agent.core.tool.Verb

sealed interface ReplayOutcome {
    /** 走完(或核对通过)的步数。 */
    val done: Int

    data class Completed(override val done: Int) : ReplayOutcome
    /**
     * [executed]:第 done+1 步真的执行了(界面可能已经动过);执行前就被拒的(参数、链接、编号过期、App 被占等)为 false。
     * [error] 是那一步工具结果的错误码,交还时一并告诉模型。
     */
    data class Diverged(
        override val done: Int, val expected: String, val seen: String, val executed: Boolean = false, val error: String? = null,
    ) : ReplayOutcome
    /** 下一步是不可逆操作(或判不清),停在它之前交还模型走正常确认流程。 */
    data class StoppedForConfirmation(override val done: Int) : ReplayOutcome
}

/**
 * 不调模型地照路线一步步做。每步:锁屏关卡 → 读屏 → 轻量核对(前台包名、动作前锚点、选择器唯一命中)→
 * 经 [ActionExecutor] 执行(守卫一有提示就停;不可逆或判不清都停,回放从不认为用户已同意)→ 核对动作后的变化。
 * 任何一处对不上就停,交还模型。输入的字只来自路线里的固定文字和本次原话里的槽值。
 */
class Replayer(
    private val tools: ToolDispatcher,
    private val executor: ActionExecutor,
    private val gate: suspend () -> Unit,
    private val sink: RunSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val edgePort: com.hana.agent.core.edge.EdgeDecisionPort = com.hana.agent.core.edge.EdgeDecisionPort.NONE,
    private val routeStore: RouteStore? = null,
) {
    private var seq = 0
    private val stabilizer = RefStabilizer("rh")

    /** 本次回放中发生自愈或锚点权重热更新后的最新路线副本（可供外部检查或落盘）。 */
    var lastHealedRoute: Route? = null
        private set

    /** 本次回放中单步原地自愈成功的次数。 */
    var healedStepCount: Int = 0
        private set

    private data class TargetResolution(
        val element: UiElement,
        val healed: Boolean,
        val reason: String? = null,
    )

    suspend fun run(choice: RouteChoice): ReplayOutcome {
        val steps = choice.route.steps
        val total = steps.size
        val workingSteps = steps.toMutableList()
        var routeModified = false
        var prevSnap: ScreenSnapshot? = null
        healedStepCount = 0
        lastHealedRoute = null

        fun persistRouteIfModified() {
            if (!routeModified) return
            val updated = choice.route.copy(steps = workingSteps.toList(), lastUsedMs = clock())
            lastHealedRoute = updated
            routeStore?.put(updated)
        }

        fun event(i: Int, matched: Boolean, detail: String) =
            emit(RunEvent.Replay(clock(), choice.route.id, i + 1, total, matched, Redactor.text(detail)))

        for ((i, step) in steps.withIndex()) {
            fun diverged(why: String, seen: String, executed: Boolean = false, error: String? = null): ReplayOutcome {
                persistRouteIfModified()
                event(i, false, why)
                return ReplayOutcome.Diverged(i, describe(step, choice.slots) + " ($why)", seen, executed, error)
            }
            val verb = tools.index[step.tool]?.takeIf { it.routable }?.verb ?: return diverged("not a replayable action", "")
            // 锁屏、被拿走时先等;等完再读屏,核对和执行都按等完之后的一屏(执行入口不再等第二次)
            gate()
            val snap = read() ?: return diverged("the screen could not be read", "")
            if (snap.protected) return diverged("the page is protected", PROTECTED_SEEN)
            val modelSnap = stabilizer.next(snap)
            val seen = summary(snap)
            step.before.packageName?.let { if (snap.packageName != it) return diverged("the foreground app is not $it", seen) }
            val names = snap.elements.map { it.name.trim() }.toSet()
            if (!step.before.matchesWeighted(names)) return diverged("the page before the action does not match", seen)

            val args = build(step, choice.slots) ?: return diverged("the slots in the arguments cannot be filled", seen)
            val resolution = step.target?.let { sel ->
                findOrHeal(snap, prevSnap, modelSnap.snapshotId, sel, choice.slots)
                    ?: return diverged("the target element is not unique or not found", seen)
            }
            val target = resolution?.element
            val healed = resolution?.healed == true
            if (verb == Verb.Tap && target == null) return diverged("the tap has no target to check", seen)
            // 链接只开参考资料里此刻还在的:资料改过,路线里记着的链接就不再放行
            if (verb == Verb.OpenLink) {
                fun arg(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
                if (!tools.linkAllowed(arg("action"), arg("uri"), arg("package"))) return diverged("the link is no longer in the reference material", seen)
            }
            val call = target?.let { JsonObject(args + mapOf("ref" to JsonPrimitive(it.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))) } ?: args

            val id = "replay-${++seq}"
            val out = when (val o = executor.run(Part.ToolCall(id, step.tool, call.toString()), Origin.Replay)) {
                is ActionOutcome.Ran -> o
                is ActionOutcome.Refused -> when (o.code) {
                    "guard_stopped", "guard_warning" -> return diverged("the action guard raised a warning", seen)
                    "confirmation_required", "confirmation_mismatch" -> {
                        persistRouteIfModified()
                        event(i, false, "irreversible action, handed back to the model for confirmation")
                        return ReplayOutcome.StoppedForConfirmation(i)
                    }
                    "target_unresolved" -> return diverged("cannot tell which element the tap would hit", seen)
                    "own_app" -> return diverged("it acts on Hana itself", seen)
                    else -> error("unexpected refusal ${o.code}")
                }
                is ActionOutcome.Cancelled -> return diverged("cancelled", seen)
            }
            val exec = out.exec
            // 动作后只读一屏:路线锚点和「有没有出现预期文字」都用它
            val fresh = out.seen ?: tools.interfaces.peek()
            val effective = effectiveWrite(verb, exec.content)
            val now = fresh?.let { if (it.protected) PROTECTED_SEEN else summary(it) } ?: seen
            if (exec.outcome != null || exec.content["ok"] != JsonPrimitive(true)) {
                val error = (exec.content["error"] as? JsonPrimitive)?.contentOrNull
                return diverged("the action failed" + error?.let { ": $it" }.orEmpty(), now, executed = exec.outcome != null || error !in NOT_EXECUTED, error = error)
            }
            val shown = fresh?.elements?.map { it.name.trim() }?.toSet().orEmpty()
            val landed = exec.content["changed"] == JsonPrimitive(true) || exec.content["verification"] == JsonPrimitive("match") ||
                (effective && verb in OPENS) ||
                step.after.texts.any { it in shown }
            if (!landed) return diverged("no expected change after the action", now, executed = true)

            // 成功后热更新当前边锚点权重，并在自愈命中时原地更新目标选择器特征
            val updatedBefore = step.before.hotUpdated(names, RouteAnchors.texts(snap).filter { it != target?.name?.trim() })
            val updatedAfter = fresh?.let {
                step.after.hotUpdated(shown, RouteAnchors.texts(it))
            } ?: step.after
            var updatedTarget = step.target
            if (healed && target != null && step.target != null) {
                healedStepCount++
                updatedTarget = if (SLOT.containsMatchIn(step.target.name)) {
                    step.target.copy(role = target.role, resourceId = target.resourceId, bounds = target.bounds)
                } else {
                    RouteAnchors.selector(snap, target)
                }
            }
            if (updatedBefore != step.before || updatedAfter != step.after || updatedTarget != step.target) {
                workingSteps[i] = step.copy(target = updatedTarget, before = updatedBefore, after = updatedAfter)
                routeModified = true
            }

            prevSnap = fresh ?: snap
            val detail = if (healed) "${describe(step, choice.slots)} [healed:${resolution?.reason}]" else describe(step, choice.slots)
            event(i, true, detail)
        }
        persistRouteIfModified()
        return ReplayOutcome.Completed(total)
    }

    private suspend fun read(): ScreenSnapshot? = try {
        // 读路线此刻所在的那块屏(最近一步动作的那块)
        val dump = tools.index.nameOf(Verb.ListElements, tools.surface)!!
        val r = tools.executeRead(Part.ToolCall("replay-read-${++seq}", dump, "{}"), 0)
        (r.content["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let { tools.interfaces.snapshotFor(tools.declared(dump), JsonObject(mapOf("snapshotId" to JsonPrimitive(it)))) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 去掉编号;每个字符串参数里的 `{n}` 都换成槽值。槽号超出范围、或非字符串参数里带着槽,为 null。 */
    private fun build(step: RouteStep, slots: List<String>): JsonObject? {
        val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null } ?: return null
        val out = mutableMapOf<String, JsonElement>()
        for ((k, v) in o) {
            if (k == "ref" || k == "snapshotId" || k == "target") continue
            out[k] = when {
                v is JsonPrimitive && v.isString -> JsonPrimitive(fill(v.content, slots) ?: return null)
                SLOT.containsMatchIn(v.toString()) -> return null
                else -> v
            }
        }
        return JsonObject(out)
    }

    /**
     * 工业级柔性多级选择器 + 单步原地自愈 (Fuzzy Multi-Tier Selector & In-Place Healing):
     * Tier 1: 严格精确匹配 (role + resourceId + name)
     * Tier 2: 动态数字剥离匹配 (应对 "购物车(2)" 匹配记录时的 "购物车")
     * Tier 3: 语义与空间就近匹配 (当 resourceId 发生热更新/混淆变化时，基于 role + name + 屏宽 15% 坐标距离命中)
     * Tier 3.5: 单步原地自愈 (基于 RefStabilizer + relocate 上下文定位 + 文本相似度 + 几何位置 IoU/距离 局部重定位)
     * Tier 4: 端侧 MNN-Pocket 指针语义对齐 (UI 改版文案微调如“结算”变“去买单”)
     */
    private suspend fun findOrHeal(
        s: ScreenSnapshot,
        prevSnap: ScreenSnapshot?,
        stabilizerSnapshotId: String,
        sel: Selector,
        slots: List<String>,
    ): TargetResolution? {
        val name = fill(sel.name, slots) ?: return null
        fun near(e: UiElement) = e.bounds.size == 4 && hypot(cx(e.bounds) - cx(sel.bounds), cy(e.bounds) - cy(sel.bounds)) <= NEAR

        // Tier 1: 严格精确匹配
        val sameExact = s.elements.filter { it.role == sel.role && it.resourceId == sel.resourceId && (name.isEmpty() || it.name.trim() == name) }
        if (sameExact.size == 1) return TargetResolution(sameExact.single(), healed = false)
        if (sameExact.isNotEmpty()) {
            if (!sel.uniqueAtRecord && sel.bounds.size == 4) {
                val pick = sameExact.getOrNull(sel.ordinal)?.takeIf(::near)
                if (pick != null && sameExact.count(::near) == 1) return TargetResolution(pick, healed = false)
            }
            // 记录时要求唯一，但当前屏上出现多个完全相同元素（同位置附近多个同名同ID控件）：严禁猜测，交还模型
            if (sel.uniqueAtRecord) return null
        }

        // Tier 2: 动态文本容错匹配 (剥离末尾动态数字角标如 "(3)", " 99+", "·")
        if (name.isNotEmpty()) {
            val baseName = stripDynamicBadge(name)
            val sameFuzzyText = s.elements.filter { it.role == sel.role && it.resourceId == sel.resourceId && stripDynamicBadge(it.name.trim()) == baseName }
            if (sameFuzzyText.size == 1) return TargetResolution(sameFuzzyText.single(), healed = false)
            if (sameFuzzyText.isNotEmpty() && sel.bounds.size == 4) {
                val pick = sameFuzzyText.filter(::near)
                if (pick.size == 1) return TargetResolution(pick.single(), healed = false)
            }
        }

        // Tier 3: 空间几何与语义容错 (App 版本更新导致混淆 resourceId 发生变动，但文案和角色一致且处于同一位置区域)
        if (name.isNotEmpty() && sel.bounds.size == 4) {
            val sameRoleAndName = s.elements.filter { it.role == sel.role && (it.name.trim() == name || stripDynamicBadge(it.name.trim()) == stripDynamicBadge(name)) }
            val nearCandidates = sameRoleAndName.filter(::near)
            if (nearCandidates.size == 1) {
                return TargetResolution(nearCandidates.single(), healed = false)
            }
        }

        // Tier 3.5: 单步原地自愈 (RefStabilizer + 文本与几何位置相似度局部重定位)
        healInPlace(s, prevSnap, stabilizerSnapshotId, sel, name)?.let { return it }

        // Tier 4: 端侧 MNN-Pocket 指针语义对齐 (UI 改版文案微调如“结算”变“去买单”)
        if (edgePort.isReady && name.isNotEmpty()) {
            val hasRecordedBounds = sel.bounds.size == 4
            val eligible = s.elements.filter { it.clickable || it.name.trim().isNotEmpty() }
            val sorted = if (hasRecordedBounds) {
                eligible.sortedBy { elem ->
                    if (elem.bounds.size == 4) hypot(cx(elem.bounds) - cx(sel.bounds), cy(elem.bounds) - cy(sel.bounds))
                    else Double.MAX_VALUE
                }
            } else eligible

            val candidates = sorted
                .take(8)
                .map {
                    com.hana.agent.core.edge.EdgeDecisionPort.EdgeCandidate(
                        ref = it.ref,
                        role = it.role,
                        label = it.name.trim(),
                        bounds = it.bounds.toIntArray(),
                    )
                }
            if (candidates.isNotEmpty()) {
                val matchedRef = edgePort.matchReplayCandidate(
                    stepGoal = sel.role,
                    recordedLabel = name,
                    candidates = candidates,
                    confidenceThreshold = 0.88f,
                )
                if (matchedRef != null) {
                    val candidateMatch = s.elements.firstOrNull { it.ref == matchedRef }
                    if (candidateMatch != null) return TargetResolution(candidateMatch, healed = true, reason = "edge_semantic")
                }
            }
        }

        return null
    }

    /**
     * 基于 RefStabilizer 上下文追踪 + 文本相似度 + 几何位置 IoU/中心距 的局部重定位：
     * 应对布局轻度位移（超出 15% 屏宽）、控件角色重构（如 button -> text/view）、
     * 文案微调（如 "立即购买" -> "立即购买(包邮)"）或 resourceId 混淆改名。
     */
    private fun healInPlace(
        s: ScreenSnapshot,
        prevSnap: ScreenSnapshot?,
        stabilizerSnapshotId: String,
        sel: Selector,
        targetName: String,
    ): TargetResolution? {
        if (s.elements.isEmpty()) return null
        val shortSide = max(1, min(s.width, s.height))
        val curLocators = buildLocators(s)
        val relocatedIdx = if (prevSnap != null && prevSnap.packageName == s.packageName) {
            val prevLocators = buildLocators(prevSnap)
            val prevMatchIdx = prevSnap.elements.indexOfFirst {
                it.role == sel.role && it.resourceId == sel.resourceId && (targetName.isEmpty() || it.name.trim() == targetName)
            }
            if (prevMatchIdx >= 0) {
                when (val rel = relocate(prevLocators[prevMatchIdx], prevLocators, curLocators, shortSide)) {
                    is Relocation.Found -> rel.index
                    else -> null
                }
            } else null
        } else null

        val targetBase = stripDynamicBadge(targetName)
        val modelSnap = stabilizer.latest
        val stabilizedRefs = if (modelSnap != null && modelSnap.snapshotId == stabilizerSnapshotId) {
            modelSnap.toPort.entries.associate { (modelRef, portRef) ->
                val resolved = stabilizer.resolve(modelRef, stabilizerSnapshotId)
                portRef to (resolved is Resolved.Current)
            }
        } else emptyMap()

        data class Scored(val idx: Int, val element: UiElement, val score: Double, val minScore: Double)

        val candidates = s.elements.mapIndexedNotNull { idx, e ->
            // 避免把纯不可交互且非同类的静态文字误当按钮点
            val roleCompatible = e.role == sel.role ||
                (sel.role in CLICKABLE_ROLES && (e.clickable || e.role in CLICKABLE_ROLES)) ||
                (sel.role in EDITABLE_ROLES && e.editable)
            if (!roleCompatible && e.resourceId != sel.resourceId) return@mapIndexedNotNull null

            val candRaw = e.name.trim().ifEmpty { e.placeholder?.trim().orEmpty() }
            val candBase = stripDynamicBadge(candRaw)
            val txtSim = textSimilarity(targetBase, candBase)
            val geoSim = geometricSimilarity(sel.bounds, e.bounds)
            val ridSim = resourceIdSimilarity(sel.resourceId, e.resourceId)
            val roleSim = when {
                e.role == sel.role -> 1.0
                roleCompatible -> 0.55
                else -> 0.0
            }
            val stabBonus = when {
                relocatedIdx == idx -> 0.15
                stabilizedRefs[e.ref] == true && (txtSim >= 0.5 || ridSim >= 0.7) -> 0.08
                else -> 0.0
            }

            if (targetBase.isNotEmpty()) {
                if (txtSim < 0.40 && ridSim < 0.70) return@mapIndexedNotNull null
                val score = 0.45 * txtSim + 0.30 * geoSim + 0.15 * ridSim + 0.10 * roleSim + stabBonus
                Scored(idx, e, score, HEAL_MIN_SCORE_NAMED)
            } else {
                // 无文本控件（如图标按钮、无 hint 输入框）必须有强 resourceId 或高几何重合+同角色
                if (ridSim < 0.70 && !(e.role == sel.role && geoSim >= 0.75)) return@mapIndexedNotNull null
                val score = 0.45 * ridSim + 0.40 * geoSim + 0.15 * roleSim + stabBonus
                Scored(idx, e, score, HEAL_MIN_SCORE_UNNAMED)
            }
        }.sortedByDescending { it.score }

        val best = candidates.firstOrNull()?.takeIf { it.score >= it.minScore } ?: return null
        val second = candidates.getOrNull(1)
        if (second != null && best.score - second.score < HEAL_MIN_MARGIN) {
            return null
        }
        return TargetResolution(
            element = best.element,
            healed = true,
            reason = "ref_geo_text(${"%.2f".format(best.score)})",
        )
    }

    private fun buildLocators(s: ScreenSnapshot): List<ElementLocator> {
        val panes = s.elements.filter { it.scrollable && it.bounds.size == 4 }
        var last: String? = null
        return s.elements.map { e ->
            val x = normalize(e.centerX, s.width)
            val y = normalize(e.centerY, s.height)
            val inList = panes.any { p -> p !== e && x in p.bounds[0]..p.bounds[2] && y in p.bounds[1]..p.bounds[3] }
            ElementLocator(e.role, e.name, e.resourceId, e.centerX, e.centerY, last, e.hint, inList)
                .also { if (e.name.isNotBlank()) last = e.name }
        }
    }

    private fun textSimilarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val la = a.lowercase()
        val lb = b.lowercase()
        if (la == lb) return 1.0
        val minLen = min(la.length, lb.length)
        val maxLen = max(la.length, lb.length)
        if (minLen >= 2 && (la in lb || lb in la)) {
            return 0.65 + 0.30 * (minLen.toDouble() / maxLen)
        }
        val bgA = la.windowed(2).toSet()
        val bgB = lb.windowed(2).toSet()
        val jaccard = if (bgA.isEmpty() || bgB.isEmpty()) 0.0 else bgA.intersect(bgB).size.toDouble() / bgA.union(bgB).size
        val lev = 1.0 - (levenshtein(la, lb).toDouble() / maxLen)
        return 0.5 * jaccard + 0.5 * lev
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun geometricSimilarity(a: List<Int>, b: List<Int>): Double {
        if (a.size != 4 || b.size != 4) return 0.0
        val interL = max(a[0], b[0])
        val interT = max(a[1], b[1])
        val interR = min(a[2], b[2])
        val interB = min(a[3], b[3])
        val interArea = max(0, interR - interL).toLong() * max(0, interB - interT)
        val areaA = max(1, a[2] - a[0]).toLong() * max(1, a[3] - a[1])
        val areaB = max(1, b[2] - b[0]).toLong() * max(1, b[3] - b[1])
        val unionArea = areaA + areaB - interArea
        val iou = if (unionArea <= 0L) 0.0 else interArea.toDouble() / unionArea
        val dist = hypot(cx(b) - cx(a), cy(b) - cy(a))
        val prox = exp(-dist / GEO_DECAY_SCALE)
        return 0.5 * iou + 0.5 * prox
    }

    private fun resourceIdSimilarity(recorded: String?, current: String?): Double {
        if (recorded.isNullOrBlank() || current.isNullOrBlank()) return 0.0
        if (recorded == current) return 1.0
        val rLocal = recorded.substringAfterLast('/')
        val cLocal = current.substringAfterLast('/')
        if (rLocal.isNotEmpty() && rLocal == cLocal) return 0.75
        if (rLocal.length >= 4 && (rLocal in cLocal || cLocal in rLocal)) return 0.45
        return 0.0
    }

    private fun stripDynamicBadge(text: String): String {
        return text.replace(Regex("""[\(\[（]\s*\d+\s*[\)\]）]"""), "")
                   .replace(Regex("""\s*\d+\+?$"""), "")
                   .replace("·", "")
                   .trim()
    }

    private fun cx(b: List<Int>) = (b[0] + b[2]) / 2.0
    private fun cy(b: List<Int>) = (b[1] + b[3]) / 2.0

    private fun summary(s: ScreenSnapshot): String =
        "${s.packageName ?: "unknown app"}: " + s.elements.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().take(8).joinToString(", ")

    private fun emit(e: RunEvent) {
        try { sink.event(e) } catch (x: CancellationException) { throw x } catch (x: Exception) { /* 记录失败不影响回放 */ }
    }

    companion object {
        /** 这些错误在动作执行之前就拒了,界面没被动过。 */
        private val NOT_EXECUTED = setOf(
            "invalid_arguments", "unknown_tool", "invalid_link", "link_unresolved", "ref_expired", "app_in_use_by_other_task",
            "app_wait_would_deadlock", "app_wait_timed_out",
            "app_in_use_by_user", "app_not_found", "no_app_open", "confirmation_required", "batch_stopped",
        )

        /** 屏宽 15%(0–999 坐标)。 */
        private const val NEAR = 150.0
        private const val GEO_DECAY_SCALE = 260.0
        private const val HEAL_MIN_SCORE_NAMED = 0.50
        private const val HEAL_MIN_SCORE_UNNAMED = 0.58
        private const val HEAL_MIN_MARGIN = 0.08
        private val CLICKABLE_ROLES = setOf("button", "text", "view", "image", "tab", "checkbox", "switch", "radio", "Button", "TextView", "ImageView", "ImageButton")
        private val EDITABLE_ROLES = setOf("edit", "input", "EditText")
        private const val PROTECTED_SEEN = "a protected page"
        private val SLOT = Regex("\\{(\\d+)\\}")

        /** 把 `{n}` 换成第 n 个槽值;槽号不存在为 null。 */
        internal fun fill(s: String, slots: List<String>): String? {
            var bad = false
            val out = SLOT.replace(s) { m -> slots.getOrNull(m.groupValues[1].toInt() - 1) ?: run { bad = true; "" } }
            return out.takeUnless { bad }
        }

        /** 给模型看的一步说明。 */
        fun describe(step: RouteStep, slots: List<String>): String {
            val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null }
            val what = listOfNotNull(
                step.target?.name?.let { fill(it, slots) }?.takeIf { it.isNotEmpty() }?.let { "「$it」" },
                (o?.get("text") as? JsonPrimitive)?.contentOrNull?.let { fill(it, slots) }?.let { "type 「$it」" },
                (o?.get("package") as? JsonPrimitive)?.contentOrNull,
                (o?.get("direction") as? JsonPrimitive)?.contentOrNull,
                (o?.get("key") as? JsonPrimitive)?.contentOrNull,
            ).joinToString(" ")
            return if (what.isEmpty()) step.tool else "${step.tool} $what"
        }
    }
}

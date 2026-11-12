package com.hana.agent.core.replay

import com.hana.agent.core.record.RunEvent
import com.hana.agent.core.tool.ToolIndex
import com.hana.agent.core.tool.Verb
import com.hana.agent.core.uistate.ScreenSnapshot
import com.hana.agent.core.uistate.UiElement
import com.hana.agent.core.uistate.UiTreeNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/**
 * 基于 UiTree 文本、节点属性与包名的受限断言原语，供条件分支与有界循环求值使用。
 */
@Serializable
sealed interface MacroPredicate {
    fun evaluate(snapshot: ScreenSnapshot): Boolean

    @Serializable
    data class TextContains(val text: String, val ignoreCase: Boolean = true) : MacroPredicate {
        override fun evaluate(snapshot: ScreenSnapshot): Boolean {
            if (text.isBlank()) return false
            return allSnapshotTexts(snapshot).any { it.contains(text, ignoreCase = ignoreCase) }
        }
    }

    @Serializable
    data class TextAbsent(val text: String, val ignoreCase: Boolean = true) : MacroPredicate {
        override fun evaluate(snapshot: ScreenSnapshot): Boolean {
            if (text.isBlank()) return true
            return allSnapshotTexts(snapshot).none { it.contains(text, ignoreCase = ignoreCase) }
        }
    }

    @Serializable
    data class AnyTextContains(val texts: List<String>, val ignoreCase: Boolean = true) : MacroPredicate {
        override fun evaluate(snapshot: ScreenSnapshot): Boolean {
            val all = allSnapshotTexts(snapshot)
            return texts.any { target ->
                target.isNotBlank() && all.any { it.contains(target, ignoreCase = ignoreCase) }
            }
        }
    }

    @Serializable
    data class PackageEquals(val packageName: String) : MacroPredicate {
        override fun evaluate(snapshot: ScreenSnapshot): Boolean =
            snapshot.packageName == packageName
    }

    @Serializable
    data class PackageContains(val substring: String, val ignoreCase: Boolean = true) : MacroPredicate {
        override fun evaluate(snapshot: ScreenSnapshot): Boolean =
            snapshot.packageName?.contains(substring, ignoreCase = ignoreCase) == true
    }

    @Serializable
    data class AnyResourceIdContains(val hints: List<String>, val ignoreCase: Boolean = true) : MacroPredicate {
        override fun evaluate(snapshot: ScreenSnapshot): Boolean {
            val rids = allResourceIds(snapshot)
            return hints.any { hint ->
                hint.isNotBlank() && rids.any { it.contains(hint, ignoreCase = ignoreCase) }
            }
        }
    }

    @Serializable
    data class And(val predicates: List<MacroPredicate>) : MacroPredicate {
        override fun evaluate(snapshot: ScreenSnapshot): Boolean =
            predicates.all { it.evaluate(snapshot) }
    }

    @Serializable
    data class Or(val predicates: List<MacroPredicate>) : MacroPredicate {
        override fun evaluate(snapshot: ScreenSnapshot): Boolean =
            predicates.any { it.evaluate(snapshot) }
    }

    @Serializable
    data class Not(val predicate: MacroPredicate) : MacroPredicate {
        override fun evaluate(snapshot: ScreenSnapshot): Boolean =
            !predicate.evaluate(snapshot)
    }

    companion object {
        fun allSnapshotTexts(snapshot: ScreenSnapshot): List<String> {
            val out = LinkedHashSet<String>()
            for (e in snapshot.elements) {
                e.name.trim().takeIf { it.isNotEmpty() }?.let(out::add)
                e.placeholder?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            }
            fun walk(nodes: List<UiTreeNode>) {
                for (n in nodes) {
                    n.text?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
                    n.description?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
                    n.hintText?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
                    walk(n.children)
                }
            }
            for (w in snapshot.windows) {
                w.title?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
                walk(w.nodes)
            }
            return out.toList()
        }

        fun allResourceIds(snapshot: ScreenSnapshot): List<String> {
            val out = LinkedHashSet<String>()
            snapshot.elements.forEach { e ->
                e.resourceId?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            }
            fun walk(nodes: List<UiTreeNode>) {
                for (n in nodes) {
                    n.resourceId?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
                    walk(n.children)
                }
            }
            snapshot.windows.forEach { walk(it.nodes) }
            return out.toList()
        }

        /** 在当前屏幕快照上查找与指定文案或 resourceId 提示匹配的交互元素（用于弹窗关闭等动态目标定位）。 */
        fun findMatchingElement(
            snapshot: ScreenSnapshot,
            texts: List<String>,
            resourceIdHints: List<String> = emptyList(),
        ): UiElement? {
            for (label in texts) {
                if (label.isBlank()) continue
                val exact = snapshot.elements.firstOrNull {
                    it.clickable && it.name.trim().equals(label, ignoreCase = true)
                } ?: snapshot.elements.firstOrNull {
                    it.name.trim().equals(label, ignoreCase = true)
                }
                if (exact != null) return exact

                val partial = snapshot.elements.firstOrNull {
                    it.clickable && it.name.trim().contains(label, ignoreCase = true)
                }
                if (partial != null) return partial
            }
            for (hint in resourceIdHints) {
                if (hint.isBlank()) continue
                val byRid = snapshot.elements.firstOrNull {
                    it.clickable && it.resourceId?.contains(hint, ignoreCase = true) == true
                } ?: snapshot.elements.firstOrNull {
                    it.resourceId?.contains(hint, ignoreCase = true) == true
                }
                if (byRid != null) return byRid
            }
            return null
        }
    }
}

/**
 * 受限条件/循环微流 AST 节点。
 */
@Serializable
sealed interface FlowNode {
    @Serializable
    data class Step(
        val tool: String,
        val argsJson: String = "{}",
        val expectText: String? = null,
        val expectGone: String? = null,
        val expectApp: String? = null,
        val waitStable: Boolean = false,
        val timeoutMs: Long = 5_000L,
    ) : FlowNode

    @Serializable
    data class TapMatching(
        val texts: List<String>,
        val resourceIdHints: List<String> = emptyList(),
        val fallbackBack: Boolean = false,
    ) : FlowNode

    @Serializable
    data class If(
        val condition: MacroPredicate,
        val thenBranch: List<FlowNode>,
        val elseBranch: List<FlowNode> = emptyList(),
    ) : FlowNode

    @Serializable
    data class RepeatUntil(
        val stopCondition: MacroPredicate,
        val body: List<FlowNode>,
        val maxIterations: Int = DEFAULT_LOOP_MAX_ITERATIONS,
        val timeoutMs: Long = DEFAULT_LOOP_TIMEOUT_MS,
    ) : FlowNode

    companion object {
        const val DEFAULT_LOOP_MAX_ITERATIONS = 8
        const val MAX_LOOP_ITERATIONS = 20
        const val DEFAULT_LOOP_TIMEOUT_MS = 15_000L
        const val MAX_LOOP_TIMEOUT_MS = 30_000L
    }
}

/**
 * 演化式影子宏定义：由历史高频轨迹提炼而成，支持参数化槽位实例化与受限微流执行。
 */
@Serializable
data class ShadowMacroDef(
    val id: String,
    val name: String,
    val description: String,
    val params: List<String> = emptyList(),
    val supportCount: Int = 1,
    val flow: List<FlowNode>,
    val maxTotalSteps: Int = DEFAULT_MAX_STEPS,
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
) {
    /**
     * 使用具名参数将影子宏模板实例化为可执行微流计划。
     * 支持可选参数 `maxSwipes` / `maxIterations` / `maxPopups` 覆盖循环上限（受硬上限约束）。
     */
    fun instantiate(args: Map<String, String>): ShadowMacroPlan {
        for (p in params) {
            val v = args[p]
            require(!v.isNullOrBlank()) { "Missing or blank macro parameter: $p" }
        }
        val iterOverride = (args["maxSwipes"] ?: args["maxPopups"] ?: args["maxIterations"])
            ?.toIntOrNull()
            ?.coerceIn(1, FlowNode.MAX_LOOP_ITERATIONS)
        val substituted = flow.map { substituteNode(it, args, iterOverride) }
        return ShadowMacroPlan(
            macroId = id,
            macroName = name,
            flow = substituted,
            maxTotalSteps = maxTotalSteps.coerceIn(1, HARD_MAX_STEPS),
            maxDurationMs = maxDurationMs.coerceIn(500L, HARD_MAX_DURATION_MS),
        )
    }

    private fun substituteNode(node: FlowNode, args: Map<String, String>, iterOverride: Int?): FlowNode = when (node) {
        is FlowNode.Step -> node.copy(
            argsJson = replacePlaceholders(node.argsJson, args),
            expectText = node.expectText?.let { replacePlaceholders(it, args) },
            expectGone = node.expectGone?.let { replacePlaceholders(it, args) },
            expectApp = node.expectApp?.let { replacePlaceholders(it, args) },
        )
        is FlowNode.TapMatching -> node.copy(
            texts = node.texts.map { replacePlaceholders(it, args) },
            resourceIdHints = node.resourceIdHints.map { replacePlaceholders(it, args) },
        )
        is FlowNode.If -> node.copy(
            condition = substitutePredicate(node.condition, args),
            thenBranch = node.thenBranch.map { substituteNode(it, args, iterOverride) },
            elseBranch = node.elseBranch.map { substituteNode(it, args, iterOverride) },
        )
        is FlowNode.RepeatUntil -> node.copy(
            stopCondition = substitutePredicate(node.stopCondition, args),
            body = node.body.map { substituteNode(it, args, iterOverride) },
            maxIterations = (iterOverride ?: node.maxIterations).coerceIn(1, FlowNode.MAX_LOOP_ITERATIONS),
            timeoutMs = node.timeoutMs.coerceIn(500L, FlowNode.MAX_LOOP_TIMEOUT_MS),
        )
    }

    private fun substitutePredicate(pred: MacroPredicate, args: Map<String, String>): MacroPredicate = when (pred) {
        is MacroPredicate.TextContains -> pred.copy(text = replacePlaceholders(pred.text, args))
        is MacroPredicate.TextAbsent -> pred.copy(text = replacePlaceholders(pred.text, args))
        is MacroPredicate.AnyTextContains -> pred.copy(texts = pred.texts.map { replacePlaceholders(it, args) })
        is MacroPredicate.PackageEquals -> pred.copy(packageName = replacePlaceholders(pred.packageName, args))
        is MacroPredicate.PackageContains -> pred.copy(substring = replacePlaceholders(pred.substring, args))
        is MacroPredicate.AnyResourceIdContains -> pred.copy(hints = pred.hints.map { replacePlaceholders(it, args) })
        is MacroPredicate.And -> pred.copy(predicates = pred.predicates.map { substitutePredicate(it, args) })
        is MacroPredicate.Or -> pred.copy(predicates = pred.predicates.map { substitutePredicate(it, args) })
        is MacroPredicate.Not -> pred.copy(predicate = substitutePredicate(pred.predicate, args))
    }

    private fun replacePlaceholders(raw: String, args: Map<String, String>): String {
        var out = raw
        for ((k, v) in args) {
            out = out.replace("{$k}", v)
        }
        return out
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 20
        const val HARD_MAX_STEPS = 30
        const val DEFAULT_MAX_DURATION_MS = 25_000L
        const val HARD_MAX_DURATION_MS = 30_000L
    }
}

/** 实例化后的可执行影子宏计划。 */
data class ShadowMacroPlan(
    val macroId: String,
    val macroName: String,
    val flow: List<FlowNode>,
    val maxTotalSteps: Int,
    val maxDurationMs: Long,
)

/**
 * 内置高频复合影子宏原语库：
 * 1. `scroll_until_text(target, maxSwipes)`: 滚动查找目标文案直到在 UiTree 中出现或达到滑动上限。
 * 2. `dismiss_popup_stack()`: 连续清理开屏/活动/协议弹窗栈直到界面不再存在弹窗遮挡控件。
 */
object ShadowMacroLibrary {
    val DEFAULT_DISMISS_LABELS = listOf(
        "跳过", "关闭", "以后再说", "稍后再说", "暂不", "残忍拒绝", "忽略", "我知道了", "取消", "不再提示",
    )
    val DEFAULT_DISMISS_RESOURCE_ID_HINTS = listOf(
        "close", "dismiss", "skip", "cancel", "iv_close", "btn_close",
    )

    fun scrollUntilText(
        target: String = "{target}",
        maxSwipes: Int = 8,
        direction: String = "up",
        timeoutMs: Long = 15_000L,
        supportCount: Int = 1,
    ): ShadowMacroDef {
        val cappedSwipes = maxSwipes.coerceIn(1, FlowNode.MAX_LOOP_ITERATIONS)
        val swipeArgs = if (direction == "down") {
            """{"startX":500,"startY":260,"endX":500,"endY":780,"durationMs":400}"""
        } else {
            """{"startX":500,"startY":780,"endX":500,"endY":260,"durationMs":400}"""
        }
        val params = if (target == "{target}") listOf("target") else emptyList()
        return ShadowMacroDef(
            id = "macro_scroll_until_text",
            name = "scroll_until_text",
            description = "Scroll repeatedly until target text appears in UiTree or maxSwipes is reached.",
            params = params,
            supportCount = supportCount,
            flow = listOf(
                FlowNode.RepeatUntil(
                    stopCondition = MacroPredicate.TextContains(target),
                    body = listOf(FlowNode.Step("phone_swipe", swipeArgs)),
                    maxIterations = cappedSwipes,
                    timeoutMs = timeoutMs.coerceIn(500L, FlowNode.MAX_LOOP_TIMEOUT_MS),
                ),
            ),
            maxTotalSteps = cappedSwipes + 2,
            maxDurationMs = timeoutMs.coerceIn(500L, ShadowMacroDef.HARD_MAX_DURATION_MS),
        )
    }

    fun dismissPopupStack(
        maxPopups: Int = 5,
        timeoutMs: Long = 10_000L,
        dismissLabels: List<String> = DEFAULT_DISMISS_LABELS,
        dismissResourceIds: List<String> = DEFAULT_DISMISS_RESOURCE_ID_HINTS,
        supportCount: Int = 1,
    ): ShadowMacroDef {
        val capped = maxPopups.coerceIn(1, FlowNode.MAX_LOOP_ITERATIONS)
        val hasPopupCondition = MacroPredicate.Or(
            listOf(
                MacroPredicate.AnyTextContains(dismissLabels),
                MacroPredicate.AnyResourceIdContains(dismissResourceIds),
            ),
        )
        return ShadowMacroDef(
            id = "macro_dismiss_popup_stack",
            name = "dismiss_popup_stack",
            description = "Repeatedly dismiss stacked popups/overlays until no dismissible popup cue remains.",
            params = emptyList(),
            supportCount = supportCount,
            flow = listOf(
                FlowNode.RepeatUntil(
                    stopCondition = MacroPredicate.Not(hasPopupCondition),
                    body = listOf(
                        FlowNode.TapMatching(
                            texts = dismissLabels,
                            resourceIdHints = dismissResourceIds,
                            fallbackBack = false,
                        ),
                    ),
                    maxIterations = capped,
                    timeoutMs = timeoutMs.coerceIn(500L, FlowNode.MAX_LOOP_TIMEOUT_MS),
                ),
            ),
            maxTotalSteps = capped + 2,
            maxDurationMs = timeoutMs.coerceIn(500L, ShadowMacroDef.HARD_MAX_DURATION_MS),
        )
    }
}

/**
 * 轨迹单步轻量表示（供历史高频模式挖掘使用）。
 */
data class TrajectoryStep(
    val tool: String,
    val argsJson: String,
    val targetName: String? = null,
    val targetRole: String? = null,
    val packageName: String? = null,
)

/**
 * 演化式影子宏挖掘器：
 * 从历史成功路线 ([Route])、运行事件流 ([RunEvent]) 或原子轨迹序列中提炼：
 * 1. 高频滚动寻靶模式 -> `scroll_until_text(target, maxSwipes)`
 * 2. 高频弹窗栈消解模式 -> `dismiss_popup_stack()`
 * 3. 高频连续业务子序列 -> 带参数占位符 `{arg1}...` 的复合影子宏
 */
object ShadowMacroMiner {

    private val SCROLL_TOOLS = setOf("phone_swipe", "phone_scroll", "swipe", "scroll")

    fun mineFromRoutes(routes: List<Route>, minSupport: Int = 2): List<ShadowMacroDef> {
        val trajectories = routes.map { r ->
            r.steps.map { st ->
                TrajectoryStep(
                    tool = st.tool,
                    argsJson = st.argsJson,
                    targetName = st.target?.name?.takeIf { it.isNotBlank() },
                    targetRole = st.target?.role,
                    packageName = st.before.packageName ?: r.packages.firstOrNull(),
                )
            }
        }
        return mineFromTrajectories(trajectories, minSupport = minSupport)
    }

    fun mineFromEvents(runs: List<List<RunEvent>>, tools: ToolIndex, minSupport: Int = 2): List<ShadowMacroDef> {
        val trajectories = runs.mapNotNull { events ->
            val ended = events.lastOrNull { it is RunEvent.TaskEnded } as? RunEvent.TaskEnded
            if (ended != null && ended.outcome != "succeeded") return@mapNotNull null
            val steps = events.filterIsInstance<RunEvent.ActionTarget>()
                .filter { it.effective && tools[it.tool]?.routable == true }
                .map { a ->
                    TrajectoryStep(
                        tool = a.tool,
                        argsJson = a.argsJson,
                        targetName = a.target?.name?.takeIf { it.isNotBlank() },
                        targetRole = a.target?.role,
                        packageName = a.before.packageName,
                    )
                }
            steps.takeIf { it.isNotEmpty() }
        }
        return mineFromTrajectories(trajectories, minSupport = minSupport)
    }

    fun mineFromTrajectories(trajectories: List<List<TrajectoryStep>>, minSupport: Int = 2): List<ShadowMacroDef> {
        if (trajectories.isEmpty()) return emptyList()
        val threshold = maxOf(1, minSupport)
        val macros = mutableListOf<ShadowMacroDef>()

        // 1. 挖掘连续滚动寻靶模式（连续 2+ 次 swipe/scroll 后紧跟一次非占位目标点击）
        var scrollSearchHits = 0
        var maxObservedSwipes = 4
        for (traj in trajectories) {
            var runLen = 0
            for (step in traj) {
                if (step.tool in SCROLL_TOOLS) {
                    runLen++
                } else {
                    if (runLen >= 2 && step.tool == "phone_tap") {
                        scrollSearchHits++
                        maxObservedSwipes = maxOf(maxObservedSwipes, runLen + 2)
                    }
                    runLen = 0
                }
            }
        }
        if (scrollSearchHits >= threshold) {
            macros += ShadowMacroLibrary.scrollUntilText(
                target = "{target}",
                maxSwipes = maxObservedSwipes.coerceAtMost(FlowNode.MAX_LOOP_ITERATIONS),
                supportCount = scrollSearchHits,
            )
        }

        // 2. 挖掘高频弹窗清理模式
        val dismissLabelsLower = ShadowMacroLibrary.DEFAULT_DISMISS_LABELS.map { it.lowercase() }
        var popupStackHits = 0
        var maxPopupsObserved = 3
        for (traj in trajectories) {
            var popupCount = 0
            for (step in traj) {
                val label = step.targetName?.trim()?.lowercase()
                if (step.tool == "phone_tap" && label != null && dismissLabelsLower.any { it in label }) {
                    popupCount++
                }
            }
            if (popupCount >= 2) {
                popupStackHits++
                maxPopupsObserved = maxOf(maxPopupsObserved, popupCount + 1)
            } else if (popupCount == 1) {
                popupStackHits++
            }
        }
        if (popupStackHits >= threshold) {
            macros += ShadowMacroLibrary.dismissPopupStack(
                maxPopups = maxPopupsObserved.coerceAtMost(FlowNode.MAX_LOOP_ITERATIONS),
                supportCount = popupStackHits,
            )
        }

        // 3. 挖掘通用连续子序列 N-gram（长度 2..5），识别结构一致且参数可抽象为槽位的复合宏
        data class Occurrence(val trajIndex: Int, val slice: List<TrajectoryStep>)
        val buckets = LinkedHashMap<String, MutableList<Occurrence>>()
        for ((tIdx, traj) in trajectories.withIndex()) {
            if (traj.size < 2) continue
            val seenKeysInTraj = HashSet<String>()
            val maxN = minOf(5, traj.size)
            for (n in 2..maxN) {
                for (start in 0..traj.size - n) {
                    val slice = traj.subList(start, start + n)
                    // 避免纯滚动片段重复进入通用序列桶
                    if (slice.all { it.tool in SCROLL_TOOLS }) continue
                    val sig = slice.joinToString("->") { s ->
                        val roleTag = s.targetRole.orEmpty()
                        "${s.tool}:$roleTag"
                    }
                    if (seenKeysInTraj.add("$n|$sig")) {
                        buckets.getOrPut(sig) { mutableListOf() }.add(Occurrence(tIdx, slice))
                    }
                }
            }
        }

        val minedSequences = buckets.entries
            .filter { it.value.size >= threshold }
            .sortedWith(
                compareByDescending<Map.Entry<String, MutableList<Occurrence>>> { it.value.size * it.value.first().slice.size }
                    .thenByDescending { it.value.first().slice.size },
            )
            .take(5)

        for ((sig, occurrences) in minedSequences) {
            val sample = occurrences.first().slice
            val params = mutableListOf<String>()
            val flow = sample.mapIndexed { idx, step ->
                val allTextArgs = occurrences.mapNotNull { occ ->
                    extractTextArg(occ.slice[idx].argsJson)
                }.distinct()
                val argsJson = if (allTextArgs.size > 1) {
                    val paramName = "arg${params.size + 1}"
                    params += paramName
                    replaceTextArg(step.argsJson, "{$paramName}")
                } else {
                    step.argsJson
                }
                FlowNode.Step(tool = step.tool, argsJson = argsJson)
            }
            val digest = shortHash(sig)
            macros += ShadowMacroDef(
                id = "macro_seq_$digest",
                name = "seq_${sample.joinToString("_") { it.tool.removePrefix("phone_") }}_$digest",
                description = "Mined frequent ${sample.size}-step sequence (${occurrences.size} occurrences).",
                params = params,
                supportCount = occurrences.size,
                flow = flow,
                maxTotalSteps = (sample.size * 2).coerceIn(4, ShadowMacroDef.HARD_MAX_STEPS),
            )
        }

        return macros.sortedByDescending { it.supportCount }
    }

    private fun extractTextArg(argsJson: String): String? {
        val obj = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: return null
        return (obj["text"] as? JsonPrimitive)?.contentOrNull
    }

    private fun replaceTextArg(argsJson: String, placeholder: String): String {
        val obj = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: return argsJson
        if ("text" !in obj) return argsJson
        return JsonObject(obj + ("text" to JsonPrimitive(placeholder))).toString()
    }

    private fun shortHash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.take(3).joinToString("") { "%02x".format(it) }
    }
}

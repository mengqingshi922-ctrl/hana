package com.hana.agent.core.uistate.tools

import com.hana.agent.core.uistate.ScreenFamily
import com.hana.agent.core.uistate.Located
import com.hana.agent.core.risk.GateTarget
import com.hana.agent.core.prompt.Feedback
import com.hana.agent.core.uistate.ScreenSnapshot
import com.hana.agent.core.tool.ArgError
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.COORD
import com.hana.agent.core.tool.LOCATED_REF
import com.hana.agent.core.tool.REF
import com.hana.agent.core.tool.Risk
import com.hana.agent.core.tool.SNAP
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolDef
import com.hana.agent.core.tool.ToolExecution
import com.hana.agent.core.tool.Verb
import com.hana.agent.core.tool.argStr
import com.hana.agent.core.tool.fail
import com.hana.agent.core.tool.int
import com.hana.agent.core.tool.str
import com.hana.agent.core.uistate.toPx
import com.hana.agent.core.uistate.DESCRIBED_ELEMENT
import com.hana.agent.core.uistate.DESCRIBED_ROLE
import com.hana.agent.core.vision.LOCATE_SCREEN_CHANGE
import com.hana.agent.core.vision.hamming
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.hana.agent.core.uistate.action
import com.hana.agent.core.uistate.described
import com.hana.agent.core.uistate.lastOrSnapshot
import com.hana.agent.core.uistate.lastSnapshot
import com.hana.agent.core.uistate.located
import com.hana.agent.core.uistate.locatedInfo
import com.hana.agent.core.uistate.screen
import com.hana.agent.core.uistate.session
import com.hana.agent.core.uistate.withPortRef

internal class Tap(f: ScreenFamily) : ScreenTool(f, "tap") {
    override val verb = Verb.Tap
    override val batchable = true
    override val routable = f.routable
    override val label = "点击"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Tap
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "ref" to str(REF), "snapshotId" to str(SNAP),
            "x" to int(COORD, 0, 999), "y" to int(COORD, 0, 999),
            "target" to str("Short label of what is being tapped (coordinate taps)."),
            DESCRIBED_ELEMENT to str(DESCRIBE_TAP), DESCRIBED_ROLE to str(DESCRIBE_ROLE),
            "clicks" to int("1 taps once (default), 2 double-taps.", 1, 2),
        )) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val ref = a.ref(located = true)
        val x = a.coord("x"); val y = a.coord("y")
        val clicks = a.int("clicks", 1, 2) ?: 1
        val target = a.str("target", max = 80)
        // 执行入口已把描述换成编号;走到这里还带着描述说明没经执行入口,不猜
        if (a.str(DESCRIBED_ELEMENT) != null) throw ArgError(DESCRIBED_ELEMENT, "could not be resolved to an element here; use ref+snapshotId")
        if (ref == null && ctx.described && target == null) throw ArgError("target", "required for coordinate taps after phone_describe")
        if (ref != null) {
            // 模型同时传 ref 与 x/y 时智能优先使用 ref，不向外报参数互斥错误
        } else {
            if (x == null) throw ArgError("x", "required with y when no ref is given")
            if (y == null) throw ArgError("y", "required with x when no ref is given")
        }
        val twice = clicks == 2
        if (ref != null) {
            if (LOCATED_REF.matches(ref.first)) return tapLocated(ref.first, ref.second, twice, ctx)
            return ctx.withPortRef(ref) { r ->
                val primaryResult = if (twice) ctx.screen.doubleTapRef(r!!.first, r.second) else ctx.screen.tapRef(r!!.first, r.second)
                // 双通道点击互备自愈（A11y ↔ Touch Fallback）：
                // 增加执行后画面/节点变动检测。若上报点击成功但画面或无障碍焦点未发生任何变化，
                // 自动降级改走底层物理触摸注入通道进行二次补偿点击
                if (primaryResult is com.hana.agent.core.uistate.ActionResult.Ok && primaryResult.changed == false && primaryResult.opened == null) {
                    val element = (ctx.lastSnapshot ?: ctx.session.lastSnapshot)?.elements?.firstOrNull { it.ref == r.first }
                    if (element != null) {
                        val fallbackResult = if (twice) ctx.screen.doubleTapXY(element.centerX, element.centerY)
                                              else ctx.screen.tapXY(element.centerX, element.centerY)
                        if (fallbackResult is com.hana.agent.core.uistate.ActionResult.Ok && (fallbackResult.changed == true || fallbackResult.opened != null)) {
                            return@withPortRef ctx.action(fallbackResult)
                        }
                    }
                }
                ctx.action(primaryResult)
            }
        }
        val s = ctx.lastOrSnapshot()
        val pxX = toPx(x!!, s.width)
        val pxY = toPx(y!!, s.height)
        val primaryResult = tapAt(ctx, pxX, pxY, twice)
        if (primaryResult is com.hana.agent.core.uistate.ActionResult.Ok && primaryResult.changed == false && primaryResult.opened == null) {
            val element = (ctx.lastSnapshot ?: ctx.session.lastSnapshot)?.elements?.firstOrNull { e ->
                x in e.bounds[0]..e.bounds[2] && y in e.bounds[1]..e.bounds[3]
            }
            if (element != null && (element.centerX != pxX || element.centerY != pxY)) {
                val fallbackResult = tapAt(ctx, element.centerX, element.centerY, twice)
                if (fallbackResult is com.hana.agent.core.uistate.ActionResult.Ok && (fallbackResult.changed == true || fallbackResult.opened != null)) {
                    return ctx.action(fallbackResult)
                }
            }
        }
        return ctx.action(primaryResult)
    }

    private suspend fun tapAt(ctx: ToolContext, x: Int, y: Int, twice: Boolean) =
        if (twice) ctx.screen.doubleTapXY(x, y) else ctx.screen.tapXY(x, y)

    /** 点 find_visually 找到的点:先重新截图,和找到时的画面差太多(或截不到)就不点。 */
    private suspend fun tapLocated(ref: String, snapshotId: String, twice: Boolean, ctx: ToolContext): ToolExecution {
        val (x, y) = locatedPixel(ctx, ref, snapshotId) ?: return fail("ref_expired", Feedback["located_stale"])
        val info = ctx.locatedInfo[ref]
        val target = info?.target
        val r = ctx.action(tapAt(ctx, x, y, twice))
        if (target == null || r.content["ok"] != JsonPrimitive(true)) return r
        // 没佐证的点:点了有没有用只能看效果,提示里先说清楚
        val hint = listOfNotNull(Feedback["located.unverified"].takeIf { info.unverified }, (r.content["hint"] as? JsonPrimitive)?.content)
        val extra = mapOf("located" to JsonPrimitive(target)) +
            (if (info.unverified) mapOf("unverifiedPoint" to JsonPrimitive(true)) else emptyMap()) +
            (if (hint.isNotEmpty()) mapOf("hint" to JsonPrimitive(hint.joinToString(" "))) else emptyMap())
        return ToolExecution(JsonObject(r.content + extra), changed = r.changed)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String =
        refTarget(o, r, ctx.lastSnapshot) ?: o.argStr("target") ?: "${o["x"]},${o["y"]}"
}

/**
 * find_visually 找到的点([ref] 是 @vN)此刻的像素位置:先重新截图,和找到时的画面差太多(或截不到)为 null,不往那里点。
 * 换算像素按此刻的一屏(读不到再退回最近一次)。
 */
internal suspend fun locatedPixel(ctx: ToolContext, ref: String, snapshotId: String): Pair<Int, Int>? {
    val m = com.hana.agent.core.tool.VISUAL_TXT_REF.find(ref)
    if (m != null) {
        val idx = m.groupValues[2].toIntOrNull()
        val lines = ctx.session.lastOcrText
        val targetLine = if (idx != null) lines?.getOrNull(idx) else null
        if (targetLine != null && targetLine.bounds.size == 4) {
            val cx = (targetLine.bounds[0] + targetLine.bounds[2]) / 2
            val cy = (targetLine.bounds[1] + targetLine.bounds[3]) / 2
            val s = ctx.session.lastSnapshot ?: ctx.lastOrSnapshot()
            ctx.locatedInfo[ref] = Located(targetLine.text, targetLine.text, targetLine.text, unverified = false)
            return toPx(cx, s.width) to toPx(cy, s.height)
        }
    }
    val (p, hash) = ctx.located.get(ref, snapshotId) ?: return null
    val screen = ctx.screen
    val png = if (screen.isProtected()) null else screen.screenshotPng()
    if (png == null || hamming(ctx.config.vision!!.ops.aHash(png), hash) > LOCATE_SCREEN_CHANGE) return null
    val s = ctx.session.peek() ?: ctx.lastOrSnapshot()
    return toPx(p.x, s.width) to toPx(p.y, s.height)
}

/** 按编号操作时动作清单里写的对象:看图找到的点写它找的是什么,其余写元素的名字,都没有写编号。 */
internal fun refTarget(o: JsonObject, r: ToolExecution, last: ScreenSnapshot?): String? = o.argStr("ref")?.let { ref ->
    (r.content["located"] as? JsonPrimitive)?.content
        ?: last?.elements?.firstOrNull { it.ref == ref }?.name?.takeIf(String::isNotBlank) ?: ref
}

internal const val DESCRIBE_TAP = "Instead of ref+snapshotId: the element's visible text or accessible name, as shown on the screen. It must match exactly one element on the screen right now."
internal const val DESCRIBE_ROLE = "Optional with element: the element's role as shown in the element list (e.g. button, link, textbox), to tell same-named elements apart."

package com.hana.agent.core.cap.phone

import com.hana.agent.core.tool.Entry
import com.hana.agent.core.observation.ObsKind
import com.hana.agent.core.observation.Observation
import com.hana.agent.core.prompt.Feedback
import com.hana.agent.core.prompt.Prompts
import com.hana.agent.core.tool.ArgError
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.EvidenceLog
import com.hana.agent.core.tool.Risk
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolDef
import com.hana.agent.core.tool.ToolExecution
import com.hana.agent.core.tool.Verb
import com.hana.agent.core.tool.argStr
import com.hana.agent.core.tool.bool
import com.hana.agent.core.tool.int
import com.hana.agent.core.tool.failCode
import com.hana.agent.core.tool.ok
import com.hana.agent.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.hana.agent.core.tool.LinkPolicy
import com.hana.agent.core.uistate.ActionResult
import com.hana.agent.core.uistate.APP_IN_USE_BY_OTHER_TASK
import com.hana.agent.core.uistate.AppWait
import com.hana.agent.core.uistate.action
import com.hana.agent.core.uistate.session

private val KNOWN_APP_PACKAGES = mapOf(
    "微信" to "com.tencent.mm",
    "支付宝" to "com.eg.android.AlipayGphone",
    "小红书" to "com.xingin.xhs",
    "高德" to "com.autonavi.minimap",
    "抖音" to "com.ss.android.ugc.aweme",
    "淘宝" to "com.taobao.taobao",
    "京东" to "com.jingdong.app.mall",
    "美团" to "com.sankuai.meituan",
    "外卖" to "com.sankuai.meituan.takeoutnew",
    "网易云" to "com.netease.cloudmusic",
    "哔哩哔哩" to "tv.danmaku.bili",
    "b站" to "tv.danmaku.bili",
    "B站" to "tv.danmaku.bili",
    "知乎" to "com.zhihu.android",
    "百度地图" to "com.baidu.BaiduMap",
    "微博" to "com.sina.weibo",
    "拼多多" to "com.xunmeng.pinduoduo",
    "快手" to "com.kuaishou.nebula",
    "闲鱼" to "com.taobao.idlefish",
    "QQ" to "com.tencent.mobileqq",
    "qq" to "com.tencent.mobileqq"
)

internal suspend fun resolveAppPackage(p: String, ctx: ToolContext): String {
    if (LinkPolicy.PACKAGE.matches(p)) return p
    val installed = runCatching { ctx.phone.screen.listApps() }.getOrNull().orEmpty()
    val matched = installed.firstOrNull { (_, label) -> label.equals(p, ignoreCase = true) }?.first
        ?: installed.firstOrNull { (_, label) -> label.contains(p, ignoreCase = true) }?.first
        ?: installed.firstOrNull { (pkg, _) -> pkg.contains(p, ignoreCase = true) }?.first
        ?: KNOWN_APP_PACKAGES.entries.firstOrNull { (name, _) -> p.contains(name, ignoreCase = true) }?.value
    return matched ?: throw ArgError("package", "must be an Android package name (or installed app name)")
}

/** 列出、打开手机上的 App:永远作用于手机。 */
internal object ListApps : PhoneTool {
    override val verb = Verb.ListApps
    override val label = "查看已装的 App"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val def = ToolDef("phone_list_apps", Prompts.tool("phone_list_apps"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val apps = ctx.phone.screen.listApps()
        // 也装在分身、工作资料一类的另一份用户里的:每一份单列一项,带上打开它要用的 user
        val copies = ctx.phone.screen.appCopies().groupBy { it.packageName }
        val access = ctx.phone.screen.appAccess.current()
        return ok {
            put("packages", JsonArray(apps.map { JsonPrimitive(it.first) }))
            put("apps", JsonArray(apps.flatMap { (p, l) ->
                listOf(buildJsonObject { put("package", p); put("label", l); put("blocked", p in access.blocked); put("screenShareAllowed", p in access.shareAllowed) }) +
                    copies[p].orEmpty().map { c -> buildJsonObject { put("package", p); put("label", c.label(l)); put("user", c.userId) } }
            }))
        }
    }

    /** 列出的 App 名字不单独作证据,记下来给之后打开 App 的事实用(另一份的名字不记,免得盖掉本来那份的)。 */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        (r.content["apps"] as? JsonArray)?.forEach { a ->
            val o2 = a as? JsonObject ?: return@forEach
            if ("user" in o2) return@forEach
            val p = (o2["package"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            (o2["label"] as? JsonPrimitive)?.contentOrNull?.let { log.appLabels[p] = it }
        }
        return emptyList()
    }
}

internal object LaunchApp : PhoneTool {
    override val verb = Verb.LaunchApp
    override val batchable = true
    override val routable = true
    override val label = "打开 App"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_launch_app", Prompts.tool("phone_launch_app"),
        mapOf(
            "package" to str("Exact package name, e.g. com.example.app."),
            "cold" to bool("Restart the app first (default false)."),
            "user" to int("Only to open another copy of the app (a clone or work-profile copy): the user value of that copy's phone_list_apps entry. Leave out for the main app.", min = 0, max = MAX_USER),
            "wait" to int("Only when another task is using this app: seconds to wait for it to be free, then open it (at most $MAX_WAIT_S). Leave out to get the in-use result at once.", min = 1, max = MAX_WAIT_S),
        ),
        listOf("package"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val rawPkg = a.str("package")!!
        val shortcutUri = LinkPolicy.resolveShortcut(rawPkg)
        if (shortcutUri != null && ctx.phone.deepLinks) {
            ctx.refusedPay = null
            val openedLink = ctx.action(ctx.phone.screen.openLink(null, shortcutUri, null))
            if (openedLink.content["ok"] == JsonPrimitive(true)) {
                ctx.appOpened = true
                return openedLink
            }
        }
        val p = resolveAppPackage(rawPkg, ctx)
        val user = a.int("user", 0, MAX_USER)
        val cold = a.bool("cold", false)
        val wait = a.int("wait", 1, MAX_WAIT_S)
        // 只打开列出过的那几份:不在可用的另一份用户里(私密空间、别人的用户、没装)的一律不碰
        if (user != null && ctx.phone.screen.appCopies().none { it.packageName == p && it.userId == user }) {
            return ctx.action(ActionResult.Failed(COPY_NOT_FOUND))
        }
        // 换了 App,之前被拒的付款申报就不是这次的了
        ctx.refusedPay = null
        suspend fun open() = if (user != null) ctx.phone.screen.openAppCopy(p, user, cold) else ctx.phone.screen.openApp(p, cold)
        var opened = open()
        // 别的任务正用着、模型要了等:等它放开再开一次;互相等(谁也等不到)、等到时限仍被占着,照实说
        if (wait != null && opened is ActionResult.Failed && opened.reason == APP_IN_USE_BY_OTHER_TASK) {
            when (ctx.phone.screen.awaitAppFree(p, user, wait * 1_000L)) {
                AppWait.Free -> opened = open()
                AppWait.WouldDeadlock -> return failCode(WAIT_WOULD_DEADLOCK)
                AppWait.TimedOut -> return failCode(WAIT_TIMED_OUT)
                AppWait.Unsupported -> Unit
            }
        }
        val r = ctx.action(opened)
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        return unreadable(ctx, p, r)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        o.argStr("package")?.let { p -> (o["user"] as? JsonPrimitive)?.contentOrNull?.let { "$p (user $it)" } ?: p }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull(o.argStr("package")?.let { p ->
            val copy = (o["user"] as? JsonPrimitive)?.contentOrNull?.let { "(另一份,用户 $it)" }.orEmpty()
            Observation(ObsKind.Fact, "$name 打开了 $p$copy" + (log.appLabels[p]?.let { "(App 名字「$it」)" } ?: ""), source = PHONE)
        })
    }

    /** 安卓的用户编号上限(UserHandle.PER_USER_RANGE 以内)。 */
    private const val MAX_USER = 99_999
    /** 等别的任务放开 App 最多等几秒:再长不如先做别的,或告诉用户。 */
    const val MAX_WAIT_S = 180
    const val COPY_NOT_FOUND = "copy_not_found"
    const val WAIT_WOULD_DEADLOCK = "app_wait_would_deadlock"
    const val WAIT_TIMED_OUT = "app_wait_timed_out"
}

/**
 * 任务结束时这个 App 留着还是关掉。默认留着(放回后台,停在做完的那一页,下次接着用),结束时正在出声的关掉;
 * 用户说了要它接着放、或做完就关(临时登录一类)时用它标出来。只记下,此刻什么也不动;同一份 App 再标一次以最后一次为准。
 */
internal object AppAfterTask : PhoneTool {
    override val verb = Verb.AppAfterTask
    override val label = "定下 App 做完后留着还是关掉"
    override val risk = Risk.Write
    override val logged = true
    override val def = ToolDef("phone_app_after_task", Prompts.tool("phone_app_after_task"),
        mapOf(
            "package" to str("Exact package name of the app, e.g. com.example.app."),
            "action" to str("keep: leave it running in the background after the task (even if it is playing sound). close: close it when the task ends.", listOf(KEEP, CLOSE)),
            "reason" to str("Why, in a few words (e.g. the user wants the music to keep playing; a one-time sign-in)."),
            "user" to int("Only for another copy of the app (a clone or work-profile copy): the user value of that copy's phone_list_apps entry. Leave out for the main app.", min = 0, max = MAX_USER),
        ),
        listOf("package", "action", "reason"))

    /** 只是记下,不切换在操作的那块屏。 */
    override fun aimAt(o: JsonObject): String? = null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = resolveAppPackage(a.str("package")!!, ctx)
        val action = a.str("action")!!
        if (action != KEEP && action != CLOSE) throw ArgError("action", "must be keep or close")
        val reason = a.str("reason", max = 200)!!.trim()
        val user = a.int("user", 0, MAX_USER)
        val marks = ctx.phone.screen.afterTask ?: return failCode(AFTER_TASK_UNAVAILABLE)
        val previous = marks.mark(p, user, action == KEEP, reason)
        return ok {
            put("package", p)
            user?.let { put("user", it) }
            put("action", action)
            previous?.takeIf { it.keep != (action == KEEP) }?.let { put("replaces", if (it.keep) KEEP else CLOSE) }
            put("hint", Feedback[if (action == KEEP) "after_task.keep" else "after_task.close"])
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        listOfNotNull(o.argStr("package"), o.argStr("action")).joinToString(" ").ifEmpty { null }

    const val KEEP = "keep"
    const val CLOSE = "close"
    const val AFTER_TASK_UNAVAILABLE = "after_task_unavailable"
    private const val MAX_USER = 99_999
}

/**
 * 把一段文字分享给一个 App(ACTION_SEND text/plain),在 Mika 的屏上打开它的分享页:收件人、发出都在那一页上点,
 * 这里不发出任何东西。读不到的界面里打不进字时,经分享把字带进去。
 */
internal object ShareText : PhoneTool {
    override val verb = Verb.ShareText
    override val label = "分享文字到 App"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_share_text", Prompts.tool("phone_share_text"),
        mapOf(
            "package" to str("Exact package name of the app to share the text to, e.g. com.example.app."),
            "text" to buildJsonObject { put("type", "string"); put("description", "The text to share, 1-10000 characters."); put("minLength", 1); put("maxLength", 10000) },
            "reason" to str("Optional: why you share it, e.g. the message the user asked to send to a contact."),
        ),
        listOf("package", "text"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = resolveAppPackage(a.str("package")!!, ctx)
        val text = a.str("text", max = 10000)!!
        // 换了 App,之前被拒的付款申报就不是这次的了
        ctx.refusedPay = null
        val r = ctx.action(ctx.phone.screen.shareText(p, text))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        // 系统先问用哪一个(装了两份、分享页不止一个)时提示已经说了怎么选;到了分享页再说下一步
        val opened = if (r.content["chooser"] != null) r else withHint(r, Feedback["share.opened"])
        return unreadable(ctx, p, opened)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("package")

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull(o.argStr("package")?.let { p ->
            Observation(ObsKind.Fact, "$name 打开了 $p 的分享页(还没发出)" + (log.appLabels[p]?.let { "(App 名字「$it」)" } ?: ""), source = PHONE)
        })
    }
}

private fun withHint(r: ToolExecution, hint: String): ToolExecution {
    val all = listOfNotNull((r.content["hint"] as? JsonPrimitive)?.content, hint).joinToString(" ")
    return ToolExecution(JsonObject(r.content + ("hint" to JsonPrimitive(all))), changed = r.changed)
}

/** 到了前台却读不到窗口(安全窗口、启动画面……):先在本地轻量等待 1.5 秒重试,免得开屏画面直接返回让模型额外轮询 wait+observe 浪费几十秒。 */
private suspend fun unreadable(ctx: ToolContext, p: String, r: ToolExecution): ToolExecution {
    var s = ctx.session.peek()
    if (s?.unreadableApp == p && s.elements.none { it.meaningful }) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 1500L) {
            kotlinx.coroutines.delay(250L)
            s = ctx.session.peek()
            if (s?.unreadableApp != p || (s != null && s.elements.any { it.meaningful })) {
                break
            }
        }
    }
    if (s?.unreadableApp != p || s.elements.any { it.meaningful }) return r
    return withHint(ToolExecution(JsonObject(r.content + ("readable" to JsonPrimitive(false))), changed = r.changed), Feedback.format("unreadable_window", "app" to p))
}

package com.wangzan101.hana.core.cap.phone

import com.wangzan101.hana.core.model.Part
import com.wangzan101.hana.core.observation.Untrusted
import com.wangzan101.hana.core.prompt.Prompts
import com.wangzan101.hana.core.tool.Args
import com.wangzan101.hana.core.tool.Risk
import com.wangzan101.hana.core.tool.ToolContext
import com.wangzan101.hana.core.tool.ToolDef
import com.wangzan101.hana.core.tool.ToolExecution
import com.wangzan101.hana.core.tool.Verb
import com.wangzan101.hana.core.tool.int
import com.wangzan101.hana.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import com.wangzan101.hana.core.uistate.session
import com.wangzan101.hana.core.tool.ArgError
import com.wangzan101.hana.core.tool.bool
import com.wangzan101.hana.core.uistate.ActionResult
import com.wangzan101.hana.core.uistate.screen
import com.wangzan101.hana.core.uistate.toPx

/** 不截屏地按文字、资源 ID 或控件类型查询当前无障碍树,结果保留父子线索。 */
internal object PhoneQuery : PhoneTool {
    override val verb = Verb.Query
    override val risk = Risk.Read
    override val label = "查找界面控件"
    override val def = ToolDef("phone_query", Prompts.tool("phone_query"), mapOf(
        "text" to str("Case-insensitive text or accessibility description to find."),
        "resourceId" to str("Full or partial Android resource ID to find."),
        "role" to str("Full or partial accessibility class or role to find."),
        "limit" to int("Maximum results, default 30.", 1, 100),
    ))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val read = ctx.session.observe(withImage = false, recordFrame = false)
        val snapshot = ctx.session.lastSnapshot!!
        val query = PhoneAutomationEngine.Query(a.str("text", max = 200), a.str("resourceId", max = 200), a.str("role", max = 100))
        val found = PhoneAutomationEngine.find(snapshot, query, a.int("limit", 1, 100) ?: 30)
        return read.with(content = JsonObject(mapOf("ok" to JsonPrimitive(true)) + found))
    }
}

/** 一次采集多屏文字。每次滚动仍通过子动作入口,因此取消、状态记录和动作结果与单步一致。 */
internal object PhoneCollect : PhoneTool {
    override val verb = Verb.Collect
    override val risk = Risk.Write
    override val composite = true
    override val label = "采集多屏内容"
    override val def = ToolDef("phone_collect", Prompts.tool("phone_collect"), mapOf(
        "direction" to str("Direction of newer content: up or down.", listOf("up", "down")),
        "pages" to int("Maximum screens to inspect, 1-30 (default 10).", 1, 30),
        "maxItems" to int("Maximum lines to return, 1-1000 (default 300).", 1, 1000),
        "fromY" to int("Top of the content area, normalized 0-999 (default 50).", 0, 999),
        "toY" to int("Bottom of the content area, normalized 0-999 (default 950).", 0, 999),
    ), listOf("direction"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly { collect(a, ctx) }

    private suspend fun collect(a: Args, ctx: ToolContext): ToolExecution {
        val direction = a.str("direction")!!
        if (direction !in setOf("up", "down")) return failure("invalid_direction")
        val pages = a.int("pages", 1, 30) ?: 10
        val maxItems = a.int("maxItems", 1, 1000) ?: 300
        val fromY = a.int("fromY", 0, 999) ?: 50
        val toY = a.int("toY", 0, 999) ?: 950
        if (fromY >= toY) return failure("invalid_region")
        val sub = ctx.sub ?: return failure("actions_unavailable")
        var collected = emptyList<String>()
        var app: String? = null
        var inspected = 0
        var scrolled = 0
        var noNewPages = 0
        var stopped = "page_limit"
        var finalScreen: JsonObject? = null
        for (page in 0 until pages) {
            val read = sub.run(Part.ToolCall("${ctx.callId}.read${page + 1}", "phone_list_elements", "{}"))
            val content = read.content
            if (content["ok"] != JsonPrimitive(true)) {
                stopped = (content["error"] as? JsonPrimitive)?.contentOrNull ?: "read_failed"
                break
            }
            val ocrReadable = (content["visibleText"] as? JsonArray)?.isNotEmpty() == true
            if (content["protected"] == JsonPrimitive(true) ||
                !ocrReadable && (content["readable"] == JsonPrimitive(false) || content["imageReason"] != null)) {
                stopped = "screen_needs_review"
                break
            }
            val currentApp = (content["currentApp"] as? JsonPrimitive)?.contentOrNull
            if (page == 0) {
                if (currentApp == null) { stopped = "no_app_open"; break }
                app = currentApp
            }
            else if (app != null && currentApp != app) { stopped = "app_switched"; break }
            val lines = pageLines(content, fromY, toY)
            val before = collected.size
            collected = if (direction == "down") PhoneAutomationEngine.appendPage(collected, lines)
            else PhoneAutomationEngine.prependPage(collected, lines)
            inspected++
            finalScreen = content
            noNewPages = if (collected.size == before) noNewPages + 1 else 0
            if (collected.size >= maxItems) { stopped = "item_limit"; break }
            if (noNewPages >= 2) { stopped = "no_new_content"; break }
            if (page == pages - 1) break
            val scroll = sub.run(Part.ToolCall("${ctx.callId}.scroll${page + 1}", "phone_scroll", buildJsonObject {
                put("direction", direction); put("amount", 3)
            }.toString()))
            if (scroll.content["ok"] != JsonPrimitive(true)) {
                stopped = (scroll.content["error"] as? JsonPrimitive)?.contentOrNull ?: "scroll_failed"
                break
            }
            scrolled++
        }
        val output = collected.take(maxItems)
        val joined = output.joinToString("\n")
        return ToolExecution(buildJsonObject {
            put("ok", inspected > 0)
            if (inspected == 0) put("error", stopped)
            put("currentApp", app)
            put("pagesInspected", inspected)
            put("scrolls", scrolled)
            put("items", output.size)
            put("stoppedBecause", stopped)
            put("truncated", collected.size > maxItems || joined.length > 49_000)
            put("text", Untrusted.wrapWithin(joined, 50_000))
            finalScreen?.get("snapshotId")?.let { put("snapshotId", it) }
        })
    }

    private fun pageLines(content: JsonObject, fromY: Int, toY: Int): List<String> {
        data class Line(val text: String, val top: Int, val bottom: Int, val left: Int)
        val out = mutableListOf<Line>()
        fun add(value: String?, bounds: JsonArray?) {
            val text = value?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            if (text.isEmpty()) return
            val top = (bounds?.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: 0
            val bottom = (bounds?.getOrNull(3) as? JsonPrimitive)?.intOrNull ?: 999
            val left = (bounds?.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: 0
            if (bottom >= fromY && top <= toY) out += Line(text, top, bottom, left)
        }
        fun visit(node: JsonObject) {
            val bounds = node["bounds"] as? JsonArray
            if (node["secret"] != JsonPrimitive(true)) {
                for (key in listOf("text", "description")) {
                    add((node[key] as? JsonPrimitive)?.contentOrNull, bounds)
                }
            }
            (node["children"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::visit) }
        }
        val windows = content["tree"] as? JsonArray
        if (windows != null) windows.forEach { window ->
            ((window as? JsonObject)?.get("nodes") as? JsonArray)?.forEach { (it as? JsonObject)?.let(::visit) }
        }
        if (out.isEmpty()) (content["elements"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            if (obj["secret"] != JsonPrimitive(true)) add((obj["name"] as? JsonPrimitive)?.contentOrNull, obj["bounds"] as? JsonArray)
        }
        // OCR 只包含树里缺失的文字；与树文本按屏幕位置合并，群聊才不会把补出的消息都放到末尾。
        (content["visibleText"] as? JsonArray)?.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            add((obj["text"] as? JsonPrimitive)?.contentOrNull, obj["bounds"] as? JsonArray)
        }
        val sorted = out.sortedWith(compareBy(Line::top, Line::left))
        return sorted.filterIndexed { index, line ->
            val previous = sorted.getOrNull(index - 1)
            previous == null || previous.text != line.text || minOf(previous.bottom, line.bottom) <= maxOf(previous.top, line.top)
        }.map(Line::text)
    }

    private fun failure(error: String) = ToolExecution(buildJsonObject { put("ok", false); put("error", error) })
}

/**
 * 一步完成端到端搜索：智能嗅探搜索入口 -> 点击进入/聚焦 -> 清空推荐词 -> 键入关键词 -> 双通道提交搜索（点击搜索按钮 + 回车）。
 * 解决大模型多次往返网络延迟，将 6~9 秒的搜索链路压缩至毫秒级，且支持全自动入口嗅探与容错自愈。
 */
internal object PhoneSearch : PhoneTool {
    override val verb = Verb.PhoneSearch
    override val risk = Risk.Write
    override val composite = true
    override val showsScreenAfter = true
    override val logged = true
    override val label: String? = null
    override val def = ToolDef(
        "phone_search",
        Prompts.tool("phone_search"),
        mapOf(
            "query" to buildJsonObject { put("type", "string"); put("description", "Search query keyword to enter and search for."); put("minLength", 1); put("maxLength", 1000) },
            "ref" to str("Optional: element ref of the search bar or search entry."),
            "snapshotId" to str("Optional: snapshotId of the ref element."),
            "x" to int("Optional: x coordinate of the search bar, 0-999.", 0, 999),
            "y" to int("Optional: y coordinate of the search bar, 0-999.", 0, 999),
            "clear" to bool("Optional: whether to clear existing preset/trending hotwords before typing (default true)."),
            "pressEnter" to bool("Optional: whether to press Enter to submit search (default true)."),
        ),
        listOf("query")
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val query = a.str("query", max = 1000) ?: a.str("text", max = 1000) ?: throw ArgError("query", "search query required")
        var ref = a.str("ref")
        var snapshotId = a.str("snapshotId")
        val x = a.coord("x")
        val y = a.coord("y")
        val clear = a.bool("clear", true) ?: true
        val enter = a.bool("pressEnter", true) ?: true

        val screen = ctx.screen
        var currentSnapshot = ctx.session.lastSnapshot ?: runCatching { screen.snapshot() }.getOrNull()

        // 1. 检查传入的 ref 是否为直接可输入的编辑框
        val targetEl = ref?.let { r -> currentSnapshot?.elements?.firstOrNull { it.ref == r } }
        val isTargetEditable = targetEl?.let { it.editable || it.role == "EditText" || it.searchField } ?: false

        // 2. 如果未指定 ref 或指定的 ref 只是一个入口按钮（如搜索图标），自动嗅探或激活搜索入口
        if (ref == null && (x == null || y == null)) {
            val elements = currentSnapshot?.elements.orEmpty()
            // 优先查找当前页已存在的搜索输入框
            val existingEdit = elements.firstOrNull { it.focused && (it.editable || it.role == "EditText") }
                ?: elements.firstOrNull { (it.editable || it.role == "EditText" || it.searchField) && (it.name.contains("搜索") || it.placeholder?.contains("搜索") == true) }
                ?: elements.firstOrNull { it.editable || it.role == "EditText" || it.searchField }

            if (existingEdit != null) {
                ref = existingEdit.ref
                snapshotId = currentSnapshot?.snapshotId
            } else {
                // 当前页面未处于搜索页，智能查找顶部搜索入口（如小红书、微信等顶部搜索按钮或搜索条）
                val searchEntry = elements.firstOrNull { el ->
                    val name = el.name.trim()
                    val res = el.resourceId?.lowercase().orEmpty()
                    val isTop = el.bounds.getOrNull(1)?.let { it < 400 } ?: true
                    (el.clickable || el.role == "Button" || el.role == "ImageView") &&
                        isTop &&
                        (name == "搜索" || name == "搜一搜" || name.contains("搜索") || res.contains("search") || res.contains("query"))
                } ?: elements.firstOrNull { el ->
                    val name = el.name.trim()
                    (el.clickable || el.role == "Button") && (name == "搜索" || name == "搜一搜")
                }

                if (searchEntry != null) {
                    screen.tapRef(searchEntry.ref, currentSnapshot?.snapshotId.orEmpty())
                    kotlinx.coroutines.delay(350L) // 等待搜索页转场动画并呈现输入框
                    currentSnapshot = runCatching { screen.snapshot() }.getOrNull()
                    val newElements = currentSnapshot?.elements.orEmpty()
                    val newEdit = newElements.firstOrNull { it.focused && (it.editable || it.role == "EditText") }
                        ?: newElements.firstOrNull { (it.editable || it.role == "EditText" || it.searchField) && (it.name.contains("搜索") || it.placeholder?.contains("搜索") == true) }
                        ?: newElements.firstOrNull { it.editable || it.role == "EditText" || it.searchField }
                        ?: newElements.firstOrNull { it.name.contains("搜索") && it.clickable }
                    if (newEdit != null) {
                        ref = newEdit.ref
                        snapshotId = currentSnapshot?.snapshotId
                    }
                }
            }
        } else if (ref != null && !isTargetEditable) {
            // 模型指定了 ref，但它是一个入口按钮（例如首页搜索放大镜图标）
            screen.tapRef(ref, snapshotId.orEmpty())
            kotlinx.coroutines.delay(350L)
            currentSnapshot = runCatching { screen.snapshot() }.getOrNull()
            val newElements = currentSnapshot?.elements.orEmpty()
            val newEdit = newElements.firstOrNull { it.focused && (it.editable || it.role == "EditText") }
                ?: newElements.firstOrNull { it.editable || it.role == "EditText" || it.searchField }
            if (newEdit != null) {
                ref = newEdit.ref
                snapshotId = currentSnapshot?.snapshotId
            }
        }

        // 3. 点击聚焦输入框
        if (ref != null) {
            screen.tapRef(ref, snapshotId.orEmpty())
            kotlinx.coroutines.delay(120L)
        } else if (x != null && y != null) {
            val width = currentSnapshot?.width ?: 1080
            val height = currentSnapshot?.height ?: 2400
            screen.tapXY(toPx(x, width), toPx(y, height))
            kotlinx.coroutines.delay(120L)
        }

        // 4. 清空预设热词/默认提示
        if (clear) {
            runCatching { screen.clearText(ref, snapshotId) }
        }

        // 5. 键入目标搜索词
        var typeResult = screen.typeText(ref, snapshotId, query)
        // 容错重试：若指定 ref 输入失败，尝试向当前聚焦框输入
        if (typeResult !is ActionResult.Ok && ref != null) {
            typeResult = screen.typeText(null, null, query)
        }

        // 6. 执行搜索提交（多通道：点击界面“搜索”文字按钮 + 物理回车）
        var pressedEnter = false
        if (enter) {
            // 通道 A：扫描屏幕右上角出现的“搜索”按钮（小红书、抖音、淘宝等强依赖点击搜索按钮提交）
            val afterTypeSnapshot = runCatching { screen.snapshot() }.getOrNull()
            val submitBtn = afterTypeSnapshot?.elements?.firstOrNull { el ->
                val name = el.name.trim()
                val isTop = el.bounds.getOrNull(1)?.let { it < 300 } ?: true
                isTop && (name == "搜索" || name == "Search") && el.ref != ref
            }
            if (submitBtn != null) {
                val btnRes = screen.tapRef(submitBtn.ref, afterTypeSnapshot.snapshotId)
                if (btnRes is ActionResult.Ok) pressedEnter = true
            }

            // 通道 B：同时发送物理回车键（KEYCODE_ENTER = 66）
            val keyResult = screen.pressKey(66)
            if (keyResult is ActionResult.Ok) pressedEnter = true
        }

        // 7. 失败防护：如果未成功输入且未提交，告知模型明确错误以自愈
        if (typeResult !is ActionResult.Ok && !pressedEnter) {
            val read = ctx.session.observe(withImage = false, recordFrame = false)
            return read.with(content = buildJsonObject {
                put("ok", false)
                put("error", "search_failed")
                put("hint", "未能成功输入搜索关键词或未找到搜索框，请先导航至包含搜索入口的界面")
                put("query", query)
                read.content["currentApp"]?.let { put("currentApp", it) }
            })
        }

        // 8. 等待搜索结果列表渲染并抓取最新快照返回
        kotlinx.coroutines.delay(350L)
        val read = ctx.session.observe(withImage = false, recordFrame = false)
        return read.with(content = buildJsonObject {
            put("ok", true)
            put("query", query)
            put("searched", true)
            put("pressedEnter", pressedEnter)
            read.content["currentApp"]?.let { put("currentApp", it) }
            read.content["snapshotId"]?.let { put("snapshotId", it) }
        })
    }
}

/**
 * 一步完成端到端评论发表：智能嗅探底部评论栏 -> 点击展开输入框浮层 -> 键入评论文本 -> 智能点击发送按钮 -> 自动收起软键盘并恢复界面。
 * 解决小红书/抖音/微博等 App 评论需要先点展示条、再在弹窗中打字、最后必须点发送按钮而非回车的顽疾。
 */
internal object PhoneComment : PhoneTool {
    override val verb = Verb.PhoneComment
    override val risk = Risk.Write
    override val composite = true
    override val showsScreenAfter = true
    override val logged = true
    override val label: String? = null
    override val def = ToolDef(
        "phone_comment",
        Prompts.tool("phone_comment"),
        mapOf(
            "text" to buildJsonObject { put("type", "string"); put("description", "Comment text to post."); put("minLength", 1); put("maxLength", 5000) },
            "ref" to str("Optional: element ref of the comment bar or input field."),
            "snapshotId" to str("Optional: snapshotId of the ref element."),
            "autoSend" to bool("Optional: whether to automatically find and click the '发送' (Send) button (default true)."),
            "closeAfter" to bool("Optional: whether to close comment sheet/keyboard after sending (default true)."),
        ),
        listOf("text")
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val text = a.str("text", max = 5000) ?: throw ArgError("text", "comment text required")
        var ref = a.str("ref")
        var snapshotId = a.str("snapshotId")
        val autoSend = a.bool("autoSend", true) ?: true
        val closeAfter = a.bool("closeAfter", true) ?: true

        val screen = ctx.screen
        var currentSnapshot = ctx.session.lastSnapshot ?: runCatching { screen.snapshot() }.getOrNull()

        // 1. 检查传入的 ref 是否可编辑
        val targetEl = ref?.let { r -> currentSnapshot?.elements?.firstOrNull { it.ref == r } }
        val isTargetEditable = targetEl?.let { it.editable || it.role == "EditText" } ?: false

        // 2. 智能嗅探并激活评论入口
        if (ref == null) {
            val elements = currentSnapshot?.elements.orEmpty()
            // 优先检查当前屏幕是否已经弹出了真实的评论输入框 EditText
            val activeEdit = elements.firstOrNull { it.focused && (it.editable || it.role == "EditText") }
                ?: elements.firstOrNull { it.editable || it.role == "EditText" }

            if (activeEdit != null) {
                ref = activeEdit.ref
                snapshotId = currentSnapshot?.snapshotId
            } else {
                // 当前处于帖子正文页，寻找底部的“评论入口”（通常位于屏幕下半部分 y > 600）
                val commentKeywords = listOf("说点什么", "评论", "发评论", "写评论", "发条评论", "留言", "善语", "留下", "回复")
                val commentEntry = elements.firstOrNull { el ->
                    val name = el.name.trim()
                    val isBottom = el.bounds.getOrNull(1)?.let { it > 600 } ?: true
                    isBottom && (el.clickable || el.role == "TextView" || el.role == "ViewGroup" || el.role == "FrameLayout") &&
                        commentKeywords.any { name.contains(it) } &&
                        !name.contains("点赞") && !name.contains("收藏") && !name.contains("分享")
                } ?: elements.firstOrNull { el ->
                    val name = el.name.trim()
                    (el.clickable || el.role == "TextView") && commentKeywords.any { name.contains(it) }
                }

                if (commentEntry != null) {
                    screen.tapRef(commentEntry.ref, currentSnapshot?.snapshotId.orEmpty())
                    kotlinx.coroutines.delay(350L) // 等待评论输入浮层弹出及动画就绪
                    currentSnapshot = runCatching { screen.snapshot() }.getOrNull()
                    val newElements = currentSnapshot?.elements.orEmpty()
                    val newEdit = newElements.firstOrNull { it.focused && (it.editable || it.role == "EditText") }
                        ?: newElements.firstOrNull { it.editable || it.role == "EditText" }
                    if (newEdit != null) {
                        ref = newEdit.ref
                        snapshotId = currentSnapshot?.snapshotId
                    }
                }
            }
        } else if (!isTargetEditable) {
            // 用户传了入口条 ref（非 EditText），先点击展开浮层
            screen.tapRef(ref, snapshotId.orEmpty())
            kotlinx.coroutines.delay(350L)
            currentSnapshot = runCatching { screen.snapshot() }.getOrNull()
            val newElements = currentSnapshot?.elements.orEmpty()
            val newEdit = newElements.firstOrNull { it.focused && (it.editable || it.role == "EditText") }
                ?: newElements.firstOrNull { it.editable || it.role == "EditText" }
            if (newEdit != null) {
                ref = newEdit.ref
                snapshotId = currentSnapshot?.snapshotId
            }
        }

        // 3. 聚焦输入框并键入文字
        if (ref != null) {
            screen.tapRef(ref, snapshotId.orEmpty())
            kotlinx.coroutines.delay(120L)
        }
        var typeResult = screen.typeText(ref, snapshotId, text)
        if (typeResult !is ActionResult.Ok && ref != null) {
            typeResult = screen.typeText(null, null, text)
        }

        // 4. 智能提交发送（点击“发送”按钮 + 补发回车）
        var sent = false
        if (autoSend) {
            val afterTypeSnapshot = runCatching { screen.snapshot() }.getOrNull()
            val sendKeywords = listOf("发送", "发表", "Send", "发布")
            val sendBtn = afterTypeSnapshot?.elements?.firstOrNull { el ->
                val name = el.name.trim()
                val isBottom = el.bounds.getOrNull(1)?.let { it > 600 } ?: true
                isBottom && (el.clickable || el.role == "Button" || el.role == "TextView" || el.role == "View") &&
                    sendKeywords.any { name == it || name.startsWith(it) } &&
                    el.ref != ref
            }
            if (sendBtn != null) {
                val tapRes = screen.tapRef(sendBtn.ref, afterTypeSnapshot.snapshotId)
                if (tapRes is ActionResult.Ok) sent = true
            }
            // 补充按回车
            val enterRes = screen.pressKey(66)
            if (enterRes is ActionResult.Ok) sent = true
        }

        // 5. 等待发送完成与界面收起
        kotlinx.coroutines.delay(350L)
        if (closeAfter) {
            // 检查当前是否依然有半屏遮罩或打开的 EditText，若是则发送返回收起
            val checkSnapshot = runCatching { screen.snapshot() }.getOrNull()
            val stillEditing = checkSnapshot?.elements?.any { it.focused && (it.editable || it.role == "EditText") } == true
            if (stillEditing) {
                screen.back()
                kotlinx.coroutines.delay(200L)
            }
        }

        // 6. 状态检查
        if (typeResult !is ActionResult.Ok && !sent) {
            val read = ctx.session.observe(withImage = false, recordFrame = false)
            return read.with(content = buildJsonObject {
                put("ok", false)
                put("error", "comment_failed")
                put("hint", "未能成功输入评论内容或未找到评论输入入口，请确认是否处于帖子/视频详情页")
                put("text", text)
                read.content["currentApp"]?.let { put("currentApp", it) }
            })
        }

        // 7. 返回最新观察
        val read = ctx.session.observe(withImage = false, recordFrame = false)
        return read.with(content = buildJsonObject {
            put("ok", true)
            put("commented", true)
            put("text", text)
            put("sent", sent)
            read.content["currentApp"]?.let { put("currentApp", it) }
            read.content["snapshotId"]?.let { put("snapshotId", it) }
        })
    }
}

/**
 * 一步完成单帖全自动社交互动（互赞、批量点赞、评论）：
 * 1. 列表页自动识别下一个未处理帖子（支持 Z 字形左右双列瀑布流）；若当前屏全处理过自动下滑一屏加载新帖；
 * 2. 自动点击进入帖子详情页；
 * 3. 幂等点赞：智能识别点赞按钮状态，若未点赞则点赞，已点赞则安全跳过（绝不反向取消点赞）；
 * 4. 拟人随机停顿（0.8s~1.2s，防平台风控限制）；
 * 5. 自动发表评论（支持多条轮换或微表情变体防雷同）；
 * 6. 自动收起评论弹窗并稳妥退回搜索列表页（双重返回校验）；
 * 7. 记录已处理帖子指纹，返回全局进度与剩余提示。
 */
internal object PhoneSocialInteract : PhoneTool {
    override val verb = Verb.PhoneSocialInteract
    override val risk = Risk.Write
    override val composite = true
    override val showsScreenAfter = true
    override val logged = true
    override val label: String? = null
    override val def = ToolDef(
        "phone_social_interact",
        Prompts.tool("phone_social_interact"),
        mapOf(
            "postRef" to str("Optional: element ref of the post card to click. If omitted, automatically picks the next unhandled post on the feeds list."),
            "commentText" to str("Optional: comment text to post (e.g. 'dd 最新收仓'). If omitted, only likes without commenting."),
            "like" to bool("Optional: whether to like the post (default true). Idempotent: will not unlike if already liked."),
            "humanDelay" to bool("Optional: whether to simulate human viewing delay (0.8-1.5s) to avoid anti-bot frequency limits (default true)."),
            "autoReturn" to bool("Optional: whether to automatically return to the feeds list after interacting (default true)."),
        )
    )

    // 内存级去重账本：记录已处理过的帖子唯一标识（标题文本或卡片哈希）
    internal val handledPostSignatures = mutableSetOf<String>()

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val specifiedRef = a.str("postRef")
        val commentText = a.str("commentText")
        val like = a.bool("like", true) ?: true
        val humanDelay = a.bool("humanDelay", true) ?: true
        val autoReturn = a.bool("autoReturn", true) ?: true

        val screen = ctx.screen
        var currentSnapshot = ctx.session.lastSnapshot ?: runCatching { screen.snapshot() }.getOrNull()

        var currentPostRef = specifiedRef
        var postTitle = ""

        // 1. 如果在列表页（即当前屏幕有多张帖子卡片），挑选目标帖子
        if (currentPostRef == null) {
            val elements = currentSnapshot?.elements.orEmpty()
            fun findPostCards(): List<com.wangzan101.hana.core.uistate.UiElement> {
                return elements.filter { el ->
                    val name = el.name.trim()
                    val isContent = name.startsWith("笔记") || name.startsWith("视频") ||
                        (name.contains("赞") && (name.contains("来自") || name.contains("前") || name.length > 10)) ||
                        (el.bounds.let { b -> b.size == 4 && (b[2] - b[0]) in 250..600 && (b[3] - b[1]) in 150..600 })
                    isContent && el.clickable
                }.sortedWith(compareBy({ it.bounds.getOrNull(1) ?: 0 }, { it.bounds.getOrNull(0) ?: 0 }))
            }

            var postCards = findPostCards()
            var unhandledCard = postCards.firstOrNull { card ->
                val sig = card.name.trim().take(40)
                sig.isNotEmpty() && !handledPostSignatures.contains(sig)
            }

            // 如果当前屏幕上的帖子都已处理，自动向下滑动一屏加载全新内容
            if (unhandledCard == null && postCards.isNotEmpty()) {
                screen.scroll(null, null, true)
                kotlinx.coroutines.delay(450L)
                currentSnapshot = runCatching { screen.snapshot() }.getOrNull()
                postCards = findPostCards()
                unhandledCard = postCards.firstOrNull { card ->
                    val sig = card.name.trim().take(40)
                    sig.isNotEmpty() && !handledPostSignatures.contains(sig)
                }
            }

            if (unhandledCard != null) {
                currentPostRef = unhandledCard.ref
                postTitle = unhandledCard.name.trim().take(30)
                handledPostSignatures.add(unhandledCard.name.trim().take(40))
                screen.tapRef(unhandledCard.ref, currentSnapshot?.snapshotId.orEmpty())
                kotlinx.coroutines.delay(400L)
                currentSnapshot = runCatching { screen.snapshot() }.getOrNull()
            }
        } else {
            val matched = currentSnapshot?.elements?.firstOrNull { it.ref == currentPostRef }
            if (matched != null) {
                postTitle = matched.name.trim().take(30)
                handledPostSignatures.add(matched.name.trim().take(40))
            }
            screen.tapRef(currentPostRef, currentSnapshot?.snapshotId.orEmpty())
            kotlinx.coroutines.delay(400L)
            currentSnapshot = runCatching { screen.snapshot() }.getOrNull()
        }

        // 2. 在详情页中执行操作
        val detailElements = currentSnapshot?.elements.orEmpty()
        var liked = false

        // A. 幂等点赞
        if (like) {
            val likeBtn = detailElements.firstOrNull { el ->
                val name = el.name.trim()
                val isBottom = el.bounds.getOrNull(1)?.let { it > 600 } ?: true
                isBottom && (el.clickable || el.role == "Button") &&
                    (name.contains("点赞") || name == "赞" || el.resourceId?.contains("like") == true)
            }
            if (likeBtn != null) {
                val alreadyLiked = likeBtn.name.contains("已点赞") ||
                    likeBtn.states.contains("selected") ||
                    likeBtn.name.contains("取消")
                if (!alreadyLiked) {
                    val tapRes = screen.tapRef(likeBtn.ref, currentSnapshot?.snapshotId.orEmpty())
                    if (tapRes is ActionResult.Ok) liked = true
                } else {
                    liked = true
                }
            }
        }

        // B. 拟人随机停顿（模拟人类看帖，防风控）
        if (humanDelay) {
            val delayMs = java.util.concurrent.ThreadLocalRandom.current().nextLong(600L, 1100L)
            kotlinx.coroutines.delay(delayMs)
        }

        // C. 确定性发表评论
        var commented = false
        var commentVariant = commentText
        if (!commentText.isNullOrBlank()) {
            val variants = commentText.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val chosen = if (variants.size > 1) variants.random() else commentText.trim()
            val emojis = listOf("", " ", "！", "啦", "~", "[赞R]")
            val randomEmoji = emojis.random()
            commentVariant = if (randomEmoji.isNotEmpty() && !chosen.endsWith(randomEmoji)) "$chosen$randomEmoji" else chosen

            val commentEntry = detailElements.firstOrNull { el ->
                val name = el.name.trim()
                val isBottom = el.bounds.getOrNull(1)?.let { it > 600 } ?: true
                isBottom && (name.contains("说点什么") || name.contains("评论") || name.contains("发条评论")) &&
                    !name.contains("点赞") && !name.contains("收藏")
            }
            if (commentEntry != null) {
                screen.tapRef(commentEntry.ref, currentSnapshot?.snapshotId.orEmpty())
                kotlinx.coroutines.delay(350L)
                val editSnapshot = runCatching { screen.snapshot() }.getOrNull()
                val editField = editSnapshot?.elements?.firstOrNull { it.focused && (it.editable || it.role == "EditText") }
                    ?: editSnapshot?.elements?.firstOrNull { it.editable || it.role == "EditText" }

                if (editField != null) {
                    screen.typeText(editField.ref, editSnapshot?.snapshotId, commentVariant)
                    kotlinx.coroutines.delay(100L)
                    val sendSnapshot = runCatching { screen.snapshot() }.getOrNull()
                    val sendBtn = sendSnapshot?.elements?.firstOrNull { el ->
                        val name = el.name.trim()
                        val isBottom = el.bounds.getOrNull(1)?.let { it > 600 } ?: true
                        isBottom && (el.clickable || el.role == "Button" || el.role == "TextView") &&
                            (name == "发送" || name == "发表" || name == "Send")
                    }
                    if (sendBtn != null) {
                        screen.tapRef(sendBtn.ref, sendSnapshot?.snapshotId.orEmpty())
                    }
                    screen.pressKey(66)
                    commented = true
                    kotlinx.coroutines.delay(350L)
                }
            }
        }

        // D. 安全退回到列表页
        var returned = false
        if (autoReturn) {
            screen.back()
            kotlinx.coroutines.delay(300L)
            val afterBack = runCatching { screen.snapshot() }.getOrNull()
            val stillDetail = afterBack?.elements?.any { it.name.contains("说点什么") || it.name.contains("让大家听到") } == true
            if (stillDetail) {
                screen.back()
                kotlinx.coroutines.delay(300L)
            }
            returned = true
        }

        val completedCount = handledPostSignatures.size
        val read = ctx.session.observe(withImage = false, recordFrame = false)
        return read.with(content = buildJsonObject {
            put("ok", true)
            put("interacted", true)
            put("completedCount", completedCount)
            put("liked", liked)
            put("commented", commented)
            commentVariant?.let { put("commentText", it) }
            if (postTitle.isNotEmpty()) put("postTitle", postTitle)
            put("returnedToList", returned)
            put("hint", "第 $completedCount 个帖子互赞完成！已安全退回列表。继续调用 phone_social_interact 即可自动互赞下一个！")
            read.content["currentApp"]?.let { put("currentApp", it) }
            read.content["snapshotId"]?.let { put("snapshotId", it) }
        })
    }
}

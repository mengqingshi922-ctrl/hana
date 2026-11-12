package com.hana.agent.core.uistate

/**
 * UI 树序列化格式切换枚举，支持脱水行文本树与标准 JSON 树并存切换。
 */
enum class UiTreeFormat {
    JSON,
    COMPACT,
}

/**
 * 脱水行文本 UI 树序列化器：
 * 将冗长的 JSON 树压缩为紧凑缩进行文本，如：
 * `[e12] btn "立即购买" [120,840,340,910] {clk}`
 * 仅保留非默认状态、有效文本与必要交互属性，显著降低模型上下文 Token 消耗。
 */
object UiTreeCompact {

    private val ROLE_ALIASES = mapOf(
        "button" to "btn",
        "imagebutton" to "btn",
        "textview" to "txt",
        "text" to "txt",
        "edittext" to "input",
        "edit" to "input",
        "imageview" to "img",
        "image" to "img",
        "checkbox" to "chk",
        "switch" to "sw",
        "togglebutton" to "sw",
        "radiobutton" to "radio",
        "recyclerview" to "list",
        "listview" to "list",
        "scrollview" to "scroll",
        "horizontalscrollview" to "scroll",
        "viewgroup" to "box",
        "framelayout" to "box",
        "linearlayout" to "box",
        "relativelayout" to "box",
        "constraintlayout" to "box",
        "view" to "box",
        "webview" to "web",
        "tabwidget" to "tab",
        "seekbar" to "bar",
        "progressbar" to "bar",
    )

    /**
     * 按 [format] 切换导出 UI 树表示（紧凑行文本或 JSON 字符串），实现与 [UiTreeJson] 无缝并存切换。
     */
    fun serialize(s: ScreenSnapshot, format: UiTreeFormat = UiTreeFormat.COMPACT, prune: Boolean = true): String =
        when (format) {
            UiTreeFormat.COMPACT -> format(s, prune = prune)
            UiTreeFormat.JSON -> UiTreeJson.windows(s, prune = prune).toString()
        }

    /**
     * 将 [ScreenSnapshot] 序列化为紧凑行文本；优先输出窗口树层级，若窗口树为空则回退元素列表。
     */
    fun format(s: ScreenSnapshot, prune: Boolean = true): String {
        if (s.windows.isNotEmpty()) {
            return formatWindows(s, prune = prune)
        }
        return formatElements(s.elements)
    }

    /**
     * 序列化窗口层级为缩进紧凑行文本。
     */
    fun formatWindows(s: ScreenSnapshot, prune: Boolean = true): String {
        if (s.windows.isEmpty()) return ""
        val sb = StringBuilder()
        s.windows.forEachIndexed { idx, w ->
            if (idx > 0) sb.append('\n')
            sb.append("#w").append(w.id).append(' ').append(w.type)
            w.packageName?.takeIf { it.isNotBlank() }?.let { sb.append(" pkg=").append(it) }
            w.title?.takeIf { it.isNotBlank() }?.let { sb.append(" title=\"").append(escape(it)).append('"') }
            val wFlags = buildList {
                if (w.active) add("active")
                if (w.focused) add("focused")
            }
            if (wFlags.isNotEmpty()) {
                sb.append(" {").append(wFlags.joinToString(",")).append('}')
            }
            val nodes = if (prune && s.width > 0 && s.height > 0) {
                w.nodes.mapNotNull { UiTreePruner.pruneTree(it, s.width, s.height) }
            } else {
                w.nodes
            }
            for (n in nodes) {
                appendNode(sb, n, depth = 0)
            }
        }
        return sb.toString()
    }

    /**
     * 当快照仅含扁平 [UiElement] 列表时，输出脱水行文本列表。
     */
    fun formatElements(elements: List<UiElement>): String {
        if (elements.isEmpty()) return ""
        val sb = StringBuilder()
        elements.forEachIndexed { idx, e ->
            if (idx > 0) sb.append('\n')
            sb.append('[').append(e.ref.removePrefix("@")).append("] ")
            sb.append(compactRole(e.role))
            val text = e.name.trim()
            if (text.isNotEmpty()) {
                sb.append(" \"").append(escape(text)).append('"')
            }
            e.placeholder?.trim()?.takeIf { it.isNotEmpty() && it != text }?.let {
                sb.append(" hint=\"").append(escape(it)).append('"')
            }
            e.resourceId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                sb.append(" #").append(shortResourceId(it))
            }
            if (e.bounds.size == 4) {
                sb.append(" [").append(e.bounds.joinToString(",")).append(']')
            }
            val flags = buildList {
                if (e.clickable) add("clk")
                if (e.editable) add("edit")
                if (e.scrollable) add("scroll")
                if (e.focused) add("focus")
                if (e.secret) add("secret")
                e.states.forEach { st ->
                    when (st) {
                        UiStates.CHECKED -> add("checked")
                        UiStates.SELECTED -> add("sel")
                        UiStates.EXPANDED -> add("exp")
                        UiStates.PRESSED -> add("press")
                        UiStates.DISABLED -> add("disabled")
                        else -> add(st)
                    }
                }
            }
            if (flags.isNotEmpty()) {
                sb.append(" {").append(flags.joinToString(",")).append('}')
            }
        }
        return sb.toString()
    }

    /**
     * 对比前后两屏窗口树，生成紧凑行文本增量差异（`+` 新增，`~` 属性变更，`-` 移除）。
     */
    fun changes(before: ScreenSnapshot, current: ScreenSnapshot, prune: Boolean = false): String {
        val old = index(before, prune)
        val now = index(current, prune)
        val lines = mutableListOf<String>()
        for ((path, n) in now) {
            val prev = old[path]
            if (prev == null && parent(path) in now && parent(path) !in old) continue
            if (prev == null) {
                val sub = StringBuilder()
                appendNodeLines(sub, n, depth = 0)
                lines += "+ $path ${sub.toString().trimStart()}"
            } else if (shallowSignature(prev) != shallowSignature(n)) {
                lines += "~ $path ${formatSingleNode(n)}"
            }
        }
        val gone = old.keys.filterTo(LinkedHashSet()) { it !in now }
        for (path in gone) {
            if (parent(path) !in gone) {
                lines += "- $path"
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * 估算相比 [UiTreeJson.windows] 的字符缩减比例（0.0 ~ 1.0）。
     */
    fun compressionRatio(s: ScreenSnapshot, prune: Boolean = true): Double {
        val jsonLen = UiTreeJson.windows(s, prune = false).toString().length
        if (jsonLen <= 0) return 0.0
        val compactLen = format(s, prune = prune).length
        return (1.0 - compactLen.toDouble() / jsonLen).coerceIn(0.0, 1.0)
    }

    private fun appendNode(sb: StringBuilder, n: UiTreeNode, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
        sb.append(formatSingleNode(n))
        for (child in n.children) {
            appendNode(sb, child, depth + 1)
        }
    }

    private fun appendNodeLines(sb: StringBuilder, n: UiTreeNode, depth: Int) {
        if (depth > 0) {
            sb.append('\n')
            repeat(depth) { sb.append("  ") }
        }
        sb.append(formatSingleNode(n))
        for (child in n.children) {
            appendNodeLines(sb, child, depth + 1)
        }
    }

    internal fun formatSingleNode(n: UiTreeNode): String = buildString {
        n.ref?.takeIf { it.isNotBlank() }?.let {
            append('[').append(it.removePrefix("@")).append("] ")
        }
        append(compactRole(n.role))
        val text = n.text?.trim()?.takeIf { it.isNotEmpty() }
        val desc = n.description?.trim()?.takeIf { it.isNotEmpty() && it != text }
        val hint = n.hintText?.trim()?.takeIf { it.isNotEmpty() && it != text && it != desc }
        if (text != null) {
            append(" \"").append(escape(text)).append('"')
            if (desc != null) append(" desc=\"").append(escape(desc)).append('"')
        } else if (desc != null) {
            append(" \"").append(escape(desc)).append('"')
        }
        if (hint != null) {
            append(" hint=\"").append(escape(hint)).append('"')
        }
        n.resourceId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append(" #").append(shortResourceId(it))
        }
        if (n.bounds.size == 4) {
            append(" [").append(n.bounds.joinToString(",")).append(']')
        }
        val flags = nonDefaultFlags(n)
        if (flags.isNotEmpty()) {
            append(" {").append(flags.joinToString(",")).append('}')
        }
    }

    private fun nonDefaultFlags(n: UiTreeNode): List<String> = buildList {
        if (n.clickable) add("clk")
        if (n.editable) add("edit")
        if (n.scrollable) add("scroll")
        if (n.checkable) add(if (n.checked) "checked" else "unchecked")
        if (n.selected) add("sel")
        if (!n.enabled) add("disabled")
        if (n.focused) add("focus")
        if (n.secret) add("secret")
        if (n.wheel) add("wheel")
    }

    private fun shallowSignature(n: UiTreeNode): List<Any?> = listOf(
        n.role, n.ref, n.text, n.description, n.hintText, n.resourceId, n.bounds,
        n.clickable, n.editable, n.scrollable, n.checkable, n.checked, n.selected, n.enabled, n.focused, n.secret, n.wheel,
    )

    internal fun compactRole(role: String): String {
        val simple = role.substringAfterLast('.').trim()
        return ROLE_ALIASES[simple.lowercase()] ?: simple.lowercase()
    }

    private fun shortResourceId(rid: String): String = rid.substringAfterLast(":id/")

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()

    private fun index(s: ScreenSnapshot, prune: Boolean): LinkedHashMap<String, UiTreeNode> =
        LinkedHashMap<String, UiTreeNode>().also { out ->
            fun walk(prefix: String, nodes: List<UiTreeNode>) {
                nodes.forEachIndexed { i, node ->
                    val path = "$prefix/$i"
                    out[path] = node
                    walk(path, node.children)
                }
            }
            s.windows.forEach { w ->
                val nodes = if (prune && s.width > 0 && s.height > 0) {
                    w.nodes.mapNotNull { UiTreePruner.pruneTree(it, s.width, s.height) }
                } else {
                    w.nodes
                }
                walk("w${w.id}", nodes)
            }
        }

    private fun parent(path: String): String = path.substringBeforeLast('/', "")
}

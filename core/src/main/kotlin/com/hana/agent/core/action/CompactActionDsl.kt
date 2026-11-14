package com.hana.agent.core.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * AgentCPM-GUI 超紧凑动作别名 DSL 解析与反向压缩编码器。
 *
 * 支持单条指令或由分号 `;` 或换行符 `\n` 串联的多步宏动作，例如：
 * - `t(e3)` / `tap(e3)` -> `phone_tap(ref="e3")`
 * - `t(100, 200)` -> `phone_tap(x=100, y=200)`
 * - `tp(e3, "hello")` / `type(e3, "hello")` -> `phone_type(ref="e3", text="hello")`
 * - `tp("hello")` -> `phone_type(text="hello")`
 * - `sw(500,1400,500,400)` / `swipe(...)` -> `phone_swipe(x1=500, y1=1400, x2=500, y2=400)`
 * - `bk()` / `back()` -> `phone_back()`
 * - `hm()` / `home()` -> `phone_home()`
 * - `wt(800)` / `wait(800)` -> `wait(ms=800)`
 */
object CompactActionDsl {

    /**
     * 将极简 DSL 字符串（或短 JSON 数组）解析为标准宏动作步骤列表 [List<MacroStep>]。
     */
    fun parseBatch(dsl: String): List<MacroStep> {
        val trimmed = dsl.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("[")) {
            val jsonSteps = runCatching { parseJsonBatch(trimmed) }.getOrNull()
            if (jsonSteps != null) return jsonSteps
        }

        val commands = splitCommands(trimmed)
        return commands.mapNotNull { parseSingleCommand(it) }
    }

    /**
     * 将标准宏动作步骤列表反向压缩编码为极简紧凑 DSL 字符串。
     */
    fun encodeSteps(steps: List<MacroStep>): String {
        return steps.joinToString("; ") { encodeSingleStep(it) }
    }

    private fun parseJsonBatch(jsonStr: String): List<MacroStep>? {
        val element = runCatching { Json.parseToJsonElement(jsonStr) }.getOrNull() as? JsonArray ?: return null
        return element.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val tool = obj["tool"]?.jsonPrimitive?.contentOrNull
                ?: obj["t"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val args = (obj["args"] as? JsonObject) ?: (obj["a"] as? JsonObject) ?: JsonObject(emptyMap())
            MacroStep(tool = tool, args = args)
        }
    }

    /**
     * 按照分号 `;` 或换行符 `\n` 切分命令，且忽略双引号或单引号内部的分隔符。
     */
    private fun splitCommands(dsl: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inDoubleQuotes = false
        var inSingleQuotes = false
        var escape = false

        for (ch in dsl) {
            if (escape) {
                current.append(ch)
                escape = false
                continue
            }
            if (ch == '\\') {
                current.append(ch)
                escape = true
                continue
            }
            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes
                current.append(ch)
                continue
            }
            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes
                current.append(ch)
                continue
            }

            if ((ch == ';' || ch == '\n') && !inDoubleQuotes && !inSingleQuotes) {
                val cmd = current.toString().trim()
                if (cmd.isNotEmpty()) {
                    result.add(cmd)
                }
                current.clear()
            } else {
                current.append(ch)
            }
        }
        val last = current.toString().trim()
        if (last.isNotEmpty()) {
            result.add(last)
        }
        return result
    }

    /**
     * 切分括号内部由逗号 `,` 分隔的参数列表，忽略引号内部的逗号。
     */
    private fun splitArgs(argsStr: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inDoubleQuotes = false
        var inSingleQuotes = false
        var escape = false

        for (ch in argsStr) {
            if (escape) {
                current.append(ch)
                escape = false
                continue
            }
            if (ch == '\\') {
                current.append(ch)
                escape = true
                continue
            }
            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes
                current.append(ch)
                continue
            }
            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes
                current.append(ch)
                continue
            }

            if (ch == ',' && !inDoubleQuotes && !inSingleQuotes) {
                args.add(current.toString().trim())
                current.clear()
            } else {
                current.append(ch)
            }
        }
        val last = current.toString().trim()
        if (last.isNotEmpty()) {
            args.add(last)
        }
        return args
    }

    private fun stripQuotes(token: String): String {
        val t = token.trim()
        if ((t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) ||
            (t.startsWith("'") && t.endsWith("'") && t.length >= 2)
        ) {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
        }
        return t
    }

    /**
     * 解析单个 DSL 指令。
     */
    private fun parseSingleCommand(cmd: String): MacroStep? {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return null

        val openParen = trimmed.indexOf('(')
        val closeParen = trimmed.lastIndexOf(')')

        val fnName: String
        val rawArgsList: List<String>

        if (openParen != -1 && closeParen != -1 && closeParen > openParen) {
            fnName = trimmed.substring(0, openParen).trim().lowercase()
            val inside = trimmed.substring(openParen + 1, closeParen).trim()
            rawArgsList = if (inside.isEmpty()) emptyList() else splitArgs(inside)
        } else {
            fnName = trimmed.lowercase()
            rawArgsList = emptyList()
        }

        val cleanArgs = rawArgsList.map { stripQuotes(it) }

        return when (fnName) {
            "t", "tap", "phone_tap" -> {
                when {
                    cleanArgs.size >= 2 && cleanArgs[0].toIntOrNull() != null && cleanArgs[1].toIntOrNull() != null -> {
                        MacroStep(
                            tool = "phone_tap",
                            args = buildJsonObject {
                                put("x", cleanArgs[0].toInt())
                                put("y", cleanArgs[1].toInt())
                            }
                        )
                    }
                    cleanArgs.isNotEmpty() -> {
                        MacroStep(
                            tool = "phone_tap",
                            args = buildJsonObject {
                                put("ref", cleanArgs[0])
                            }
                        )
                    }
                    else -> MacroStep(tool = "phone_tap", args = JsonObject(emptyMap()))
                }
            }

            "tp", "type", "phone_type", "phone_type_text" -> {
                when {
                    cleanArgs.size >= 2 -> {
                        MacroStep(
                            tool = "phone_type",
                            args = buildJsonObject {
                                put("ref", cleanArgs[0])
                                put("text", cleanArgs[1])
                            }
                        )
                    }
                    cleanArgs.size == 1 -> {
                        MacroStep(
                            tool = "phone_type",
                            args = buildJsonObject {
                                put("text", cleanArgs[0])
                            }
                        )
                    }
                    else -> MacroStep(tool = "phone_type", args = JsonObject(emptyMap()))
                }
            }

            "sw", "swipe", "phone_swipe" -> {
                if (cleanArgs.size >= 4) {
                    val x1 = cleanArgs[0].toIntOrNull() ?: 0
                    val y1 = cleanArgs[1].toIntOrNull() ?: 0
                    val x2 = cleanArgs[2].toIntOrNull() ?: 0
                    val y2 = cleanArgs[3].toIntOrNull() ?: 0
                    MacroStep(
                        tool = "phone_swipe",
                        args = buildJsonObject {
                            put("x1", x1)
                            put("y1", y1)
                            put("x2", x2)
                            put("y2", y2)
                        }
                    )
                } else {
                    MacroStep(tool = "phone_swipe", args = JsonObject(emptyMap()))
                }
            }

            "bk", "back", "phone_back" -> {
                MacroStep(tool = "phone_back", args = JsonObject(emptyMap()))
            }

            "hm", "home", "phone_home" -> {
                MacroStep(tool = "phone_home", args = JsonObject(emptyMap()))
            }

            "wt", "wait", "phone_wait" -> {
                val ms = cleanArgs.firstOrNull()?.toLongOrNull() ?: 500L
                MacroStep(
                    tool = "wait",
                    args = buildJsonObject {
                        put("ms", ms)
                    }
                )
            }

            else -> {
                // 通用自定义命令兜底
                MacroStep(
                    tool = fnName,
                    args = buildJsonObject {
                        cleanArgs.forEachIndexed { idx, arg ->
                            put("arg$idx", arg)
                        }
                    }
                )
            }
        }
    }

    private fun escapeStr(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /**
     * 将单个步骤反向编码为紧凑 DSL。
     */
    private fun encodeSingleStep(step: MacroStep): String {
        return when (step.tool) {
            "phone_tap" -> {
                val ref = step.args["ref"]?.jsonPrimitive?.contentOrNull
                val x = step.args["x"]?.jsonPrimitive?.intOrNull
                val y = step.args["y"]?.jsonPrimitive?.intOrNull
                when {
                    ref != null -> "t($ref)"
                    x != null && y != null -> "t($x,$y)"
                    else -> "t()"
                }
            }

            "phone_type", "phone_type_text" -> {
                val ref = step.args["ref"]?.jsonPrimitive?.contentOrNull
                val text = step.args["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val escaped = escapeStr(text)
                if (ref != null) {
                    "tp($ref, \"$escaped\")"
                } else {
                    "tp(\"$escaped\")"
                }
            }

            "phone_swipe" -> {
                val x1 = step.args["x1"]?.jsonPrimitive?.intOrNull ?: 0
                val y1 = step.args["y1"]?.jsonPrimitive?.intOrNull ?: 0
                val x2 = step.args["x2"]?.jsonPrimitive?.intOrNull ?: 0
                val y2 = step.args["y2"]?.jsonPrimitive?.intOrNull ?: 0
                "sw($x1,$y1,$x2,$y2)"
            }

            "phone_back" -> "bk()"
            "phone_home" -> "hm()"

            "wait", "phone_wait" -> {
                val ms = step.args["ms"]?.jsonPrimitive?.longOrNull
                    ?: step.args["timeoutMs"]?.jsonPrimitive?.longOrNull
                    ?: 500L
                "wt($ms)"
            }

            else -> "${step.tool}()"
        }
    }
}

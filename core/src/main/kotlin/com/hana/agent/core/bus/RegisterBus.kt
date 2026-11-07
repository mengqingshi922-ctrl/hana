package com.hana.agent.core.bus

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 跨平面零拷贝语义数据总线 (Cross-Surface Zero-Copy Register Bus)
 *
 * 工业级 Mobile/GUI Agent 的关键组件 (对标 OSWorld / UFO2 数据总线设计)：
 * 在手机虚拟屏 (Phone)、无头浏览器 (Web)、沙箱容器 (Sandbox) 之间搬运大文本或结构化数据时，
 * 绝不大费周章地让云端大模型通过 Output Token 逐字逐句重吐一遍 (浪费 Token、延迟极高且容易截断产生幻觉)，
 * 而是直接将提取/收集的数据写入寄存器 ($reg:id)，在打字、填表或沙箱执行时通过寄存器占位符 0 拷贝展开。
 */
object RegisterBus {
    private const val MAX_REGISTERS = 16
    private const val MAX_CHAR_PER_REGISTER = 12000
    const val PREFIX = "\u0024reg:"

    private val registers = LinkedHashMap<String, String>()
    private val REG_REF_REGEX = Regex("""\x24reg:([a-zA-Z0-9_-]+)""")

    @Synchronized
    fun clear() {
        registers.clear()
    }

    /**
     * 将数据存入指定寄存器键 (如 "$reg:latest", "$reg:step3")
     */
    @Synchronized
    fun put(key: String, value: String) {
        if (value.isBlank()) return
        val normalizedKey = if (key.startsWith(PREFIX)) key else (PREFIX + key)
        if (registers.size >= MAX_REGISTERS) {
            registers.keys.firstOrNull()?.let { registers.remove(it) }
        }
        registers[normalizedKey] = value.take(MAX_CHAR_PER_REGISTER)
    }

    /**
     * 读取指定寄存器键对应的值
     */
    @Synchronized
    fun get(key: String): String? {
        val normalizedKey = if (key.startsWith(PREFIX)) key else (PREFIX + key)
        return registers[normalizedKey]
    }

    /**
     * 解析文本中的所有寄存器引用占位符 (如 "$reg:latest" 或 "标题为: $reg:step2")，
     * 若命中则替换为真实内容；若未命中则保留原样。
     */
    @Synchronized
    fun resolve(text: String): String {
        if (!text.contains(PREFIX)) return text
        return REG_REF_REGEX.replace(text) { matchResult ->
            val regKey = matchResult.value
            registers[regKey] ?: matchResult.value
        }
    }

    /**
     * 自动从工具执行结果 (JsonObject) 中挖掘有价值的长文本并自动存入寄存器
     */
    @Synchronized
    fun captureFromToolContent(content: JsonObject, step: Int) {
        val candidate = extractTextCandidate(content) ?: return
        if (candidate.length >= 8) {
            put(PREFIX + "step$step", candidate)
            put(PREFIX + "latest", candidate)
        }
    }

    /**
     * 获取最近活跃的寄存器简要清单 (供注入到运行时状态供模型感知)
     */
    @Synchronized
    fun snapshot(): Map<String, String> = LinkedHashMap(registers)

    private fun extractTextCandidate(content: JsonObject): String? {
        val priorityKeys = listOf("extracted", "text", "content", "items", "data", "summary", "visibleText")
        for (k in priorityKeys) {
            val element = content[k] ?: continue
            val str = when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.toString()
                else -> element.toString()
            }
            if (!str.isNullOrBlank()) return str
        }
        return null
    }
}

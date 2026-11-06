package com.wangzan101.mika.core.model

/**
 * API Key 辅助解析工具：
 * 支持单个 Key 以及逗号、分号、换行分隔的双 Key / 多 Key 解析。
 */
object KeySupport {
    /**
     * 将输入的原始 API Key 字符串拆解为干净的 Key 列表。
     * 支持中英文逗号、分号、换行符分隔。
     */
    fun splitKeys(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', '，', ';', '；', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 获取首选主 Key（用于连接测试、获取模型列表或不支持多 Key 的场景）。
     */
    fun firstKey(raw: String?): String? = splitKeys(raw).firstOrNull()

    /**
     * 判断是否配置了多个 Key（双 Key 及以上）。
     */
    fun isMultiKey(raw: String?): Boolean = splitKeys(raw).size > 1

    /**
     * 规范化存储格式：去除多余空白，统一以英文逗号分隔。
     */
    fun normalize(raw: String?): String = splitKeys(raw).joinToString(",")
}

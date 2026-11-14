package com.hana.agent.core.memory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable

/**
 * 任务笔记槽中的单条结构化事实条目。
 *
 * @property category 笔记所属类别，如 price、contact、summary、todo 等
 * @property key 笔记条目的标题或键（如商品名、人名、事件等）
 * @property value 笔记条目的具体内容值
 * @property timestamp 记录或最后更新的时间戳（毫秒）
 */
@Serializable
data class NoteEntry(
    val category: String,
    val key: String,
    val value: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Mobile-Agent-v3 结构化笔记槽：
 * 用于在跨步、长列表滚动或多轮翻页场景下按类别摘录关键业务事实（如价格、联系人、摘要、待办），
 * 避免旧页面冗长 UI 树挤爆大模型上下文窗口，实现每任务轻量级状态留存。
 */
class TaskNotePad {
    private val lock = ReentrantLock()
    // category -> (key -> NoteEntry)，使用 LinkedHashMap 保持分类和条目的插入顺序
    private val store = LinkedHashMap<String, LinkedHashMap<String, NoteEntry>>()

    /**
     * 追加或更新结构化笔记条目。
     * 同一 category 下相同 key 的条目自动去重并覆盖为最新 value 与更新时间戳。
     *
     * @param category 类别名（自动转小写并去前后空格）
     * @param key 标识键（去前后空格）
     * @param value 内容值
     * @return true 表示为全新增条目，false 表示更新已有条目
     */
    fun append(category: String, key: String, value: String): Boolean = lock.withLock {
        val catNormalized = category.trim().lowercase()
        val keyNormalized = key.trim()
        val catMap = store.computeIfAbsent(catNormalized) { LinkedHashMap() }
        val isNew = !catMap.containsKey(keyNormalized)
        catMap[keyNormalized] = NoteEntry(
            category = catNormalized,
            key = keyNormalized,
            value = value.trim(),
            timestamp = System.currentTimeMillis(),
        )
        isNew
    }

    /**
     * 查询笔记条目。
     *
     * @param category 若为 null，则返回所有分类下的全部条目；若指定，则只返回对应分类下的条目列表。
     */
    fun read(category: String? = null): List<NoteEntry> = lock.withLock {
        if (category == null) {
            store.values.flatMap { it.values }.toList()
        } else {
            val catNormalized = category.trim().lowercase()
            store[catNormalized]?.values?.toList() ?: emptyList()
        }
    }

    /**
     * 获取当前所有已有类别列表。
     */
    fun categories(): List<String> = lock.withLock {
        store.keys.toList()
    }

    /**
     * 导出紧凑 Markdown 摘要文本，并按 [maxChars] 进行优雅截断。
     */
    fun renderCompactSummary(maxChars: Int = 1000): String = lock.withLock {
        if (store.isEmpty()) return ""
        val sb = StringBuilder()
        for ((cat, entries) in store) {
            if (entries.isEmpty()) continue
            sb.append("### ").append(cat).append("\n")
            for ((_, entry) in entries) {
                sb.append("- ").append(entry.key).append(": ").append(entry.value).append("\n")
            }
        }
        val result = sb.toString().trimEnd()
        return if (result.length > maxChars) {
            result.take(maxChars - 15) + "\n... [truncated]"
        } else {
            result
        }
    }

    /**
     * 清空当前笔记槽中的所有条目。
     */
    fun clear() = lock.withLock {
        store.clear()
    }

    /**
     * 当前笔记槽中的全部条目总数。
     */
    val totalEntriesCount: Int
        get() = lock.withLock { store.values.sumOf { it.size } }

    companion object {
        private val registries = ConcurrentHashMap<String, TaskNotePad>()

        /**
         * 按任务/会话标识 [runId] 获取或创建进程内绑定的笔记槽实例。
         */
        fun getForRun(runId: String): TaskNotePad =
            registries.computeIfAbsent(runId) { TaskNotePad() }

        /**
         * 移除指定任务/会话的笔记槽，防止内存泄漏。
         */
        fun removeForRun(runId: String): TaskNotePad? =
            registries.remove(runId)

        /**
         * 清空全部任务笔记（用于测试隔离）。
         */
        fun clearAll() = registries.clear()
    }
}

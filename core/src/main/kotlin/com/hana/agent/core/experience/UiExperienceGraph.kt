package com.hana.agent.core.experience

import kotlinx.serialization.Serializable
import com.hana.agent.core.action.MacroStep
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class UiExperienceEntry(
    val appId: String,
    val pageSignature: String,
    val intentKeyword: String,
    val macroActions: List<MacroStep>,
    val successCount: Int = 1,
    val lastVerifiedTimestamp: Long = System.currentTimeMillis(),
)

interface UiExperienceGraph {
    suspend fun lookup(appId: String, pageSignature: String, intent: String): UiExperienceEntry?
    suspend fun recordSuccess(entry: UiExperienceEntry)
    suspend fun invalidate(appId: String, pageSignature: String)
}

class InMemoryUiExperienceGraph : UiExperienceGraph {
    private val entries = ConcurrentHashMap<String, MutableList<UiExperienceEntry>>()

    private fun key(appId: String, pageSignature: String): String = "$appId:$pageSignature"

    override suspend fun lookup(appId: String, pageSignature: String, intent: String): UiExperienceEntry? {
        if (intent.isBlank()) return null
        val trimmed = intent.trim()
        val list = entries[key(appId, pageSignature)] ?: return null
        synchronized(list) {
            // 1. 完全精确匹配优先
            val exact = list.filter { it.intentKeyword.equals(trimmed, ignoreCase = true) }
            if (exact.isNotEmpty()) {
                return exact.maxByOrNull { it.successCount }
            }

            // 2. 否定词语义保护：若输入包含否定词，而候选条目不包含该否定词，严禁错误命中
            val negations = listOf("放弃", "取消", "不要", "不用", "别", "不", "退", "拒绝")
            val inputNegations = negations.filter { trimmed.contains(it) }

            // 3. 正向包含匹配 (仅允许输入包含条目关键字，严格禁止反向粗暴包含)
            val candidates = list.filter { candidate ->
                val kw = candidate.intentKeyword.trim()
                if (kw.isEmpty()) return@filter false
                if (!trimmed.contains(kw, ignoreCase = true)) return@filter false

                val hasNegationMismatch = inputNegations.any { neg -> !kw.contains(neg) }
                !hasNegationMismatch
            }

            return candidates.maxByOrNull { it.successCount }
        }
    }

    override suspend fun recordSuccess(entry: UiExperienceEntry) {
        val k = key(entry.appId, entry.pageSignature)
        val list = entries.computeIfAbsent(k) { mutableListOf() }
        synchronized(list) {
            val idx = list.indexOfFirst {
                it.intentKeyword.equals(entry.intentKeyword, ignoreCase = true) &&
                it.macroActions == entry.macroActions
            }
            if (idx >= 0) {
                val existing = list[idx]
                list[idx] = existing.copy(
                    successCount = existing.successCount + 1,
                    lastVerifiedTimestamp = System.currentTimeMillis()
                )
            } else {
                list.add(entry)
            }
        }
    }

    override suspend fun invalidate(appId: String, pageSignature: String) {
        entries.remove(key(appId, pageSignature))
    }

    fun size(): Int = entries.values.sumOf { it.size }

    fun clear() {
        entries.clear()
    }
}

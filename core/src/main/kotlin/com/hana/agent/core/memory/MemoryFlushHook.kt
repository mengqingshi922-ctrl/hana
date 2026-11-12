package com.hana.agent.core.memory

import com.hana.agent.core.agent.ContextBuilder
import com.hana.agent.core.model.Author
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 触发 Pre-Compaction 记忆强制刷盘的原因。 */
enum class FlushTriggerReason {
    /** 整份请求已逼近或达到上下文可用窗口的 80% 水位。 */
    Watermark80,
    /** 即将执行 ContextCompressor 折叠压缩。 */
    Compaction,
    /** 发生上下文超长溢出的紧急压缩。 */
    Overflow,
    /** HistoryPruner 即将裁剪旧轮次消息。 */
    HistoryPrune,
}

/** 传给 [MemoryFlushHook] 的刷盘上下文快照。 */
data class PreCompactFlushRequest(
    val history: List<Message>,
    val aboutToCompact: List<Message>,
    val contextTokens: Int,
    val currentSize: Int,
    val trigger: Int,
    val aggressive: Boolean,
    val reason: FlushTriggerReason,
)

/**
 * Pre-Compaction 记忆强制刷盘回调钩子。
 * 在 [com.hana.agent.core.agent.ContextCompressor] 或 [com.hana.agent.core.agent.HistoryPruner]
 * 触发 80% 水位或裁剪压缩旧消息之前调用，允许先将上下文中的临时关键事实刷写持久化，杜绝「压缩失忆」。
 */
fun interface MemoryFlushHook {
    fun onPreCompact(request: PreCompactFlushRequest)
}

/** 从待裁剪上下文中提炼出的待持久化事实。 */
data class FlushedFact(
    val kind: MemoryKind,
    val content: String,
    val keywords: List<String>,
    val quote: String,
    val triple: Triple<String, String, String>? = null,
)

/**
 * 基于 [MemoryStore] 与 [TemporalFactGraph] 的默认 Pre-Compaction 刷盘实现。
 * 无需额外网络模型调用，在毫秒级本地扫描即将被裁剪的用户原话与交互答复，
 * 提取关键偏好、约束、地址与事实，经 [SensitiveFilter] 过滤后落盘持久化。
 */
class StoreBackedMemoryFlushHook(
    private val memoryStore: MemoryStore? = null,
    private val factGraph: TemporalFactGraph? = null,
    private val conversationId: String? = null,
    private val onFlushed: ((List<FlushedFact>) -> Unit)? = null,
) : MemoryFlushHook {

    private val flushedQuotes = HashSet<String>()

    override fun onPreCompact(request: PreCompactFlushRequest) {
        if (request.aboutToCompact.isEmpty()) return
        val extracted = extractDurableFacts(request.aboutToCompact)
        if (extracted.isEmpty()) return

        val persisted = mutableListOf<FlushedFact>()
        val now = memoryStore?.clock?.invoke() ?: System.currentTimeMillis()
        val existingNormalized = memoryStore?.items?.value
            ?.map { normalize(it.content) }
            ?.toHashSet()
            ?: emptySet()

        for (fact in extracted) {
            if (SensitiveFilter.hit(fact.content) != null || SensitiveFilter.hit(fact.quote) != null) continue
            val norm = normalize(fact.content)
            if (norm.isEmpty() || norm.length > MemoryStore.MAX_CONTENT) continue

            var savedSomething = false
            if (memoryStore != null && norm !in existingNormalized) {
                val res = memoryStore.add(
                    kind = fact.kind,
                    content = fact.content,
                    keywords = fact.keywords,
                    source = MemorySource(
                        type = "user_said",
                        quote = fact.quote,
                        conversationId = conversationId,
                        atMs = now,
                        channel = "pre_compaction_flush",
                    ),
                )
                if (res.isSuccess) {
                    savedSomething = true
                }
            }

            val fg = factGraph
            if (fg != null && fact.triple != null) {
                val (s, r, o) = fact.triple
                runCatching {
                    fg.assertFact(
                        subject = s,
                        relation = r,
                        objectValue = o,
                        validFrom = now,
                        supersedeExisting = true,
                        source = "pre_compaction_flush",
                    )
                    savedSomething = true
                }
            }

            if (savedSomething || (memoryStore == null && factGraph == null)) {
                persisted += fact
            }
        }

        if (persisted.isNotEmpty()) {
            onFlushed?.invoke(persisted)
        }
    }

    internal fun extractDurableFacts(messages: List<Message>): List<FlushedFact> {
        val candidates = mutableListOf<String>()
        for (m in messages) {
            // 1. 用户直接发送的消息（跳过系统注入的记忆/Notice 外壳）
            if (m.author == Author.User && ContextBuilder.kindOf(m) == null) {
                val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }.trim()
                if (text.isNotEmpty()) candidates += text
            }
            // 2. 追问/交回工具结果中用户填写的答案或备注
            if (m.role == Role.Tool) {
                m.parts.filterIsInstance<Part.ToolResult>().forEach { tr ->
                    extractUserAnswerFromToolResult(tr.content)?.let { candidates += it }
                }
            }
        }

        val out = mutableListOf<FlushedFact>()
        for (raw in candidates) {
            val parts = raw.split(SENTENCE_SPLIT)
                .map { it.trim() }
                .filter { it.length in 3..MemoryStore.MAX_CONTENT }
            for (clause in parts) {
                if (!flushedQuotes.add(clause)) continue
                classifyClause(clause)?.let { out += it }
            }
        }
        return out
    }

    private fun extractUserAnswerFromToolResult(jsonStr: String): String? {
        val obj = try {
            Json.parseToJsonElement(jsonStr) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val answer = (obj["answer"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val note = (obj["note"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        return listOfNotNull(answer, note).filter { it.isNotBlank() }.joinToString("。").takeIf { it.isNotBlank() }
    }

    private fun classifyClause(clause: String): FlushedFact? {
        // 排除纯一次性临时指令（"这次"、"今天先"）避免污染长期记忆
        if (ONE_OFF_PREFIX.containsMatchIn(clause) && !EXPLICIT_REMEMBER.containsMatchIn(clause)) {
            return null
        }

        // 地址更新/居住地事实
        ADDRESS_REGEX.find(clause)?.let { match ->
            val addr = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.length >= 2 }
            if (addr != null) {
                return FlushedFact(
                    kind = MemoryKind.fact,
                    content = "用户的常用地址是 $addr",
                    keywords = listOf("地址", addr.take(12)),
                    quote = clause,
                    triple = Triple("用户", "常用地址", addr),
                )
            }
        }

        // 忌口/不要偏好
        AVOID_REGEX.find(clause)?.let { match ->
            val item = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 20 }
            if (item != null) {
                return FlushedFact(
                    kind = MemoryKind.preference,
                    content = "用户偏好不要$item",
                    keywords = listOf("偏好", "忌口", item),
                    quote = clause,
                    triple = Triple("用户", "忌口偏好", "不要$item"),
                )
            }
        }

        // 显式要求记住 / 以后默认规则 / 偏好表达
        if (EXPLICIT_REMEMBER.containsMatchIn(clause) || PREFERENCE_SIGNAL.containsMatchIn(clause)) {
            val isPref = PREFERENCE_SIGNAL.containsMatchIn(clause)
            val kind = if (isPref) MemoryKind.preference else MemoryKind.fact
            val keywords = Tokenize.terms(clause).distinct().take(4)
            return FlushedFact(
                kind = kind,
                content = clause.take(MemoryStore.MAX_CONTENT),
                keywords = keywords,
                quote = clause,
            )
        }

        return null
    }

    private fun normalize(text: String): String =
        text.trim().replace(WHITESPACE, " ").lowercase()

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val SENTENCE_SPLIT = Regex("[。！？!?\n;；]+")
        val ONE_OFF_PREFIX = Regex("^(这次|这一次|今天先|临时|just this time)", RegexOption.IGNORE_CASE)
        val EXPLICIT_REMEMBER = Regex("(记住|请记下|帮我记|以后都|今后都|默认|一直用|remember that|always use)", RegexOption.IGNORE_CASE)
        val PREFERENCE_SIGNAL = Regex("(喜欢|不喜欢|偏好|习惯|忌口|不要放|少放|多放|prefer|dislike)", RegexOption.IGNORE_CASE)
        val ADDRESS_REGEX = Regex("(?:住在|家在|收货地址(?:是|改为|改成|设为)?|寄到|送到)[:：\\s]*([\\u4e00-\\u9fa5A-Za-z0-9\\-\\s]{2,40})")
        val AVOID_REGEX = Regex("(?:不要|别放|忌口|不吃)([\\u4e00-\\u9fa5A-Za-z0-9]{1,16})")
    }
}

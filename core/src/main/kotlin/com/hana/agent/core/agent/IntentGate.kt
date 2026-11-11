package com.hana.agent.core.agent

import com.hana.agent.core.edge.EdgeDecisionPort
import com.hana.agent.core.model.Author
import com.hana.agent.core.model.ChatModel
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import com.hana.agent.core.prompt.Prompts
import kotlinx.coroutines.CancellationException

/** 只在准备调用本轮第一个工具时，独立判断用户是在讨论，还是要读取或操作。 */
class IntentGate(
    private val judge: ChatModel? = null,
    private val fallback: ChatModel? = null,
    private val edgePort: EdgeDecisionPort = EdgeDecisionPort.NONE,
) {
    enum class Intent { Discuss, Read, Act }

    suspend fun assess(task: String, priorTurns: List<Turn>): Intent? {
        val prior = Conversation.context(priorTurns, maxTurns = 3, maxTokens = 800)
            ?.parts?.filterIsInstance<Part.Text>()?.joinToString("\n") { it.text }.orEmpty()
        if (edgePort.isReady) {
            val edgeIntent = edgePort.classifyIntent(task, recentContextSummary = prior, confidenceThreshold = 0.85f)
            if (edgeIntent != null) return edgeIntent
        }
        val input = buildString {
            if (prior.isNotEmpty()) appendLine(prior).appendLine()
            append("The user's current message:\n").append(task)
        }
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (candidate in listOfNotNull(judge, fallback)) {
            val result = try {
                val reply = candidate.complete(Prompts.role("intent_gate"), messages, emptyList())
                parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result != null) return result
        }
        return null
    }

    companion object {
        fun parse(raw: String): Intent? = when (raw.trim().removePrefix("`").removeSuffix("`").trim().uppercase()) {
            "DISCUSS" -> Intent.Discuss
            "READ" -> Intent.Read
            "ACT" -> Intent.Act
            else -> null
        }
    }
}

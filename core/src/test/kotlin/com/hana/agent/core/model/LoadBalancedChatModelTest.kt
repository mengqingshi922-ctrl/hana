package com.hana.agent.core.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LoadBalancedChatModelTest {

    private fun testCaps() = Capabilities(toolCalls = true, vision = true, contextTokens = 128_000, reasoning = false)

    @Test
    fun primaryFirstKeepsHealthyModel() = runTest {
        var c1 = 0
        var c2 = 0
        val model1 = object : ChatModel {
            override val capabilities = testCaps()
            override val name = "m1"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                c1++
                return ModelReply(listOf(Part.Text("reply-m1-$c1")), StopReason.EndTurn, null)
            }
        }
        val model2 = object : ChatModel {
            override val capabilities = testCaps()
            override val name = "m2"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                c2++
                return ModelReply(listOf(Part.Text("reply-m2-$c2")), StopReason.EndTurn, null)
            }
        }

        val balanced = LoadBalancedChatModel(listOf(model1, model2))

        val r1 = balanced.complete("sys", emptyList(), emptyList())
        val r2 = balanced.complete("sys", emptyList(), emptyList())
        val r3 = balanced.complete("sys", emptyList(), emptyList())

        // 正常情况下主力模型 m1 健康，始终优先使用 m1，保障极速低延迟，绝不让慢速端点轮询拖慢
        assertEquals("reply-m1-1", (r1.parts[0] as Part.Text).text)
        assertEquals("reply-m1-2", (r2.parts[0] as Part.Text).text)
        assertEquals("reply-m1-3", (r3.parts[0] as Part.Text).text)
        assertEquals(3, c1)
        assertEquals(0, c2)
    }

    @Test
    fun failoverOnRateLimitSwitchesToHealthyModel() = runTest {
        var callCount1 = 0
        var callCount2 = 0
        val model1 = object : ChatModel {
            override val capabilities = testCaps()
            override val name = "m1"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                callCount1++
                throw ModelError.RateLimited(retryAfterMs = 60_000)
            }
        }
        val model2 = object : ChatModel {
            override val capabilities = testCaps()
            override val name = "m2"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                callCount2++
                return ModelReply(listOf(Part.Text("success from m2")), StopReason.EndTurn, null)
            }
        }

        var simulatedTime = 1000L
        val balanced = LoadBalancedChatModel(listOf(model1, model2), cooldownMs = 60_000, now = { simulatedTime })

        // 第一次调用：m1 抛出 RateLimited，自动转移到 m2
        val r1 = balanced.complete("sys", emptyList(), emptyList())
        assertEquals("success from m2", (r1.parts[0] as Part.Text).text)
        assertEquals(1, callCount1)
        assertEquals(1, callCount2)

        // 紧接着调用第二次：m1 还在 60 秒冷却期内，直接走 m2，不浪费时间打 m1
        val r2 = balanced.complete("sys", emptyList(), emptyList())
        assertEquals("success from m2", (r2.parts[0] as Part.Text).text)
        assertEquals(1, callCount1)
        assertEquals(2, callCount2)

        // 模拟时间前进 61 秒，m1 冷却结束，自动恢复优先走主力 m1
        simulatedTime += 61_000
        val r3 = balanced.complete("sys", emptyList(), emptyList())
        assertEquals("success from m2", (r3.parts[0] as Part.Text).text)
        assertEquals(2, callCount1) // 再次调用了 m1，因为 m1 又抛出 RateLimited，所以 fallback 到了 m2
        assertEquals(3, callCount2)
    }

    @Test
    fun unrecoverableErrorThrowsImmediatelyWithoutFailover() = runTest {
        val model1 = object : ChatModel {
            override val capabilities = testCaps()
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                throw ModelError.ContextTooLong()
            }
        }
        val model2 = object : ChatModel {
            override val capabilities = testCaps()
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                return ModelReply(listOf(Part.Text("should not reach")), StopReason.EndTurn, null)
            }
        }

        val balanced = LoadBalancedChatModel(listOf(model1, model2))
        try {
            balanced.complete("sys", emptyList(), emptyList())
            fail("Expected ContextTooLong exception")
        } catch (e: ModelError.ContextTooLong) {
            // 正确，不该切换 Key
        }
    }

    @Test
    fun keySupportSplitsCommaSemicolonNewline() {
        val single = KeySupport.splitKeys("sk-123456")
        assertEquals(listOf("sk-123456"), single)

        val multiple = KeySupport.splitKeys("  sk-1 , sk-2 \n sk-3 ； sk-4 ， sk-5  ")
        assertEquals(listOf("sk-1", "sk-2", "sk-3", "sk-4", "sk-5"), multiple)
        assertTrue(KeySupport.isMultiKey("sk-1,sk-2"))
        assertEquals("sk-1", KeySupport.firstKey("sk-1,sk-2"))
        assertEquals("sk-1,sk-2", KeySupport.normalize(" sk-1 ,  sk-2 "))
    }

    @Test
    fun allCoolingFailsFastInsteadOfBurningRequests() = runTest {
        var calls = 0
        fun failing() = object : ChatModel {
            override val capabilities = testCaps()
            override val name = "m"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                calls++
                throw ModelError.RateLimited(retryAfterMs = 30_000)
            }
        }
        var t = 1_000L
        val balanced = LoadBalancedChatModel(listOf(failing(), failing()), now = { t })

        try { balanced.complete("sys", emptyList(), emptyList()); fail("应当失败") } catch (_: ModelError.RateLimited) {}
        assertEquals("两个通道各试一次", 2, calls)

        // 两个都还在冷却：立刻失败，不能再打请求
        t += 1_000
        try { balanced.complete("sys", emptyList(), emptyList()); fail("应当失败") } catch (_: ModelError.RateLimited) {}
        assertEquals("冷却期内不再发请求", 2, calls)

        // 冷却结束后恢复
        t += 31_000
        try { balanced.complete("sys", emptyList(), emptyList()) } catch (_: ModelError.RateLimited) {}
        assertEquals("解封后重新尝试", 4, calls)
    }

    @Test
    fun capabilitiesTakeTheSmallestWindow() {
        val big = object : ChatModel {
            override val capabilities = testCaps().copy(contextTokens = 512_000)
            override val name = "big"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("x")), StopReason.EndTurn, null)
        }
        val small = object : ChatModel {
            override val capabilities = testCaps().copy(contextTokens = 128_000)
            override val name = "small"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("x")), StopReason.EndTurn, null)
        }
        val balanced = LoadBalancedChatModel(listOf(big, small))
        assertEquals(128_000, balanced.capabilities.contextTokens)
    }

    @Test
    fun slowFirstTokenFailsOverToTheOtherChannel() = runTest {
        val slow = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "slow"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("slow")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                kotlinx.coroutines.delay(30_000) // 一直不出字
                return ModelReply(listOf(Part.Text("slow")), StopReason.EndTurn, null)
            }
        }
        val fast = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "fast"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("fast")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                onText("fast")
                return ModelReply(listOf(Part.Text("fast")), StopReason.EndTurn, null)
            }
        }
        val balanced = LoadBalancedChatModel(
            listOf(slow, fast),
            hedge = LoadBalancedChatModel.HedgePolicy(minDelayMs = 1_000, maxDelayMs = 1_000, seedDelayMs = 1_000),
        )
        val r = balanced.completeStreaming("sys", emptyList(), emptyList()) {}
        assertEquals("fast", (r.parts[0] as Part.Text).text)
    }

    @Test
    fun zeroBudgetTurnsHedgingOffAndWaitsForThePrimary() = runTest {
        val slow = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "slow"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("slow")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                kotlinx.coroutines.delay(30_000)
                onText("slow")
                return ModelReply(listOf(Part.Text("slow")), StopReason.EndTurn, null)
            }
        }
        var fastCalls = 0
        val fast = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "fast"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("fast")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                fastCalls++
                onText("fast")
                return ModelReply(listOf(Part.Text("fast")), StopReason.EndTurn, null)
            }
        }
        val balanced = LoadBalancedChatModel(
            listOf(slow, fast),
            hedge = LoadBalancedChatModel.HedgePolicy(budget = 0.0, refillPerCall = 0.0, minDelayMs = 1_000, maxDelayMs = 1_000, seedDelayMs = 1_000),
        )
        val r = balanced.completeStreaming("sys", emptyList(), emptyList()) {}
        assertEquals("没有预算就不对冲，等主通道", "slow", (r.parts[0] as Part.Text).text)
        assertEquals(0, fastCalls)
    }

    @Test
    fun hedgeIsCountedInStats() = runTest {
        val slow = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "slow"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("slow")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                kotlinx.coroutines.delay(30_000)
                return ModelReply(listOf(Part.Text("slow")), StopReason.EndTurn, null)
            }
        }
        val fast = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "fast"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("fast")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                onText("fast")
                return ModelReply(listOf(Part.Text("fast")), StopReason.EndTurn, null)
            }
        }
        val balanced = LoadBalancedChatModel(
            listOf(slow, fast),
            hedge = LoadBalancedChatModel.HedgePolicy(minDelayMs = 1_000, maxDelayMs = 1_000, seedDelayMs = 1_000),
        )
        balanced.completeStreaming("sys", emptyList(), emptyList()) {}
        val st = balanced.stats()
        assertEquals(1, st.hedges)
        assertEquals(1, st.backupWins)
        assertEquals(0, st.primaryWins)
    }

    @Test
    fun primaryFailureDuringHedgeDoesNotCancelHealthyBackup() = runTest {
        // 主通道在对冲后报错 503，备用通道正常完成：绝不能把备用通道误杀，必须顺利返回备用通道结果
        val failingPrimary = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "failingPrimary"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                throw ModelError.Server(503)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                kotlinx.coroutines.delay(1_500)
                throw ModelError.Server(503)
            }
        }
        val healthyBackup = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "healthyBackup"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("backup success")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                kotlinx.coroutines.delay(2_500)
                onText("backup success")
                return ModelReply(listOf(Part.Text("backup success")), StopReason.EndTurn, null)
            }
        }

        val balanced = LoadBalancedChatModel(
            listOf(failingPrimary, healthyBackup),
            hedge = LoadBalancedChatModel.HedgePolicy(minDelayMs = 1_000, maxDelayMs = 1_000, seedDelayMs = 1_000),
        )
        val r = balanced.completeStreaming("sys", emptyList(), emptyList()) {}
        assertEquals("backup success", (r.parts[0] as Part.Text).text)
    }

    @Test
    fun backupWinsDoesNotCoolDownPrimaryPreservingPromptCacheAffinity() = runTest {
        // 当对冲中备用通道赢了，主通道绝不能被冷却，下一步依然由主通道优先处理，保证 Prompt Cache 不被击穿
        val slowPrimary = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "slowPrimary"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("primary")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                kotlinx.coroutines.delay(2_000)
                onText("primary")
                return ModelReply(listOf(Part.Text("primary")), StopReason.EndTurn, null)
            }
        }
        val fastBackup = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "fastBackup"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("fast")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                kotlinx.coroutines.delay(200)
                onText("fast")
                return ModelReply(listOf(Part.Text("fast")), StopReason.EndTurn, null)
            }
        }
        val balanced = LoadBalancedChatModel(
            listOf(slowPrimary, fastBackup),
            hedge = LoadBalancedChatModel.HedgePolicy(minDelayMs = 500, maxDelayMs = 500, seedDelayMs = 500),
        )
        // 第一次调用：backup 跑赢
        balanced.completeStreaming("sys", emptyList(), emptyList()) {}
        val st = balanced.stats()
        assertEquals(1, st.backupWins)

        // 验证关键行为：主通道（索引 0）绝不能在冷却中，selectIndex 必须依然返回 0！
        assertEquals("主通道未出错时必须保持亲和性，保护 Prompt Cache", 0, balanced.selectIndex())
    }

    @Test
    fun streamingMuxPreventsInterleavedChunks() = runTest {
        val streamOutput = mutableListOf<String>()
        val slowPrimary = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "slowPrimary"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("p1p2")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                kotlinx.coroutines.delay(3_000)
                onText("p1")
                kotlinx.coroutines.delay(100)
                onText("p2")
                return ModelReply(listOf(Part.Text("p1p2")), StopReason.EndTurn, null)
            }
        }
        val fastBackup = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "fastBackup"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("b1b2")), StopReason.EndTurn, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                kotlinx.coroutines.delay(500)
                onText("b1")
                kotlinx.coroutines.delay(100)
                onText("b2")
                return ModelReply(listOf(Part.Text("b1b2")), StopReason.EndTurn, null)
            }
        }

        val balanced = LoadBalancedChatModel(
            listOf(slowPrimary, fastBackup),
            hedge = LoadBalancedChatModel.HedgePolicy(minDelayMs = 1_000, maxDelayMs = 1_000, seedDelayMs = 1_000),
        )
        val r = balanced.completeStreaming("sys", emptyList(), emptyList()) { chunk ->
            streamOutput.add(chunk)
        }
        assertEquals("b1b2", (r.parts[0] as Part.Text).text)
        // 验证收到的流式文本全是 fastBackup 的，没有混入 slowPrimary 的字符
        assertEquals(listOf("b1", "b2"), streamOutput)
    }

    @Test
    fun toolCallWithoutTextRecordsLatencySample() = runTest {
        val toolModel = object : StreamingChatModel {
            override val capabilities = testCaps()
            override val name = "toolModel"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.ToolCall("c1", "phone_tap", "{}")), StopReason.ToolUse, null)
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                // 工具调用不吐正文文本
                kotlinx.coroutines.delay(50)
                return ModelReply(listOf(Part.ToolCall("c1", "phone_tap", "{}")), StopReason.ToolUse, null)
            }
        }

        val balanced = LoadBalancedChatModel(listOf(toolModel))
        balanced.completeStreaming("sys", emptyList(), emptyList()) {}
        val st = balanced.stats()
        assertEquals(1, st.samples[0])
    }
}

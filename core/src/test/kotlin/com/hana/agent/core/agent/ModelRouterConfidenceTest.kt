package com.hana.agent.core.agent

import com.hana.agent.core.model.Capabilities
import com.hana.agent.core.model.ChatModel
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.ModelReply
import com.hana.agent.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class TestChatModel(
    override val name: String,
    toolCalls: Boolean = true,
    vision: Boolean = false,
) : ChatModel {
    override val capabilities = Capabilities(
        toolCalls = toolCalls,
        vision = vision,
        contextTokens = 64_000,
        reasoning = false,
    )
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        error("Test mock not called")
}

/**
 * MAI-UI 端云协同置信度路由门控单元测试。
 */
class ModelRouterConfidenceTest {
    private val cloudMain = TestChatModel("cloud-main", toolCalls = true, vision = true)
    private val edgeReflex = TestChatModel("edge-reflex", toolCalls = true, vision = false)
    private val lightweight = TestChatModel("lightweight", toolCalls = true, vision = false)

    private fun cleanCtx(step: Int = 2, hasImages: Boolean = false) = StepContext(
        step = step,
        lastOk = true,
        lastHadGuard = false,
        lastChanged = true,
        lastWasRecovery = false,
        sameAppAsLast = true,
        hasImages = hasImages,
        scene = ModelScene.VisionAction,
    )

    private fun createRouter(): ModelRouter {
        return ModelRouter(
            slots = ModelSlots(
                chat = cloudMain,
                visionAction = cloudMain,
                memorySummary = lightweight,
                edgeReflex = edgeReflex,
            )
        )
    }

    @Test
    fun testLowConfidenceForcesCloudMain() {
        val router = createRouter()
        val ctx = cleanCtx(2)
        val lowSignal = ConfidenceSignal(stepConfidence = 0.5f) // < 0.65 threshold
        val decision = router.routeWithConfidence(ctx, lowSignal)

        assertEquals(RouteTier.CLOUD_MAIN, decision.tier)
        assertSame(cloudMain, decision.model)
        assertTrue(decision.reason.contains("low_confidence"))
    }

    @Test
    fun testTreeQualityPoorForcesCloudMain() {
        val router = createRouter()
        val ctx = cleanCtx(2)
        val treePoorSignal = ConfidenceSignal(stepConfidence = 0.9f, treeQualityPoor = true)
        val decision = router.routeWithConfidence(ctx, treePoorSignal)

        assertEquals(RouteTier.CLOUD_MAIN, decision.tier)
        assertSame(cloudMain, decision.model)
        assertEquals("tree_quality_poor", decision.reason)
    }

    @Test
    fun testAmbiguityDetectedForcesCloudMain() {
        val router = createRouter()
        val ctx = cleanCtx(2)
        val ambiguitySignal = ConfidenceSignal(stepConfidence = 0.95f, ambiguityDetected = true)
        val decision = router.routeWithConfidence(ctx, ambiguitySignal)

        assertEquals(RouteTier.CLOUD_MAIN, decision.tier)
        assertSame(cloudMain, decision.model)
        assertEquals("ambiguity_detected", decision.reason)
    }

    @Test
    fun testConsecutiveLowConfidenceForcesCloudMain() {
        val router = createRouter()
        val ctx = cleanCtx(2)
        val signal = ConfidenceSignal(stepConfidence = 0.9f, consecutiveLowConfidence = 1)
        val decision = router.routeWithConfidence(ctx, signal)

        assertEquals(RouteTier.CLOUD_MAIN, decision.tier)
        assertSame(cloudMain, decision.model)
        assertEquals("consecutive_low_confidence", decision.reason)
    }

    @Test
    fun testRecoveryStateForcesCloudMain() {
        val router = createRouter()
        val recoveryCtx = cleanCtx(2).copy(lastWasRecovery = true)
        val highSignal = ConfidenceSignal(stepConfidence = 0.95f)
        val decision = router.routeWithConfidence(recoveryCtx, highSignal)

        assertEquals(RouteTier.CLOUD_MAIN, decision.tier)
        assertSame(cloudMain, decision.model)
        assertEquals("recovery_state", decision.reason)
    }

    @Test
    fun testHighConfidenceRoutesToEdgeReflexOrLightweight() {
        val router = createRouter()
        val ctx = cleanCtx(2, hasImages = false)
        val highSignal = ConfidenceSignal(stepConfidence = 0.88f)
        val decision = router.routeWithConfidence(ctx, highSignal)

        assertEquals(RouteTier.EDGE_REFLEX, decision.tier)
        assertSame(edgeReflex, decision.model)
        assertEquals("high_confidence_edge_reflex", decision.reason)
    }

    @Test
    fun testHasImagesUnsupportedByLightweightForcesCloudMain() {
        val router = createRouter()
        // edgeReflex 和 lightweight 都不支持 vision
        val ctxWithImages = cleanCtx(2, hasImages = true)
        val highSignal = ConfidenceSignal(stepConfidence = 0.95f)
        val decision = router.routeWithConfidence(ctxWithImages, highSignal)

        assertEquals(RouteTier.CLOUD_MAIN, decision.tier)
        assertSame(cloudMain, decision.model)
        assertEquals("has_images_unsupported_by_lightweight", decision.reason)
    }
}

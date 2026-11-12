package com.hana.agent.core.model

import com.hana.agent.core.agent.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeFormatter

class HttpSupportRetryAfterTest {

    @Test
    fun parseHeaderFormats() {
        assertEquals(15000L, HttpSupport.retryAfterMs("15"))
        assertEquals(2500L, HttpSupport.retryAfterMs("2.5"))
        assertEquals(30000L, HttpSupport.retryAfterMs("30s"))
        assertEquals(12000L, HttpSupport.retryAfterMs(" 12S "))

        // HTTP-Date RFC 1123
        val future = Instant.ofEpochMilli(System.currentTimeMillis() + 60_000)
        val dateStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(future.atZone(java.time.ZoneOffset.UTC))
        val parsed = HttpSupport.retryAfterMs(dateStr)
        assertNotNull(parsed)
        assertTrue(parsed!! in 50_000L..65_000L)
    }

    @Test
    fun parseGoogleRpcRetryInfo() {
        val body = """
            {
              "error": {
                "code": 429,
                "message": "Resource has been exhausted",
                "status": "RESOURCE_EXHAUSTED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "18s"
                  }
                ]
              }
            }
        """.trimIndent()
        assertEquals(18000L, HttpSupport.retryAfterMs(null, body))
        val err = ModelHttp.geminiError(429, null, body, "gemini-pro")
        assertTrue(err is ModelError.RateLimited)
        assertEquals(18000L, (err as ModelError.RateLimited).retryAfterMs)
    }

    @Test
    fun parseOpenAiErrorMessage() {
        val body = """{"error":{"message":"Rate limit reached for requests. Please try again in 20.5s."}}"""
        assertEquals(20500L, HttpSupport.retryAfterMs(null, body))
        val err = ModelHttp.openAiError(429, null, body, "gpt-4o")
        assertTrue(err is ModelError.RateLimited)
        assertEquals(20500L, (err as ModelError.RateLimited).retryAfterMs)
    }

    @Test
    fun parseJsonRetryAfterFields() {
        assertEquals(12000L, HttpSupport.retryAfterMs(null, """{"error":{"retry_after":12}}"""))
        assertEquals(5000L, HttpSupport.retryAfterMs(null, """{"retry_after":5}"""))
        assertEquals(500L, HttpSupport.retryAfterMs(null, """{"error":{"retryDelay":"500ms"}}"""))
    }

    @Test
    fun parsePlainTextFallback() {
        val plain = "Too many requests. Rate limit exceeded, retry in 40 seconds."
        assertEquals(40000L, HttpSupport.retryAfterMs(null, plain))
    }

    @Test
    fun nullWhenNoRetryDelay() {
        assertNull(HttpSupport.retryAfterMs(null, """{"error":{"message":"Internal server error"}}"""))
        assertNull(HttpSupport.retryAfterMs("", ""))
    }

    @Test
    fun retryPolicyExponentialBackoffWhenNoRetryAfter() {
        val policy = RetryPolicy(maxRetries = 4, jitter = { 1.0 })
        val rateLimitedNoHeader = ModelError.RateLimited(null)
        // retriesSoFar: 0 -> 1000ms, 1 -> 2000ms, 2 -> 4000ms, 3 -> 8000ms
        assertEquals(1000L, policy.delayFor(rateLimitedNoHeader, 0))
        assertEquals(2000L, policy.delayFor(rateLimitedNoHeader, 1))
        assertEquals(4000L, policy.delayFor(rateLimitedNoHeader, 2))
        assertEquals(8000L, policy.delayFor(rateLimitedNoHeader, 3))
        assertNull(policy.delayFor(rateLimitedNoHeader, 4))
    }

    @Test
    fun retryPolicyFollowsExtractedRetryAfter() {
        val policy = RetryPolicy(maxRetries = 3, retryAfterCapMs = 30_000)
        val rateLimitedWithDelay = ModelError.RateLimited(15_000L)
        assertEquals(15000L, policy.delayFor(rateLimitedWithDelay, 0))
        val rateLimitedOverCap = ModelError.RateLimited(90_000L)
        assertEquals(30000L, policy.delayFor(rateLimitedOverCap, 0))
    }
}

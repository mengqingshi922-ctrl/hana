package com.hana.agent.core.mcp

import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.cap.mcp.McpAnnotations
import com.hana.agent.core.cap.mcp.McpConnection
import com.hana.agent.core.cap.mcp.McpEndpoint
import com.hana.agent.core.cap.mcp.McpResult
import com.hana.agent.core.cap.mcp.McpToolDescriptor
import com.hana.agent.core.record.RunEvent
import com.hana.agent.core.record.RunSink
import com.hana.agent.core.run.Attendance
import com.hana.agent.core.run.MemoryRunStore
import com.hana.agent.core.run.RunEventBus
import com.hana.agent.core.run.RunOrigin
import com.hana.agent.core.run.RunPhase
import com.hana.agent.core.run.RunSpec
import com.hana.agent.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    private class Endpoint : McpEndpoint {
        override val serverId = "fake"
        var calls = 0
        override fun tools() = listOf(
            McpToolDescriptor("read", "Read", buildJsonObject { put("type", "object") }, McpAnnotations(readOnlyHint = true)),
            McpToolDescriptor("send", "Send", buildJsonObject { put("type", "object") }, McpAnnotations(openWorldHint = true)),
        )
        override suspend fun call(name: String, arguments: JsonObject): McpResult { calls++; return McpResult(name) }
    }

    @Test fun lowLevelToolCallsRunAutonomouslyAndEmitEvents() = runBlocking {
        val endpoint = Endpoint()
        val store = MemoryRunStore()
        val server = McpServer({ spec -> toolDispatcher(FakeScreen(), runSpec = spec,
            mcpServers = listOf(McpConnection(endpoint))) }, McpTaskHost { error("not used") }, store)
        val session = server.openToolSession("external-thread")
        val events = mutableListOf<RunEvent>()
        session.events.subscribe(object : RunSink { override fun event(e: RunEvent) { events += e } })
        assertEquals(RunOrigin.Mcp, session.spec.provenance.origin)
        assertEquals(Attendance.Unattended, session.spec.provenance.attendance)
        val read = session.call("mcp_fake__read", "{}")
        assertEquals("true", read["ok"].toString())
        val send = session.call("mcp_fake__send", "{}")
        assertEquals("true", send["ok"].toString())
        assertEquals(2, endpoint.calls)
        assertFalse(events.any { it is RunEvent.Approval && it.tool == "mcp_fake__send" })
        assertTrue(events.any { it is RunEvent.ToolResult })
        session.close()
        assertEquals(RunPhase.Ended, store.get(session.spec.id)?.phase)
        assertFalse(store.get(session.spec.id)?.roundInProgress == true)
    }

    @Test fun wholeTaskEntryKeepsMcpOriginAndPushesEvents() {
        var submitted: RunSpec? = null
        val bus = RunEventBus()
        val server = McpServer({ spec -> toolDispatcher(FakeScreen(), runSpec = spec) }, McpTaskHost { spec ->
            submitted = spec
            McpTaskLink(spec.id, bus)
        })
        val link = server.submitTask("查资料", "remote")
        assertEquals(RunOrigin.Mcp, submitted?.provenance?.origin)
        assertEquals(Attendance.Unattended, submitted?.provenance?.attendance)
        val received = mutableListOf<RunEvent>()
        link.events.subscribe(object : RunSink { override fun event(e: RunEvent) { received += e } })
        bus.event(RunEvent.State(1, "progress", "reading"))
        assertEquals(1, received.size)
    }
}

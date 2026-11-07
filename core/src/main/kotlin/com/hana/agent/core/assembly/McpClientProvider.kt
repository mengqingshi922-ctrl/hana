package com.hana.agent.core.assembly

import com.hana.agent.core.cap.mcp.McpCapability
import com.hana.agent.core.cap.mcp.McpConnection
import com.hana.agent.core.run.RunSpec

internal class McpClientProvider(private val connection: McpConnection) : CapabilityProvider {
    override val id: String = "mcp:${connection.endpoint.serverId}"
    override fun open(spec: RunSpec) = McpCapability(connection, id)
}

package com.hana.agent.core.tool

import com.hana.agent.core.bus.RegisterStoreTool
import com.hana.agent.core.bus.RegisterResolveTool

/**
 * PhoneGroup 工具注册契约 (Core 命名空间)
 * 注册核心扩展工具 RegisterStoreTool 与 RegisterResolveTool
 */
internal object PhoneGroup {
    val registeredTools: List<AgentTool> = listOf(
        RegisterStoreTool,
        RegisterResolveTool
    )
}

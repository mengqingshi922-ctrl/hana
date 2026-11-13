package com.hana.agent.edge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.hana.agent.core.cap.mcp.McpAnnotations
import com.hana.agent.core.cap.mcp.McpToolDescriptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Android 16 AppFunctions 平台发现能力数据模型
 */
@Serializable
data class DiscoveredAppFunction(
    val packageName: String,
    val functionId: String,
    val title: String,
    val description: String,
    val parameterKeys: List<String> = emptyList(),
)

/**
 * 契约类型别名，无缝桥接核心 MCP 规范
 */
typealias McpToolDef = McpToolDescriptor

/**
 * Android 16 (API 36) AppFunctions 本地直调契约桥接器：
 * 1. 负责在系统支持时（Android 16+）动态探测本机已声明 AppFunctionService 的应用与公开能力清单；
 * 2. 在低于 Android 16 或无系统权限环境下实现零崩溃优雅降级；
 * 3. 提供静态契约转换函数 [toMcpToolDefs]，将已发现的 AppFunctions 转换为本地 MCP 工具定义供 Agent 统一编排与调用。
 */
object AppFunctionBridge {

    /** Android 16 平台引入的 AppFunction 服务标准 Action */
    const val APP_FUNCTION_SERVICE_ACTION = "android.app.appfunctions.AppFunctionService"

    /** Android 16 最低支持 SDK 版本 */
    const val MIN_APP_FUNCTIONS_SDK_INT = 36

    /**
     * 探测宿主设备中已安装并声明的 AppFunctions 能力列表。
     * 低于 Android 16 或探测异常时静默降级返回空列表，保障端侧稳定性。
     */
    fun discoverAppFunctions(context: Context): List<DiscoveredAppFunction> {
        if (Build.VERSION.SDK_INT < MIN_APP_FUNCTIONS_SDK_INT) {
            return emptyList()
        }

        return runCatching {
            val pm = context.packageManager
            val intent = Intent(APP_FUNCTION_SERVICE_ACTION)

            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
            }

            val discoveredList = mutableListOf<DiscoveredAppFunction>()
            for (ri in resolveInfos) {
                val serviceInfo = ri.serviceInfo ?: continue
                val pkg = serviceInfo.packageName
                val label = runCatching { serviceInfo.loadLabel(pm).toString() }
                    .getOrDefault(serviceInfo.name.substringAfterLast('.'))

                val metaData = serviceInfo.metaData
                val metaFunctions = metaData?.getString("android.app.appfunctions.functions")

                if (!metaFunctions.isNullOrBlank()) {
                    metaFunctions.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { fnId ->
                            discoveredList.add(
                                DiscoveredAppFunction(
                                    packageName = pkg,
                                    functionId = fnId,
                                    title = "$label - $fnId",
                                    description = "Discovered Android 16 AppFunction from $pkg",
                                    parameterKeys = emptyList(),
                                )
                            )
                        }
                } else {
                    val fallbackId = serviceInfo.name.substringAfterLast('.')
                    discoveredList.add(
                        DiscoveredAppFunction(
                            packageName = pkg,
                            functionId = fallbackId,
                            title = label,
                            description = "Discovered AppFunction service from $pkg",
                            parameterKeys = emptyList(),
                        )
                    )
                }
            }

            // 安全尝试反射平台系统服务能力（针对已内建 app_function 系统服务的定制 ROM / 原生 Android 16）
            runCatching {
                val appFunctionManager = context.getSystemService("app_function")
                if (appFunctionManager != null) {
                    val method = appFunctionManager.javaClass.getMethod("getAppFunctionInfoList")
                    val list = method.invoke(appFunctionManager) as? List<*>
                    if (list != null) {
                        for (item in list) {
                            if (item == null) continue
                            val itemClass = item.javaClass
                            val pkg = runCatching { itemClass.getMethod("getPackageName").invoke(item) as? String }.getOrNull() ?: ""
                            val fnId = runCatching { itemClass.getMethod("getFunctionId").invoke(item) as? String }.getOrNull() ?: ""
                            val title = runCatching { itemClass.getMethod("getTitle").invoke(item) as? String }.getOrNull() ?: fnId
                            val desc = runCatching { itemClass.getMethod("getDescription").invoke(item) as? String }.getOrNull() ?: ""
                            if (pkg.isNotEmpty() && fnId.isNotEmpty()) {
                                discoveredList.add(
                                    DiscoveredAppFunction(
                                        packageName = pkg,
                                        functionId = fnId,
                                        title = title,
                                        description = desc,
                                        parameterKeys = emptyList(),
                                    )
                                )
                            }
                        }
                    }
                }
            }

            discoveredList
        }.getOrElse {
            emptyList()
        }
    }

    /**
     * 将已探测的 [DiscoveredAppFunction] 转换为标准的 MCP 工具描述符 [McpToolDescriptor]。
     * 生成合法消毒的 tool name 及 JSON Schema，支持作为本地工具直接注册到 Agent 规划执行体系中。
     */
    fun toMcpToolDefs(functions: List<DiscoveredAppFunction>): List<McpToolDescriptor> {
        return functions.map { fn ->
            val cleanPkg = fn.packageName.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val cleanId = fn.functionId.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val rawName = "appfn_${cleanPkg}_$cleanId"
            val toolName = if (rawName.length > 64) rawName.substring(0, 64) else rawName

            val schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    for (paramKey in fn.parameterKeys) {
                        putJsonObject(paramKey) {
                            put("type", "string")
                            put("description", "Parameter '$paramKey' for AppFunction '${fn.functionId}'")
                        }
                    }
                }
                put("required", buildJsonArray {
                    for (paramKey in fn.parameterKeys) {
                        add(JsonPrimitive(paramKey))
                    }
                })
                put("additionalProperties", false)
            }

            val desc = buildString {
                append(fn.title)
                if (fn.description.isNotBlank()) {
                    append(": ")
                    append(fn.description)
                }
            }

            McpToolDescriptor(
                name = toolName,
                description = desc,
                inputSchema = schema,
                annotations = McpAnnotations(openWorldHint = true),
                estimatedCostUnits = 0L,
            )
        }
    }
}

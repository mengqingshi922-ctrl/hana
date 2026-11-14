package com.hana.agent.edge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import com.hana.agent.core.cap.mcp.McpAnnotations
import com.hana.agent.core.cap.mcp.McpToolDescriptor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.Closeable
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.coroutines.resume

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
 * AppFunction 执行结果结构化模型
 */
@Serializable
data class AppFunctionExecutionResult(
    val ok: Boolean,
    val output: String,
    val error: String? = null,
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

    private const val APP_FUNCTION_SERVICE_NAME = "app_function"
    private const val APP_SEARCH_SERVICE_NAME = "app_search"
    private const val APP_SEARCH_TIMEOUT_MS = 800L
    private const val EXECUTE_TIMEOUT_MS = 15_000L

    private val bridgeExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hana-appfn-bridge").apply { isDaemon = true }
        }
    }

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
            val existingKeys = HashSet<String>()
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
                            if (existingKeys.add("$pkg:$fnId")) {
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
                        }
                } else {
                    val fallbackId = serviceInfo.name.substringAfterLast('.')
                    if (existingKeys.add("$pkg:$fallbackId")) {
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
            }

            // 通过 Android 16 AppSearchManager 全局索引查询 AppFunctionStaticMetadata 元数据
            queryAppSearchMetadata(context).forEach { fn ->
                if (existingKeys.add("${fn.packageName}:${fn.functionId}")) {
                    discoveredList.add(fn)
                }
            }

            discoveredList
        }.getOrElse {
            emptyList()
        }
    }

    /**
     * 通过反射调用 Android AppSearchManager.createGlobalSearchSession 检索系统索引的 AppFunctionStaticMetadata。
     * 权限不足、ROM 未开放或超时时静默降级返回空列表。
     */
    private fun queryAppSearchMetadata(context: Context): List<DiscoveredAppFunction> = runCatching {
        val appSearchManager = context.getSystemService(APP_SEARCH_SERVICE_NAME) ?: return emptyList()
        val createSessionMethod = appSearchManager.javaClass.getMethod(
            "createGlobalSearchSession",
            Executor::class.java,
            Consumer::class.java,
        )

        val sessionRef = AtomicReference<Any?>(null)
        val sessionLatch = CountDownLatch(1)
        val sessionConsumer = Consumer<Any> { appSearchResult ->
            runCatching {
                val isSuccess = appSearchResult.javaClass.getMethod("isSuccess").invoke(appSearchResult) as? Boolean == true
                if (isSuccess) {
                    sessionRef.set(appSearchResult.javaClass.getMethod("getResultValue").invoke(appSearchResult))
                }
            }
            sessionLatch.countDown()
        }

        createSessionMethod.invoke(appSearchManager, bridgeExecutor, sessionConsumer)
        if (!sessionLatch.await(APP_SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return emptyList()
        }
        val session = sessionRef.get() ?: return emptyList()

        try {
            val searchSpecBuilderClass = Class.forName("android.app.appsearch.SearchSpec\$Builder")
            val specBuilder = searchSpecBuilderClass.getConstructor().newInstance()
            searchSpecBuilderClass.getMethod("addFilterNamespaces", Collection::class.java)
                .invoke(specBuilder, listOf("app_functions"))
            searchSpecBuilderClass.getMethod("addFilterSchemas", Collection::class.java)
                .invoke(specBuilder, listOf("AppFunctionStaticMetadata"))
            searchSpecBuilderClass.getMethod("setResultCountPerPage", Int::class.javaPrimitiveType)
                .invoke(specBuilder, 100)
            val searchSpec = searchSpecBuilderClass.getMethod("build").invoke(specBuilder)

            val searchResults = session.javaClass
                .getMethod("search", String::class.java, Class.forName("android.app.appsearch.SearchSpec"))
                .invoke(session, "", searchSpec) ?: return emptyList()

            try {
                val pageRef = AtomicReference<List<*>?>(null)
                val pageLatch = CountDownLatch(1)
                val pageConsumer = Consumer<Any> { appSearchResult ->
                    runCatching {
                        val isSuccess = appSearchResult.javaClass.getMethod("isSuccess").invoke(appSearchResult) as? Boolean == true
                        if (isSuccess) {
                            pageRef.set(appSearchResult.javaClass.getMethod("getResultValue").invoke(appSearchResult) as? List<*>)
                        }
                    }
                    pageLatch.countDown()
                }
                searchResults.javaClass
                    .getMethod("getNextPage", Executor::class.java, Consumer::class.java)
                    .invoke(searchResults, bridgeExecutor, pageConsumer)

                if (!pageLatch.await(APP_SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return emptyList()
                }

                val results = pageRef.get() ?: return emptyList()
                val out = mutableListOf<DiscoveredAppFunction>()
                for (sr in results) {
                    if (sr == null) continue
                    val pkg = runCatching { sr.javaClass.getMethod("getPackageName").invoke(sr) as? String }.getOrNull().orEmpty()
                    val doc = runCatching { sr.javaClass.getMethod("getGenericDocument").invoke(sr) }.getOrNull() ?: continue
                    val docClass = doc.javaClass
                    val getPropStr = docClass.getMethod("getPropertyString", String::class.java)
                    val fnId = (runCatching { getPropStr.invoke(doc, "functionId") as? String }.getOrNull()
                        ?: runCatching { docClass.getMethod("getId").invoke(doc) as? String }.getOrNull())
                        .orEmpty()
                    if (pkg.isEmpty() || fnId.isEmpty()) continue

                    val title = (runCatching { getPropStr.invoke(doc, "displayName") as? String }.getOrNull()
                        ?: runCatching { getPropStr.invoke(doc, "name") as? String }.getOrNull())
                        .orEmpty()
                        .ifBlank { fnId }
                    val desc = runCatching { getPropStr.invoke(doc, "description") as? String }.getOrNull().orEmpty()
                    val paramKeys = extractParameterKeysFromDoc(doc)
                    out.add(
                        DiscoveredAppFunction(
                            packageName = pkg,
                            functionId = fnId,
                            title = title,
                            description = desc,
                            parameterKeys = paramKeys,
                        )
                    )
                }
                return out
            } finally {
                (searchResults as? Closeable)?.let { runCatching { it.close() } }
            }
        } finally {
            (session as? Closeable)?.let { runCatching { it.close() } }
        }
    }.getOrElse { emptyList() }

    private fun extractParameterKeysFromDoc(doc: Any): List<String> = runCatching {
        val docArray = doc.javaClass
            .getMethod("getPropertyDocumentArray", String::class.java)
            .invoke(doc, "parameters") as? Array<*> ?: return emptyList()
        docArray.mapNotNull { paramDoc ->
            if (paramDoc == null) null
            else runCatching {
                paramDoc.javaClass.getMethod("getPropertyString", String::class.java)
                    .invoke(paramDoc, "name") as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    /**
     * 生成合法消毒的 tool name；超长（>64）时保留前缀并附加稳定短哈希，避免同包多函数截断撞名。
     */
    internal fun buildToolName(fn: DiscoveredAppFunction): String {
        val cleanPkg = fn.packageName.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val cleanId = fn.functionId.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val rawName = "appfn_${cleanPkg}_$cleanId"
        if (rawName.length <= 64) return rawName
        val hash = stableShortHash("${fn.packageName}:${fn.functionId}")
        return "${rawName.substring(0, 64 - 1 - hash.length)}_$hash"
    }

    private fun stableShortHash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(8) {
            for (i in 0 until 4) {
                val b = bytes[i].toInt() and 0xFF
                append(((b ushr 4) and 0xF).toString(16))
                append((b and 0xF).toString(16))
            }
        }
    }

    /**
     * 将已探测的 [DiscoveredAppFunction] 转换为标准的 MCP 工具描述符 [McpToolDescriptor]。
     * 生成合法消毒且唯一不碰撞的 tool name 及 JSON Schema，支持作为本地工具直接注册到 Agent 规划执行体系中。
     */
    fun toMcpToolDefs(functions: List<DiscoveredAppFunction>): List<McpToolDescriptor> {
        return toToolDescriptorsAndIds(functions).first
    }

    /**
     * 将 [DiscoveredAppFunction] 列表映射为去重且名称唯一的 MCP 工具描述符列表以及由工具名到原始 AppFunction 契约对象的查找字典。
     */
    fun toToolDescriptorsAndIds(functions: List<DiscoveredAppFunction>): Pair<List<McpToolDescriptor>, Map<String, DiscoveredAppFunction>> {
        val seenFnKeys = HashSet<String>()
        val usedToolNames = HashSet<String>()
        val descriptors = ArrayList<McpToolDescriptor>(functions.size)
        val idMap = LinkedHashMap<String, DiscoveredAppFunction>(functions.size)

        for (fn in functions) {
            if (!seenFnKeys.add("${fn.packageName}:${fn.functionId}")) continue

            val baseName = buildToolName(fn)
            var uniqueName = baseName
            var seq = 2
            while (!usedToolNames.add(uniqueName)) {
                val suffix = "_$seq"
                val maxBase = (64 - suffix.length).coerceAtLeast(1)
                val prefix = if (baseName.length > maxBase) baseName.substring(0, maxBase) else baseName
                uniqueName = "$prefix$suffix"
                seq++
            }

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

            descriptors.add(
                McpToolDescriptor(
                    name = uniqueName,
                    description = desc,
                    inputSchema = schema,
                    annotations = McpAnnotations(openWorldHint = true),
                    estimatedCostUnits = 0L,
                )
            )
            idMap[uniqueName] = fn
        }
        return descriptors to idMap
    }

    /**
     * 探测当前系统中的 AppFunctions 并转换为工具描述符列表以及工具名映射表。
     */
    fun toToolDescriptorsAndIds(context: Context): Pair<List<McpToolDescriptor>, Map<String, DiscoveredAppFunction>> {
        return toToolDescriptorsAndIds(discoverAppFunctions(context))
    }

    /**
     * 在 Android 16+ (API 36+) 上执行指定的 AppFunction。
     * 采用 android.app.appfunctions.AppFunctionManager 真实异步 API：
     * ExecuteAppFunctionRequest.Builder + Executor + CancellationSignal + OutcomeReceiver 动态代理桥接。
     * 低于 Android 16 或反射/执行失败时优雅降级，绝对不抛出未捕获异常。
     */
    suspend fun executeAppFunction(
        context: Context,
        packageName: String,
        functionId: String,
        argsJson: String = "{}",
    ): AppFunctionExecutionResult {
        if (Build.VERSION.SDK_INT < MIN_APP_FUNCTIONS_SDK_INT) {
            return AppFunctionExecutionResult(
                ok = false,
                output = "",
                error = "unsupported_sdk",
            )
        }

        val appFunctionManager = runCatching {
            context.getSystemService(APP_FUNCTION_SERVICE_NAME)
        }.getOrNull() ?: return AppFunctionExecutionResult(
            ok = false,
            output = "",
            error = "service_unavailable",
        )

        val executionResult = withTimeoutOrNull(EXECUTE_TIMEOUT_MS) {
            runCatching {
                val requestClass = Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest")
                val builderClass = Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest\$Builder")
                val outcomeReceiverClass = Class.forName("android.os.OutcomeReceiver")

                val requestBuilder = builderClass
                    .getConstructor(String::class.java, String::class.java)
                    .newInstance(packageName, functionId)

                buildParametersDocument(argsJson)?.let { paramsDoc ->
                    val genericDocClass = Class.forName("android.app.appsearch.GenericDocument")
                    builderClass.getMethod("setParameters", genericDocClass).invoke(requestBuilder, paramsDoc)
                }

                val request = builderClass.getMethod("build").invoke(requestBuilder)
                val executeMethod = appFunctionManager.javaClass.getMethod(
                    "executeAppFunction",
                    requestClass,
                    Executor::class.java,
                    CancellationSignal::class.java,
                    outcomeReceiverClass,
                )

                val cancellationSignal = CancellationSignal()
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation {
                        runCatching { cancellationSignal.cancel() }
                    }

                    val receiverProxy = Proxy.newProxyInstance(
                        outcomeReceiverClass.classLoader,
                        arrayOf(outcomeReceiverClass),
                    ) { _, method, args ->
                        when (method.name) {
                            "onResult" -> {
                                val response = args?.firstOrNull()
                                val output = serializeExecuteResponse(response)
                                if (cont.isActive) {
                                    cont.resume(
                                        AppFunctionExecutionResult(
                                            ok = true,
                                            output = output,
                                            error = null,
                                        )
                                    )
                                }
                            }
                            "onError" -> {
                                val errObj = args?.firstOrNull()
                                val errMsg = extractAppFunctionError(errObj)
                                if (cont.isActive) {
                                    cont.resume(
                                        AppFunctionExecutionResult(
                                            ok = false,
                                            output = "",
                                            error = errMsg,
                                        )
                                    )
                                }
                            }
                        }
                        null
                    }

                    runCatching {
                        executeMethod.invoke(
                            appFunctionManager,
                            request,
                            bridgeExecutor,
                            cancellationSignal,
                            receiverProxy,
                        )
                    }.onFailure { invokeErr ->
                        val cause = invokeErr.cause ?: invokeErr
                        if (cont.isActive) {
                            cont.resume(
                                AppFunctionExecutionResult(
                                    ok = false,
                                    output = "",
                                    error = cause.message ?: cause.javaClass.simpleName,
                                )
                            )
                        }
                    }
                }
            }.getOrElse { e ->
                val cause = e.cause ?: e
                val errName = when (cause) {
                    is NoSuchMethodException -> "method_not_found"
                    is ClassNotFoundException -> "api_unavailable"
                    else -> cause.message ?: cause.javaClass.simpleName
                }
                AppFunctionExecutionResult(
                    ok = false,
                    output = "",
                    error = errName,
                )
            }
        }

        return executionResult ?: AppFunctionExecutionResult(
            ok = false,
            output = "",
            error = "execution_timeout",
        )
    }

    /**
     * 将 JSON 字符串参数转换为 Android AppSearch GenericDocument，以适配 ExecuteAppFunctionRequest.Builder.setParameters。
     */
    private fun buildParametersDocument(argsJson: String): Any? = runCatching {
        val jsonObj = Json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject ?: return null
        val docBuilderClass = Class.forName("android.app.appsearch.GenericDocument\$Builder")
        val builder = docBuilderClass
            .getConstructor(String::class.java, String::class.java, String::class.java)
            .newInstance("app_function_params", "params", "app_function_params")

        val setStr = docBuilderClass.getMethod("setPropertyString", String::class.java, Array<String>::class.java)
        val setBool = docBuilderClass.getMethod("setPropertyBoolean", String::class.java, BooleanArray::class.java)
        val setLong = docBuilderClass.getMethod("setPropertyLong", String::class.java, LongArray::class.java)
        val setDouble = docBuilderClass.getMethod("setPropertyDouble", String::class.java, DoubleArray::class.java)

        for ((key, value) in jsonObj) {
            if (value is JsonPrimitive) {
                when {
                    value.isString -> setStr.invoke(builder, key, arrayOf(value.content))
                    value.booleanOrNull != null -> setBool.invoke(builder, key, booleanArrayOf(value.booleanOrNull!!))
                    value.longOrNull != null -> setLong.invoke(builder, key, longArrayOf(value.longOrNull!!))
                    value.doubleOrNull != null -> setDouble.invoke(builder, key, doubleArrayOf(value.doubleOrNull!!))
                    else -> setStr.invoke(builder, key, arrayOf(value.content))
                }
            } else {
                setStr.invoke(builder, key, arrayOf(value.toString()))
            }
        }
        docBuilderClass.getMethod("build").invoke(builder)
    }.getOrNull()

    private fun serializeExecuteResponse(response: Any?): String {
        if (response == null) return "{}"
        return runCatching {
            val resultDoc = response.javaClass.getMethod("getResultDocument").invoke(response)
                ?: return response.toString()
            val propNames = resultDoc.javaClass.getMethod("getPropertyNames").invoke(resultDoc) as? Set<*>
            if (propNames.isNullOrEmpty()) return "{}"

            val getProp = resultDoc.javaClass.getMethod("getProperty", String::class.java)
            buildJsonObject {
                for (name in propNames) {
                    val key = name as? String ?: continue
                    val raw = runCatching { getProp.invoke(resultDoc, key) }.getOrNull()
                    when (raw) {
                        is Array<*> -> {
                            if (raw.size == 1) put(key, raw[0]?.toString() ?: "")
                            else put(key, buildJsonArray { raw.forEach { add(JsonPrimitive(it?.toString() ?: "")) } })
                        }
                        is BooleanArray -> if (raw.size == 1) put(key, raw[0]) else put(key, buildJsonArray { raw.forEach { add(JsonPrimitive(it)) } })
                        is LongArray -> if (raw.size == 1) put(key, raw[0]) else put(key, buildJsonArray { raw.forEach { add(JsonPrimitive(it)) } })
                        is DoubleArray -> if (raw.size == 1) put(key, raw[0]) else put(key, buildJsonArray { raw.forEach { add(JsonPrimitive(it)) } })
                        null -> Unit
                        else -> put(key, raw.toString())
                    }
                }
            }.toString()
        }.getOrElse { response.toString() }
    }

    private fun extractAppFunctionError(errObj: Any?): String {
        if (errObj == null) return "app_function_error"
        return runCatching {
            val cls = errObj.javaClass
            val code = runCatching { cls.getMethod("getErrorCode").invoke(errObj) as? Int }.getOrNull()
            val msg = runCatching { cls.getMethod("getErrorMessage").invoke(errObj) as? String }.getOrNull()
                ?: (errObj as? Throwable)?.message
            when {
                code != null && !msg.isNullOrBlank() -> "error_$code: $msg"
                code != null -> "error_$code"
                !msg.isNullOrBlank() -> msg
                else -> cls.simpleName
            }
        }.getOrElse { "app_function_error" }
    }
}

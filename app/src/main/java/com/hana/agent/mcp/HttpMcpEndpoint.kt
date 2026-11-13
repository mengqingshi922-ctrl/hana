package com.hana.agent.mcp

import com.hana.agent.core.cap.mcp.McpAnnotations
import com.hana.agent.core.cap.mcp.McpEndpoint
import com.hana.agent.core.cap.mcp.McpResult
import com.hana.agent.core.cap.mcp.McpToolDescriptor
import com.hana.agent.core.mcp.Mcp2026Codec
import com.hana.agent.core.mcp.McpElicitationAction
import com.hana.agent.core.mcp.McpElicitationRequest
import com.hana.agent.core.mcp.McpElicitationResponse
import com.hana.agent.core.mcp.McpRpc
import com.hana.agent.core.mcp.McpTaskInfo
import com.hana.agent.core.net.NetProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class McpProtocolMode {
    AUTO,
    MODERN_2026,
    LEGACY_2025,
}

data class McpOAuth2Config(
    val staticBearerToken: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val tokenEndpoint: String? = null,
    val scope: String? = null,
    /** RFC 8707 Resource Indicator，供 MCP OAuth 2.1 受众绑定。 */
    val resource: String? = null,
    val refreshToken: String? = null,
    val authorizationCode: String? = null,
    val codeVerifier: String? = null,
    val redirectUri: String? = null,
    val protectedResourceMetadataUrl: String? = null,
) {
    val hasDynamicGrant: Boolean
        get() = !tokenEndpoint.isNullOrBlank() || !protectedResourceMetadataUrl.isNullOrBlank()
}

data class McpHttpServerConfig(
    val serverId: String,
    val endpointUrl: String,
    val protocolMode: McpProtocolMode = McpProtocolMode.AUTO,
    val oauth: McpOAuth2Config = McpOAuth2Config(),
    val headers: Map<String, String> = emptyMap(),
    val clientName: String = "hana",
    val clientVersion: String = "0.1.36",
    val requestTimeoutMs: Long = 30_000L,
    val taskPollIntervalMs: Long = 250L,
    val taskPollTimeoutMs: Long = 30_000L,
)

data class McpNegotiatedSession(
    val protocolVersion: String,
    val isModern2026: Boolean,
    val sessionId: String?,
    val capabilities: JsonObject,
    val instructions: String? = null,
)

class McpHttpException(
    val httpStatus: Int,
    message: String,
    val rpcCode: Int? = null,
    val wwwAuthenticate: String? = null,
) : IOException(message)

fun interface McpTokenProvider {
    suspend fun getAccessToken(forceRefresh: Boolean): String?
}

/**
 * OAuth 2.1 Bearer Token 管理器：
 * 支持静态 Bearer、Client Credentials、Authorization Code + PKCE、Refresh Token 轮转，
 * 以及基于 RFC 9728（Protected Resource Metadata）和 RFC 8414 的动态 Token 端点发现。
 */
class OAuth2TokenManager(
    private val http: OkHttpClient,
    private val configProvider: () -> McpOAuth2Config,
    private val clock: () -> Long = System::currentTimeMillis,
) : McpTokenProvider {

    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var expiresAtMs: Long = 0L
    private var currentRefreshToken: String? = null
    private var lastConfig: McpOAuth2Config? = null
    private var discoveredTokenEndpoint: String? = null
    private var consumedAuthCode: String? = null

    fun invalidate() {
        cachedToken = null
        expiresAtMs = 0L
        discoveredTokenEndpoint = null
    }

    suspend fun updateResourceMetadataHint(wwwAuthenticate: String?) {
        val metaUrl = extractResourceMetadataUrl(wwwAuthenticate) ?: return
        mutex.withLock {
            discoveredTokenEndpoint = discoverTokenEndpoint(metaUrl)
        }
    }

    override suspend fun getAccessToken(forceRefresh: Boolean): String? = mutex.withLock {
        val cfg = configProvider()
        if (cfg != lastConfig) {
            cachedToken = null
            expiresAtMs = 0L
            currentRefreshToken = cfg.refreshToken
            discoveredTokenEndpoint = null
            consumedAuthCode = null
            lastConfig = cfg
        }

        if (!forceRefresh && !cachedToken.isNullOrBlank()) {
            if (expiresAtMs == 0L || clock() + EXPIRY_SKEW_MS < expiresAtMs) {
                return@withLock cachedToken
            }
        }

        if (!forceRefresh && !cfg.staticBearerToken.isNullOrBlank() && !cfg.hasDynamicGrant) {
            cachedToken = cfg.staticBearerToken
            expiresAtMs = 0L
            return@withLock cachedToken
        }

        val tokenEndpoint = cfg.tokenEndpoint?.takeIf { it.isNotBlank() }
            ?: discoveredTokenEndpoint
            ?: cfg.protectedResourceMetadataUrl?.takeIf { it.isNotBlank() }?.let { discoverTokenEndpoint(it) }
                ?.also { discoveredTokenEndpoint = it }

        if (tokenEndpoint.isNullOrBlank()) {
            return@withLock cfg.staticBearerToken?.takeIf { it.isNotBlank() }
        }

        val tokenReply = requestToken(tokenEndpoint, cfg)
        cachedToken = tokenReply.accessToken
        expiresAtMs = if (tokenReply.expiresInSec != null && tokenReply.expiresInSec > 0L) {
            clock() + tokenReply.expiresInSec * 1000L
        } else {
            0L
        }
        if (!tokenReply.refreshToken.isNullOrBlank()) {
            currentRefreshToken = tokenReply.refreshToken
        }
        return@withLock cachedToken
    }

    private data class TokenResponse(
        val accessToken: String,
        val expiresInSec: Long?,
        val refreshToken: String?,
    )

    private suspend fun requestToken(tokenEndpoint: String, cfg: McpOAuth2Config): TokenResponse {
        val form = FormBody.Builder()
        val refresh = currentRefreshToken?.takeIf { it.isNotBlank() }
        val authCode = cfg.authorizationCode?.takeIf { it.isNotBlank() && it != consumedAuthCode }

        when {
            refresh != null -> {
                form.add("grant_type", "refresh_token")
                form.add("refresh_token", refresh)
            }
            authCode != null -> {
                form.add("grant_type", "authorization_code")
                form.add("code", authCode)
                cfg.redirectUri?.takeIf { it.isNotBlank() }?.let { form.add("redirect_uri", it) }
                cfg.codeVerifier?.takeIf { it.isNotBlank() }?.let { form.add("code_verifier", it) }
                consumedAuthCode = authCode
            }
            else -> {
                form.add("grant_type", "client_credentials")
            }
        }

        cfg.clientId?.takeIf { it.isNotBlank() }?.let { form.add("client_id", it) }
        cfg.clientSecret?.takeIf { it.isNotBlank() }?.let { form.add("client_secret", it) }
        cfg.scope?.takeIf { it.isNotBlank() }?.let { form.add("scope", it) }
        cfg.resource?.takeIf { it.isNotBlank() }?.let { form.add("resource", it) }

        val req = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .post(form.build())
            .build()

        val (status, body) = executeSimple(http, req)
        if (status !in 200..299) {
            throw McpHttpException(status, "OAuth 2.1 token request failed: HTTP $status ${body.take(200)}")
        }
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw McpHttpException(status, "Invalid OAuth 2.1 token response")
        val accessToken = (obj["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw McpHttpException(status, "Missing access_token in OAuth 2.1 response")
        val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.longOrNull
        val newRefresh = (obj["refresh_token"] as? JsonPrimitive)?.contentOrNull
        return TokenResponse(accessToken, expiresIn, newRefresh)
    }

    private suspend fun discoverTokenEndpoint(resourceMetadataUrl: String): String? {
        val (status, body) = runCatching {
            executeSimple(
                http,
                Request.Builder().url(resourceMetadataUrl).header("Accept", "application/json").get().build(),
            )
        }.getOrNull() ?: return null
        if (status !in 200..299) return null

        val metaObj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        (metaObj["token_endpoint"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }

        val authServer = (metaObj["authorization_servers"] as? JsonArray)
            ?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.trimEnd('/') ?: return null

        val asMetaUrl = "$authServer/.well-known/oauth-authorization-server"
        val (asStatus, asBody) = runCatching {
            executeSimple(
                http,
                Request.Builder().url(asMetaUrl).header("Accept", "application/json").get().build(),
            )
        }.getOrNull() ?: return null
        if (asStatus !in 200..299) return null
        val asObj = runCatching { json.parseToJsonElement(asBody).jsonObject }.getOrNull() ?: return null
        return (asObj["token_endpoint"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val EXPIRY_SKEW_MS = 30_000L
        private val RESOURCE_META_REGEX = Regex("""resource_metadata\s*=\s*"([^"]+)"""")
        private val json = Json { ignoreUnknownKeys = true }

        fun extractResourceMetadataUrl(wwwAuthenticate: String?): String? {
            if (wwwAuthenticate.isNullOrBlank()) return null
            return RESOURCE_META_REGEX.find(wwwAuthenticate)?.groupValues?.getOrNull(1)
        }
    }
}

/**
 * 基于 OkHttp 的真实 MCP 2026 客户端端点实现：
 * - 实现 `:core` [McpEndpoint] 契约，可直接封装为 [com.hana.agent.core.cap.mcp.McpConnection] 挂载到工具调度器；
 * - 支持 Streamable HTTP（`application/json` 单体响应 + `text/event-stream` SSE 事件流与双向 `elicitation/create`）；
 * - 支持 MCP `2026-07-28` 无状态显式头协议与 `2025-11-25` 会话握手自动协商降级；
 * - 支持 OAuth 2.1 Bearer Token、动态配置热更新及 `tasks/get|result|cancel` 异步任务操作。
 */
class HttpMcpEndpoint(
    initialConfig: McpHttpServerConfig,
    private val http: OkHttpClient = defaultHttpClient(initialConfig.requestTimeoutMs),
    customTokenProvider: McpTokenProvider? = null,
    private val elicitationHandler: (suspend (McpElicitationRequest) -> McpElicitationResponse)? = null,
    private val onNotification: ((method: String, params: JsonObject?) -> Unit)? = null,
) : McpEndpoint {

    private val configRef = AtomicReference(initialConfig)
    private val idSeq = AtomicLong(1L)
    private val sessionLock = Mutex()
    private val internalOAuth = OAuth2TokenManager(http, { configRef.get().oauth })
    private val tokenProvider: McpTokenProvider = customTokenProvider ?: internalOAuth

    @Volatile
    private var negotiatedSession: McpNegotiatedSession? = null

    @Volatile
    private var cachedTools: List<McpToolDescriptor>? = null

    @Volatile
    private var lastEventId: String? = null

    override val serverId: String
        get() = configRef.get().serverId

    val currentConfig: McpHttpServerConfig
        get() = configRef.get()

    val activeSession: McpNegotiatedSession?
        get() = negotiatedSession

    /**
     * 动态更新配置（如变更 endpointUrl、OAuth 2.1 凭据、自定义请求头或协议模式）：
     * 立即清空旧会话与工具缓存，下次调用自动重新协商并应用新 Token。
     */
    fun updateConfig(newConfig: McpHttpServerConfig) {
        configRef.set(newConfig)
        negotiatedSession = null
        cachedTools = null
        lastEventId = null
        internalOAuth.invalidate()
    }

    suspend fun connect(): McpNegotiatedSession = sessionLock.withLock {
        negotiatedSession?.let { return@withLock it }
        val cfg = configRef.get()
        val session = when (cfg.protocolMode) {
            McpProtocolMode.MODERN_2026 -> discoverModern2026(cfg)
            McpProtocolMode.LEGACY_2025 -> initializeLegacy2025(cfg)
            McpProtocolMode.AUTO -> {
                try {
                    discoverModern2026(cfg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: McpHttpException) {
                    if (e.httpStatus == 401 || e.httpStatus == 403) throw e
                    initializeLegacy2025(cfg)
                }
            }
        }
        negotiatedSession = session
        session
    }

    suspend fun refreshTools(): List<McpToolDescriptor> {
        ensureSession()
        val collected = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null
        do {
            val params = buildJsonObject {
                cursor?.let { put("cursor", it) }
            }
            val result = rpcCall("tools/list", params)
            val arr = (result["tools"] as? JsonArray).orEmpty()
            for (el in arr) {
                val obj = el as? JsonObject ?: continue
                parseToolDescriptor(obj)?.let(collected::add)
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        } while (cursor != null)
        cachedTools = collected.toList()
        return cachedTools!!
    }

    override fun tools(): List<McpToolDescriptor> {
        cachedTools?.let { return it }
        return runBlocking(Dispatchers.IO) { refreshTools() }
    }

    override suspend fun call(name: String, arguments: JsonObject): McpResult {
        ensureSession()
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val result = rpcCall("tools/call", params, toolName = name)

        // 若服务端按 MCP 2026 Tasks 规范返回了异步 task 对象，自动轮询至终态取回最终结果
        val createdTask = (result["task"] as? JsonObject)?.let { runCatching { Mcp2026Codec.decodeTaskInfo(it) }.getOrNull() }
        if (createdTask != null) {
            return awaitTaskResult(createdTask)
        }

        return parseToolCallResult(result)
    }

    suspend fun getTask(taskId: String): McpTaskInfo {
        ensureSession()
        val result = rpcCall(Mcp2026Codec.METHOD_TASKS_GET, buildJsonObject { put("taskId", taskId) })
        return Mcp2026Codec.decodeTaskInfo(result)
    }

    suspend fun getTaskResult(taskId: String): McpResult {
        ensureSession()
        val result = rpcCall(Mcp2026Codec.METHOD_TASKS_RESULT, buildJsonObject { put("taskId", taskId) })
        return parseToolCallResult(result)
    }

    suspend fun cancelTask(taskId: String): McpTaskInfo {
        ensureSession()
        val result = rpcCall(Mcp2026Codec.METHOD_TASKS_CANCEL, buildJsonObject { put("taskId", taskId) })
        return Mcp2026Codec.decodeTaskInfo(result)
    }

    suspend fun ping() {
        ensureSession()
        rpcCall("ping", JsonObject(emptyMap()))
    }

    /**
     * 主动打开 GET SSE 长连接接收服务端推送的通知或 `elicitation/create` 交互请求。
     */
    suspend fun listenSseStream() {
        val cfg = configRef.get()
        val session = ensureSession()
        var token = tokenProvider.getAccessToken(false)
        var req = buildGetSseRequest(cfg, session, token)
        val outcome = executeStreamableRequest(req, targetId = null, cfg = cfg, session = session)
        if (outcome.unauthorized) {
            internalOAuth.updateResourceMetadataHint(outcome.wwwAuthenticate)
            token = tokenProvider.getAccessToken(true)
            req = buildGetSseRequest(cfg, session, token)
            executeStreamableRequest(req, targetId = null, cfg = cfg, session = session)
        }
    }

    suspend fun close() {
        val cfg = configRef.get()
        val sid = negotiatedSession?.sessionId
        negotiatedSession = null
        cachedTools = null
        if (!sid.isNullOrBlank()) {
            val token = runCatching { tokenProvider.getAccessToken(false) }.getOrNull()
            val req = Request.Builder()
                .url(cfg.endpointUrl)
                .apply {
                    token?.let { header("Authorization", "Bearer $it") }
                    header("Mcp-Session-Id", sid)
                    cfg.headers.forEach { (k, v) -> header(k, v) }
                }
                .delete()
                .build()
            runCatching { executeSimple(http, req) }
        }
    }

    private suspend fun awaitTaskResult(initial: McpTaskInfo): McpResult {
        val cfg = configRef.get()
        val deadline = System.currentTimeMillis() + cfg.taskPollTimeoutMs
        var current = initial
        while (!current.status.isTerminal) {
            if (System.currentTimeMillis() >= deadline) {
                return McpResult(text = "MCP task ${current.taskId} timed out (status=${current.status.wire})", isError = true)
            }
            val sleepMs = current.pollIntervalMs ?: cfg.taskPollIntervalMs
            delay(sleepMs.coerceIn(20L, 5_000L))
            current = getTask(current.taskId)
        }
        return getTaskResult(current.taskId)
    }

    private suspend fun ensureSession(): McpNegotiatedSession = negotiatedSession ?: connect()

    private suspend fun discoverModern2026(cfg: McpHttpServerConfig): McpNegotiatedSession {
        val result = sendRpcRaw(
            cfg = cfg,
            session = McpNegotiatedSession(McpRpc.MODERN, isModern2026 = true, sessionId = null, capabilities = JsonObject(emptyMap())),
            method = "server/discover",
            params = JsonObject(emptyMap()),
            forceModern = true,
        )
        val supported = (result["supportedVersions"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (supported.isNotEmpty() && McpRpc.MODERN !in supported) {
            throw McpHttpException(400, "Server does not support MCP ${McpRpc.MODERN}")
        }
        return McpNegotiatedSession(
            protocolVersion = McpRpc.MODERN,
            isModern2026 = true,
            sessionId = null,
            capabilities = (result["capabilities"] as? JsonObject) ?: JsonObject(emptyMap()),
            instructions = (result["instructions"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    private suspend fun initializeLegacy2025(cfg: McpHttpServerConfig): McpNegotiatedSession {
        val initParams = buildJsonObject {
            put("protocolVersion", McpRpc.LEGACY_VERSIONS.first())
            putJsonObject("capabilities") {
                if (elicitationHandler != null) {
                    putJsonObject("elicitation") {
                        putJsonObject("form") {}
                        putJsonObject("url") {}
                    }
                }
            }
            putJsonObject("clientInfo") {
                put("name", cfg.clientName)
                put("version", cfg.clientVersion)
            }
        }
        val (result, sessionId) = sendRpcRawWithHeaders(
            cfg = cfg,
            session = McpNegotiatedSession(McpRpc.LEGACY_VERSIONS.first(), isModern2026 = false, sessionId = null, capabilities = JsonObject(emptyMap())),
            method = "initialize",
            params = initParams,
            forceModern = false,
        )
        val negotiatedVersion = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull
            ?: McpRpc.LEGACY_VERSIONS.first()
        val session = McpNegotiatedSession(
            protocolVersion = negotiatedVersion,
            isModern2026 = false,
            sessionId = sessionId,
            capabilities = (result["capabilities"] as? JsonObject) ?: JsonObject(emptyMap()),
            instructions = (result["instructions"] as? JsonPrimitive)?.contentOrNull,
        )
        sendNotification(cfg, session, "notifications/initialized", JsonObject(emptyMap()))
        return session
    }

    private suspend fun rpcCall(method: String, params: JsonObject, toolName: String? = null): JsonObject {
        val cfg = configRef.get()
        val session = ensureSession()
        return sendRpcRaw(cfg, session, method, params, forceModern = session.isModern2026, toolName = toolName)
    }

    private suspend fun sendRpcRaw(
        cfg: McpHttpServerConfig,
        session: McpNegotiatedSession,
        method: String,
        params: JsonObject,
        forceModern: Boolean,
        toolName: String? = null,
    ): JsonObject = sendRpcRawWithHeaders(cfg, session, method, params, forceModern, toolName).first

    private suspend fun sendRpcRawWithHeaders(
        cfg: McpHttpServerConfig,
        session: McpNegotiatedSession,
        method: String,
        params: JsonObject,
        forceModern: Boolean,
        toolName: String? = null,
    ): Pair<JsonObject, String?> {
        val rpcId = JsonPrimitive(idSeq.getAndIncrement())
        val effectiveParams = if (forceModern) {
            buildJsonObject {
                params.forEach { (k, v) -> put(k, v) }
                putJsonObject("_meta") {
                    put(McpRpc.META_VERSION, session.protocolVersion)
                    putJsonObject(McpRpc.META_CLIENT) {
                        put("name", cfg.clientName)
                        put("version", cfg.clientVersion)
                    }
                    putJsonObject(McpRpc.META_CAPABILITIES) {
                        if (elicitationHandler != null) {
                            putJsonObject("elicitation") {
                                putJsonObject("form") {}
                                putJsonObject("url") {}
                            }
                        }
                    }
                }
            }
        } else {
            params
        }
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", rpcId)
            put("method", method)
            put("params", effectiveParams)
        }.toString()

        var token = tokenProvider.getAccessToken(false)
        var outcome = executeStreamableRequest(
            req = buildPostRequest(cfg, session, method, payload, token, forceModern, toolName),
            targetId = rpcId,
            cfg = cfg,
            session = session,
        )
        if (outcome.unauthorized) {
            internalOAuth.updateResourceMetadataHint(outcome.wwwAuthenticate)
            token = tokenProvider.getAccessToken(true)
            outcome = executeStreamableRequest(
                req = buildPostRequest(cfg, session, method, payload, token, forceModern, toolName),
                targetId = rpcId,
                cfg = cfg,
                session = session,
            )
        }
        if (outcome.unauthorized) {
            throw McpHttpException(401, "MCP server rejected Bearer token (HTTP 401)", wwwAuthenticate = outcome.wwwAuthenticate)
        }
        if (outcome.httpStatus !in 200..299) {
            throw McpHttpException(
                outcome.httpStatus,
                "MCP HTTP ${outcome.httpStatus}: ${outcome.rawBody.take(300)}",
                rpcCode = outcome.rpcErrorCode,
            )
        }
        val rpcObj = outcome.matchedResponse
            ?: throw McpHttpException(outcome.httpStatus, "No matching JSON-RPC response for id=$rpcId")

        val errObj = rpcObj["error"] as? JsonObject
        if (errObj != null) {
            val code = (errObj["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val msg = (errObj["message"] as? JsonPrimitive)?.contentOrNull ?: errObj.toString()
            throw McpHttpException(outcome.httpStatus, "MCP RPC error ($code): $msg", rpcCode = code)
        }
        val resObj = rpcObj["result"] as? JsonObject
            ?: throw McpHttpException(outcome.httpStatus, "Missing JSON-RPC result object")
        return resObj to (outcome.sessionId ?: session.sessionId)
    }

    private suspend fun sendNotification(
        cfg: McpHttpServerConfig,
        session: McpNegotiatedSession,
        method: String,
        params: JsonObject,
    ) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params.isNotEmpty()) put("params", params)
        }.toString()
        val token = tokenProvider.getAccessToken(false)
        val req = buildPostRequest(cfg, session, method, payload, token, forceModern = false, toolName = null)
        executeSimple(http, req)
    }

    private suspend fun sendJsonRpcResponse(
        cfg: McpHttpServerConfig,
        session: McpNegotiatedSession,
        id: JsonElement,
        result: JsonObject? = null,
        error: Pair<Int, String>? = null,
    ) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (error != null) {
                putJsonObject("error") {
                    put("code", error.first)
                    put("message", error.second)
                }
            } else {
                put("result", result ?: JsonObject(emptyMap()))
            }
        }.toString()
        val token = tokenProvider.getAccessToken(false)
        val req = buildPostRequest(cfg, session, method = "", body = payload, token = token, forceModern = false, toolName = null, omitModernMethodHeaders = true)
        runCatching { executeSimple(http, req) }
    }

    private fun buildPostRequest(
        cfg: McpHttpServerConfig,
        session: McpNegotiatedSession,
        method: String,
        body: String,
        token: String?,
        forceModern: Boolean,
        toolName: String?,
        omitModernMethodHeaders: Boolean = false,
    ): Request {
        val b = Request.Builder()
            .url(cfg.endpointUrl)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
        if (!token.isNullOrBlank()) {
            b.header("Authorization", "Bearer $token")
        }
        session.sessionId?.takeIf { it.isNotBlank() }?.let { b.header("Mcp-Session-Id", it) }
        if (forceModern && !omitModernMethodHeaders) {
            b.header("MCP-Protocol-Version", session.protocolVersion)
            if (method.isNotEmpty()) b.header("Mcp-Method", method)
            if (method == "tools/call" && !toolName.isNullOrBlank()) {
                b.header("Mcp-Name", McpRpc.encodeHeader(toolName))
            }
        } else if (!session.isModern2026) {
            b.header("MCP-Protocol-Version", session.protocolVersion)
        }
        cfg.headers.forEach { (k, v) -> b.header(k, v) }
        return b.post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    private fun buildGetSseRequest(
        cfg: McpHttpServerConfig,
        session: McpNegotiatedSession,
        token: String?,
    ): Request {
        val b = Request.Builder()
            .url(cfg.endpointUrl)
            .header("Accept", "text/event-stream")
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        session.sessionId?.takeIf { it.isNotBlank() }?.let { b.header("Mcp-Session-Id", it) }
        b.header("MCP-Protocol-Version", session.protocolVersion)
        lastEventId?.takeIf { it.isNotBlank() }?.let { b.header("Last-Event-ID", it) }
        cfg.headers.forEach { (k, v) -> b.header(k, v) }
        return b.get().build()
    }

    private data class StreamOutcome(
        val httpStatus: Int,
        val unauthorized: Boolean = false,
        val wwwAuthenticate: String? = null,
        val sessionId: String? = null,
        val rawBody: String = "",
        val rpcErrorCode: Int? = null,
        val matchedResponse: JsonObject? = null,
    )

    private suspend fun executeStreamableRequest(
        req: Request,
        targetId: JsonElement?,
        cfg: McpHttpServerConfig,
        session: McpNegotiatedSession,
    ): StreamOutcome = withContext(Dispatchers.IO) {
        val callbackJobs = mutableListOf<Job>()
        val scope = CoroutineScope(Dispatchers.IO)
        val rawOutcome = suspendCancellableCoroutine<StreamOutcome> { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val code = resp.code
                        val wwwAuth = resp.header("WWW-Authenticate")
                        val sid = resp.header("Mcp-Session-Id")
                        if (code == 401) {
                            if (cont.isActive) {
                                cont.resume(StreamOutcome(code, unauthorized = true, wwwAuthenticate = wwwAuth, sessionId = sid))
                            }
                            return
                        }

                        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                        if (code !in 200..299) {
                            val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                            val errCode = runCatching {
                                (json.parseToJsonElement(errBody).jsonObject["error"] as? JsonObject)
                                    ?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
                            }.getOrNull()
                            val matched = runCatching { json.parseToJsonElement(errBody) as? JsonObject }.getOrNull()
                            if (cont.isActive) {
                                cont.resume(
                                    StreamOutcome(
                                        httpStatus = code,
                                        sessionId = sid,
                                        rawBody = errBody,
                                        rpcErrorCode = errCode,
                                        matchedResponse = matched,
                                    ),
                                )
                            }
                            return
                        }

                        if (contentType.contains("text/event-stream")) {
                            val source = resp.body?.source()
                            if (source == null) {
                                if (cont.isActive) cont.resume(StreamOutcome(code, sessionId = sid))
                                return
                            }
                            var matched: JsonObject? = null
                            val dataLines = mutableListOf<String>()

                            fun handlePayload(rawData: String) {
                                val trimmed = rawData.trim()
                                if (trimmed.isEmpty()) return
                                val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                                    ?: return
                                val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                                val msgId = obj["id"]
                                when {
                                    method != null && msgId != null -> {
                                        // 服务端经 SSE 下发的请求（如 elicitation/create）
                                        val job = scope.launch {
                                            dispatchServerRequest(cfg, session, msgId, method, obj["params"] as? JsonObject ?: JsonObject(emptyMap()))
                                        }
                                        synchronized(callbackJobs) { callbackJobs.add(job) }
                                    }
                                    method != null -> {
                                        onNotification?.invoke(method, obj["params"] as? JsonObject)
                                    }
                                    targetId != null && msgId == targetId -> {
                                        matched = obj
                                    }
                                    targetId == null && ("result" in obj || "error" in obj) -> {
                                        matched = obj
                                    }
                                }
                            }

                            try {
                                while (cont.isActive) {
                                    val line = source.readUtf8Line() ?: break
                                    when {
                                        line.isEmpty() -> {
                                            if (dataLines.isNotEmpty()) {
                                                handlePayload(dataLines.joinToString("\n"))
                                                dataLines.clear()
                                                if (targetId != null && matched != null) break
                                            }
                                        }
                                        line.startsWith(":") -> Unit // SSE 注释心跳
                                        line.startsWith("id:") -> {
                                            lastEventId = line.removePrefix("id:").trim()
                                        }
                                        line.startsWith("data:") -> {
                                            dataLines += line.removePrefix("data:").removePrefix(" ")
                                        }
                                    }
                                }
                                if (dataLines.isNotEmpty() && matched == null) {
                                    handlePayload(dataLines.joinToString("\n"))
                                    dataLines.clear()
                                }
                                if (cont.isActive) {
                                    cont.resume(StreamOutcome(code, sessionId = sid, matchedResponse = matched))
                                }
                            } catch (e: IOException) {
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                            return
                        }

                        // 标准 application/json 单体响应
                        val bodyStr = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        val parsed = runCatching { json.parseToJsonElement(bodyStr) as? JsonObject }.getOrNull()
                        if (cont.isActive) {
                            cont.resume(
                                StreamOutcome(
                                    httpStatus = code,
                                    sessionId = sid,
                                    rawBody = bodyStr,
                                    matchedResponse = parsed,
                                ),
                            )
                        }
                    }
                }
            })
        }
        val pendingJobs = synchronized(callbackJobs) { callbackJobs.toList() }
        pendingJobs.forEach { it.join() }
        rawOutcome
    }

    private suspend fun dispatchServerRequest(
        cfg: McpHttpServerConfig,
        session: McpNegotiatedSession,
        id: JsonElement,
        method: String,
        params: JsonObject,
    ) {
        if (method == Mcp2026Codec.METHOD_ELICITATION_CREATE) {
            val response = try {
                val req = Mcp2026Codec.decodeElicitationCreateParams(params)
                elicitationHandler?.invoke(req) ?: McpElicitationResponse(McpElicitationAction.DECLINE)
            } catch (e: Exception) {
                sendJsonRpcResponse(cfg, session, id, error = McpRpc.INVALID_PARAMS to (e.message ?: "Invalid elicitation params"))
                return
            }
            sendJsonRpcResponse(cfg, session, id, result = Mcp2026Codec.encodeElicitationResponse(response))
        } else {
            sendJsonRpcResponse(cfg, session, id, error = McpRpc.METHOD_NOT_FOUND to "Unsupported client request: $method")
        }
    }

    private fun parseToolDescriptor(obj: JsonObject): McpToolDescriptor? {
        val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val inputSchema = (obj["inputSchema"] as? JsonObject) ?: JsonObject(emptyMap())
        val annObj = obj["annotations"] as? JsonObject
        val annotations = McpAnnotations(
            readOnlyHint = (annObj?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull,
            destructiveHint = (annObj?.get("destructiveHint") as? JsonPrimitive)?.booleanOrNull,
            openWorldHint = (annObj?.get("openWorldHint") as? JsonPrimitive)?.booleanOrNull,
        )
        val estimatedCost = (obj["estimatedCostUnits"] as? JsonPrimitive)?.longOrNull ?: 0L
        return McpToolDescriptor(
            name = name,
            description = description,
            inputSchema = inputSchema,
            annotations = annotations,
            estimatedCostUnits = estimatedCost,
        )
    }

    private fun parseToolCallResult(result: JsonObject): McpResult {
        val parsed = Mcp2026Codec.decodeTaskCallResult(result)
        val cost = ((result["_meta"] as? JsonObject)?.get("costUnits") as? JsonPrimitive)?.longOrNull ?: 0L
        return McpResult(
            text = parsed.text,
            isError = parsed.isError,
            costUnits = cost,
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /** 使用全局共享 OkHttpClient 配置 MCP 客户端超时与代理 */
        fun defaultHttpClient(timeoutMs: Long = 30_000L): OkHttpClient =
            NetProxy.sharedClientBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
    }
}

private suspend fun executeSimple(http: OkHttpClient, req: Request): Pair<Int, String> =
    suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    if (cont.isActive) cont.resume(resp.code to body)
                }
            }
        })
    }

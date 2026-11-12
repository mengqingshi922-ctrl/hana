package com.hana.agent.core.model

import com.hana.agent.core.net.NetProxy
import com.hana.agent.core.net.ProxyFailure
import com.hana.agent.core.trace.TraceCall
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RawResponse(val status: Int, val retryAfter: String?, val body: String, private val onPacket: ((String) -> Unit)? = null) {
    /** Some chat endpoints send a buffered SSE body even when streaming was not requested. */
    fun replay(events: List<String>, accept: (String) -> Unit) {
        for (event in events) {
            onPacket?.invoke(event)
            accept(event)
        }
    }
}

/** 各适配器共用的 HTTP 执行(enqueue + 协程取消)与 Retry-After 解析。 */
internal object HttpSupport {
    suspend fun execute(http: OkHttpClient, req: Request): RawResponse =
        observe(req) { attempt -> executeRaw(http, req, attempt) }.also(::requireProxyAccepted)

    /** HTTP 407 只会是途中的代理要账号密码(或拒了给的),不是服务商的回复。 */
    private fun requireProxyAccepted(raw: RawResponse) {
        if (raw.status == 407) throw ModelError.Proxy(ProxyFailure.AuthFailed)
    }

    /** 连接失败:走了代理且错在代理那一段的说成代理的问题,别的照旧算网络失败。 */
    fun networkError(http: OkHttpClient, req: Request, e: IOException): ModelError =
        NetProxy.failure(http, req.url, e)?.let { ModelError.Proxy(it, e) } ?: ModelError.Network(e)

    private suspend inline fun observe(req: Request, send: (TraceCall.Attempt?) -> RawResponse): RawResponse {
        val wire = req.tag(InferenceRequest::class.java) ?: return send(null)
        val call = currentCoroutineContext()[TraceCall] ?: return send(null)
        val attempt = call.sending(wire) ?: return send(null)
        val response = send(attempt)
        if (response.status !in 200..299) {
            call.finish("error", null)
        } else if (response.body.isNotEmpty()) {
            attempt.packet(response.body)
            return response.copy(onPacket = attempt::packet)
        }
        return response
    }

    private suspend fun executeRaw(http: OkHttpClient, req: Request, attempt: TraceCall.Attempt?): RawResponse = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(networkError(http, req, e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    attempt?.httpStatus(it.code)
                    try {
                        val raw = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                        if (cont.isActive) cont.resume(raw)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(ModelError.Network(e))
                    }
                }
            }
        })
    }

    /**
     * SSE 按事件交付；取消协程会同时取消 HTTP 请求。流读到头时由调用者按各自协议判断有没有正常收尾，
     * 没收尾算 [ModelError.StreamCut]。一个事件都没有、又不是事件流格式的成功响应(比如地址指到了网页)算 [ModelError.BadResponse]。
     */
    suspend fun stream(http: OkHttpClient, req: Request, onEvent: (String) -> Unit): RawResponse =
        observe(req) { attempt -> streamRaw(http, req, attempt, onEvent) }.also(::requireProxyAccepted)

    private suspend fun streamRaw(http: OkHttpClient, req: Request, attempt: TraceCall.Attempt?, onEvent: (String) -> Unit): RawResponse = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(networkError(http, req, e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    attempt?.httpStatus(it.code)
                    try {
                        if (it.code !in 200..299) {
                            val error = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                            if (cont.isActive) cont.resume(error)
                            return
                        }
                        if (it.header("Content-Type")?.contains("application/json", ignoreCase = true) == true) {
                            if (cont.isActive) cont.resume(RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty()))
                            return
                        }
                        val source = it.body?.source() ?: throw ModelError.BadResponse("流式响应没有内容")
                        val data = mutableListOf<String>()
                        var events = 0
                        fun dispatch() {
                            if (data.isNotEmpty()) {
                                events++
                                val event = data.joinToString("\n")
                                attempt?.packet(event)
                                onEvent(event)
                                data.clear()
                            }
                        }
                        while (cont.isActive) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> dispatch()
                                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
                            }
                        }
                        if (cont.isActive) {
                            dispatch()
                            val eventStream = it.header("Content-Type")?.contains("event-stream", ignoreCase = true) == true
                            if (events == 0 && !eventStream) throw ModelError.BadResponse("返回的不是流式内容(Content-Type: ${it.header("Content-Type").orEmpty()})")
                            cont.resume(RawResponse(it.code, it.header("retry-after"), ""))
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(if (e is IOException) ModelError.Network(e) else e)
                    }
                }
            }
        })
    }

    fun retryAfterMs(header: String?, body: String? = null): Long? {
        parseHeader(header)?.let { return it }
        if (!body.isNullOrBlank()) {
            parseBody(body)?.let { return it }
        }
        return null
    }

    private fun parseHeader(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val h = header.trim()
        val secStr = if (h.endsWith("s", ignoreCase = true)) h.dropLast(1).trim() else h
        secStr.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
        try {
            val epoch = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .parse(h, java.time.Instant::from)
                .toEpochMilli()
            val diff = epoch - System.currentTimeMillis()
            return diff.coerceAtLeast(0)
        } catch (_: Exception) {}
        return null
    }

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parseBody(body: String): Long? {
        val b = body.trim()
        if (b.isEmpty()) return null
        // 1. JSON 结构化提取 (Google RPC RetryInfo, OpenAI, Anthropic 等)
        try {
            val jsonElement = jsonParser.parseToJsonElement(b)
            if (jsonElement is JsonObject) {
                findRetryDelayInJson(jsonElement)?.let { return it }
            } else if (jsonElement is JsonArray) {
                for (item in jsonElement) {
                    if (item is JsonObject) {
                        findRetryDelayInJson(item)?.let { return it }
                    }
                }
            }
        } catch (_: Exception) {}
        // 2. 正则表达式兜底提取错误描述中的时长
        val patterns = listOf(
            Regex("""(?i)(?:retry|try\s+again)\s+(?:after|in)\s+([0-9]+(?:\.[0-9]+)?)\s*(s|sec|seconds|ms|milliseconds|m|min|minutes)?"""),
            Regex("""(?i)wait\s+([0-9]+(?:\.[0-9]+)?)\s*(s|sec|seconds|ms|milliseconds|m|min|minutes)"""),
            Regex("""(?i)rate\s+limit.*?in\s+([0-9]+(?:\.[0-9]+)?)\s*(s|sec|seconds|ms|milliseconds|m|min|minutes)"""),
            Regex("""(?i)quota\s+reset.*?in\s+([0-9]+(?:\.[0-9]+)?)\s*(s|sec|seconds|ms|milliseconds|m|min|minutes)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(b)
            if (match != null) {
                val num = match.groupValues[1].toDoubleOrNull() ?: continue
                val unit = match.groupValues.getOrNull(2)?.lowercase().orEmpty()
                val ms = when {
                    unit.startsWith("m") && !unit.startsWith("ms") -> (num * 60 * 1000).toLong()
                    unit == "ms" || unit == "milliseconds" -> num.toLong()
                    else -> (num * 1000).toLong()
                }
                return ms.coerceAtLeast(0)
            }
        }
        return null
    }

    private fun findRetryDelayInJson(obj: JsonObject): Long? {
        val keys = listOf("retry_after", "retryAfter", "retryDelay", "retry_delay_seconds")
        for (key in keys) {
            obj[key]?.let { elem ->
                parseJsonDuration(elem)?.let { return it }
            }
        }
        (obj["error"] as? JsonObject)?.let { err ->
            for (key in keys) {
                err[key]?.let { elem ->
                    parseJsonDuration(elem)?.let { return it }
                }
            }
            (err["details"] as? JsonArray)?.forEach { item ->
                if (item is JsonObject) {
                    item["retryDelay"]?.let { parseJsonDuration(it)?.let { ms -> return ms } }
                }
            }
            (err["message"] as? JsonPrimitive)?.contentOrNull?.let { msg ->
                val match = Regex("""(?i)(?:retry|try\s+again)\s+(?:after|in)\s+([0-9]+(?:\.[0-9]+)?)\s*(s|sec|seconds|ms|milliseconds|m|min|minutes)?""").find(msg)
                if (match != null) {
                    val num = match.groupValues[1].toDoubleOrNull()
                    val unit = match.groupValues.getOrNull(2)?.lowercase().orEmpty()
                    if (num != null) {
                        val ms = when {
                            unit.startsWith("m") && !unit.startsWith("ms") -> (num * 60 * 1000).toLong()
                            unit == "ms" || unit == "milliseconds" -> num.toLong()
                            else -> (num * 1000).toLong()
                        }
                        return ms.coerceAtLeast(0)
                    }
                }
            }
        }
        (obj["details"] as? JsonArray)?.forEach { item ->
            if (item is JsonObject) {
                item["retryDelay"]?.let { parseJsonDuration(it)?.let { ms -> return ms } }
            }
        }
        return null
    }

    private fun parseJsonDuration(elem: JsonElement): Long? {
        if (elem is JsonPrimitive) {
            val content = elem.content
            content.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
            val trimmed = content.trim()
            if (trimmed.endsWith("s", ignoreCase = true) && !trimmed.endsWith("ms", ignoreCase = true)) {
                trimmed.dropLast(1).trim().toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
            } else if (trimmed.endsWith("ms", ignoreCase = true)) {
                trimmed.dropLast(2).trim().toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(0) }
            }
        }
        return null
    }
}

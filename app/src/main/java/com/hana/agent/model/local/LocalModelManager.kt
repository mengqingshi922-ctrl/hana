package com.hana.agent.model.local

import android.content.Context
import com.hana.agent.core.model.Capabilities
import com.hana.agent.core.model.ModelRole
import com.hana.agent.settings.FeatureFlags
import com.hana.agent.settings.ProviderConfig
import com.hana.agent.settings.ProviderStore
import com.hana.agent.settings.RoleChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 端侧小模型元数据信息。 */
data class LocalModelSpec(
    val id: String,
    val name: String,
    val tag: String,
    val fileName: String,
    val estimatedBytes: Long,
    val downloadUrl: String,
    val description: String,
    val isDefault: Boolean = false,
)

/** 端侧模型下载与就绪状态。 */
sealed interface LocalModelStatus {
    data object NotDownloaded : LocalModelStatus
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedKbps: Long,
    ) : LocalModelStatus
    data class Ready(val file: File, val sizeBytes: Long) : LocalModelStatus
    data class Error(val message: String) : LocalModelStatus
}

/**
 * 端侧小模型生命周期管理器：
 * 负责 Qwen2.5-1.5B (推荐) 及 UI-TARS-2B (GUI专用) 等端侧模型的下载、断点续传、文件存储与一键绑定 ProviderStore。
 */
object LocalModelManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 预置模型清单（走国内 ModelScope 极速 CDN）。 */
    val AVAILABLE_MODELS: List<LocalModelSpec> = listOf(
        LocalModelSpec(
            id = "ui-tars-2b",
            name = "UI-TARS-2B (字节·GUI专用)",
            tag = "UI-TARS · 1.4GB",
            fileName = "ui-tars-2b-sft-q4_k_m.gguf",
            estimatedBytes = 1420 * 1024 * 1024L,
            downloadUrl = "https://modelscope.cn/models/bartowski/UI-TARS-2B-SFT-GGUF/resolve/master/UI-TARS-2B-SFT-Q4_K_M.gguf",
            description = "字节跳动开源原生 GUI 动作模型，自带视觉空间与坐标理解，直接输出操作杜绝盲点。",
            isDefault = true,
        ),
        LocalModelSpec(
            id = "smollm2-360m",
            name = "SmolLM2-360M (极速反射·200MB)",
            tag = "SmolLM2 · 229MB",
            fileName = "smollm2-360m-instruct-q4_k_m.gguf",
            estimatedBytes = 229 * 1024 * 1024L,
            downloadUrl = "https://modelscope.cn/models/QuantFactory/SmolLM2-360M-Instruct-GGUF/resolve/master/SmolLM2-360M-Instruct.Q4_K_M.gguf",
            description = "229MB 极致轻量动作特种兵，专为 50-Token 槽位快速反应设计，纯手机 CPU 仅需 50ms 闪电决策。",
            isDefault = false,
        ),
    )

    private val statusFlows = ConcurrentHashMap<String, MutableStateFlow<LocalModelStatus>>()
    private val downloadJobs = ConcurrentHashMap<String, Job>()

    private fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { if (!exists()) mkdirs() }

    fun getFile(context: Context, spec: LocalModelSpec): File = File(modelDir(context), spec.fileName)

    /** 获取指定模型的状态流（自动检查磁盘实际文件大小）。 */
    fun status(context: Context, modelId: String): StateFlow<LocalModelStatus> {
        val spec = AVAILABLE_MODELS.firstOrNull { it.id == modelId } ?: AVAILABLE_MODELS.first()
        val flow = statusFlows.getOrPut(spec.id) {
            val file = getFile(context, spec)
            val initial = if (file.exists() && file.length() > 10 * 1024 * 1024L) {
                LocalModelStatus.Ready(file, file.length())
            } else {
                LocalModelStatus.NotDownloaded
            }
            MutableStateFlow(initial)
        }
        return flow.asStateFlow()
    }

    /** 开始或恢复下载。 */
    fun startDownload(context: Context, modelId: String) {
        val spec = AVAILABLE_MODELS.firstOrNull { it.id == modelId } ?: return
        if (downloadJobs[spec.id]?.isActive == true) return

        val flow = statusFlows.getOrPut(spec.id) { MutableStateFlow(LocalModelStatus.NotDownloaded) }
        val targetFile = getFile(context, spec)
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.downloading")

        val job = scope.launch {
            try {
                var downloaded = if (tempFile.exists()) tempFile.length() else 0L
                val requestBuilder = Request.Builder().url(spec.downloadUrl)
                if (downloaded > 0) {
                    requestBuilder.header("Range", "bytes=$downloaded-")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful && response.code != 206) {
                    flow.value = LocalModelStatus.Error("下载失败: HTTP ${response.code}")
                    return@launch
                }

                val body = response.body ?: throw IllegalStateException("空响应体")
                val contentLength = body.contentLength()
                val total = if (contentLength > 0) downloaded + contentLength else spec.estimatedBytes

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(tempFile, downloaded > 0)

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var lastTime = System.currentTimeMillis()
                var bytesSinceLast = 0L

                inputStream.use { input ->
                    outputStream.use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            bytesSinceLast += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastTime
                            if (elapsed >= 300) {
                                val speedKbps = if (elapsed > 0) (bytesSinceLast * 1000 / elapsed) / 1024 else 0L
                                val progress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                flow.value = LocalModelStatus.Downloading(progress, downloaded, total, speedKbps)
                                lastTime = now
                                bytesSinceLast = 0L
                            }
                        }
                        output.flush()
                    }
                }

                if (tempFile.renameTo(targetFile)) {
                    flow.value = LocalModelStatus.Ready(targetFile, targetFile.length())
                } else {
                    flow.value = LocalModelStatus.Ready(tempFile, tempFile.length())
                }

                // 核心保障：模型下载完成后，自动解压/安装推理引擎并自动激活常驻监听！
                ensureEngineInstalled(context)
                val providerStore = ProviderStore.get(context)
                val featureFlags = FeatureFlags.get(context)
                activateAsLightModel(context, spec.id, providerStore, featureFlags)
            } catch (e: CancellationException) {
                flow.value = LocalModelStatus.NotDownloaded
            } catch (e: Exception) {
                flow.value = LocalModelStatus.Error("下载中断: ${e.message ?: "网络异常"}")
            } finally {
                downloadJobs.remove(spec.id)
            }
        }
        downloadJobs[spec.id] = job
    }

    /** 取消当前下载。 */
    fun cancelDownload(modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        statusFlows[modelId]?.value = LocalModelStatus.NotDownloaded
    }

    /** 删除本地模型文件。 */
    fun deleteModel(context: Context, modelId: String) {
        cancelDownload(modelId)
        val spec = AVAILABLE_MODELS.firstOrNull { it.id == modelId } ?: return
        val file = getFile(context, spec)
        if (file.exists()) file.delete()
        val temp = File(file.parentFile, "${file.name}.downloading")
        if (temp.exists()) temp.delete()
        statusFlows[modelId]?.value = LocalModelStatus.NotDownloaded
    }

    /**
     * 自动安装端侧 llama-server 推理引擎（优先从 assets 内置解压，不存在则从官方 release 下载）。
     */
    fun ensureEngineInstalled(context: Context): Boolean {
        val binDir = File(context.filesDir, "bin")
        val exe = File(binDir, "llama-server")
        val vulkanSo = File(binDir, "libggml-vulkan.so")
        if (exe.exists() && exe.canExecute() && vulkanSo.exists()) return true
        binDir.mkdirs()

        // 1. 优先尝试从 assets/engine/llama-engine.tar.gz 解压安装
        val hasAsset = try {
            context.assets.list("engine")?.contains("llama-engine.tar.gz") == true
        } catch (_: Exception) { false }

        if (hasAsset) {
            try {
                context.assets.open("engine/llama-engine.tar.gz").use { stream ->
                    com.hana.agent.core.cap.sandbox.RootfsExtractor.extract(stream, binDir)
                }
                exe.setExecutable(true, false)
                if (exe.exists()) return true
            } catch (_: Exception) {
                // 解压异常退回网络下载
            }
        }

        // 2. 从官方 release 镜像下载引擎包
        return try {
            val engineUrl = "https://github.com/ggml-org/llama.cpp/releases/download/b12167/llama-b12167-bin-android-arm64.tar.gz"
            val req = Request.Builder().url(engineUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                body.byteStream().use { stream ->
                    com.hana.agent.core.cap.sandbox.RootfsExtractor.extract(stream, binDir)
                }
            }
            exe.setExecutable(true, false)
            exe.exists()
        } catch (e: Exception) {
            false
        }
    }

    /** 检测端侧 8080 推理服务端口是否存活。 */
    fun isServerRunning(): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 8080), 300)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    @Volatile private var serverProcess: Process? = null

    /** 立即停止端侧推理引擎，彻底释放大模型占用的 1.5GB 物理内存，让手机重回极速流畅。 */
    fun stopServer() {
        try {
            serverProcess?.destroy()
            serverProcess = null
            Runtime.getRuntime().exec(arrayOf("pkill", "-f", "llama-server"))
        } catch (_: Exception) {}
    }

    /**
     * 确保端侧 llama-server 守护进程已拉起并处于监听状态。
     * 支持模型热切换：若指定了 targetModelId 或要求 forceRestart，则优先优雅杀死旧进程重新拉起。
     */
    fun ensureServerRunning(context: Context, targetModelId: String? = null, forceRestart: Boolean = false): Boolean {
        if (!forceRestart && isServerRunning()) return true
        if (forceRestart) {
            try {
                serverProcess?.destroy()
                serverProcess = null
                Runtime.getRuntime().exec(arrayOf("pkill", "-f", "llama-server")).waitFor()
            } catch (_: Exception) {}
        }

        ensureEngineInstalled(context)
        val binDir = listOf(
            File(context.filesDir, "bin"),
            File("/data/local/tmp/llama")
        ).firstOrNull { File(it, "llama-server").exists() } ?: return false

        val exe = File(binDir, "llama-server")
        val targetSpec = if (targetModelId != null) {
            AVAILABLE_MODELS.firstOrNull { it.id == targetModelId }
        } else null

        val readyModel = (targetSpec?.let { getFile(context, it) }?.takeIf { it.exists() && it.length() > 10 * 1024 * 1024L })
            ?: AVAILABLE_MODELS.firstNotNullOfOrNull { spec ->
                val f = getFile(context, spec)
                if (f.exists() && f.length() > 10 * 1024 * 1024L) f else null
            } ?: return false

        return try {
            // 移动端硬件调度：采用全大核 CPU + Neon Dot Product 向量指令集并行加速，自适应动态算力
            val optimalThreads = maxOf(4, minOf(Runtime.getRuntime().availableProcessors() - 2, 8))
            // 自动检测与该模型匹配的多模态视觉投影权重 (mmproj)，若存在则自动附加加载以释放原生 GUI 视觉定位能力
            val mmprojFile = readyModel.parentFile?.listFiles()?.firstOrNull { f ->
                f.name.startsWith("mmproj") && f.name.endsWith(".gguf")
            }
            val commandList = mutableListOf(
                exe.absolutePath,
                "-m", readyModel.absolutePath,
                "--host", "127.0.0.1",
                "--port", "8080",
                "-c", "4096",
                "-fa", "on",
                "-np", "1",
                "-ngl", "0",
                "-t", optimalThreads.toString(),
                "--alias", readyModel.name.removeSuffix(".gguf")
            )
            if (mmprojFile != null && mmprojFile.exists()) {
                commandList.addAll(listOf("--mmproj", mmprojFile.absolutePath))
            }
            val pb = ProcessBuilder(commandList)
            pb.environment()["LD_LIBRARY_PATH"] = binDir.absolutePath
            val logFile = File(context.filesDir, "llama_server.log")
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            serverProcess = pb.start()

            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 8000) {
                if (isServerRunning()) return true
                Thread.sleep(300)
            }
            isServerRunning()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 一键将下载好的端侧模型配置为 Hana 的轻量模型（ModelRole.Light），并打开 lightSteps 提速开关。
     */
    suspend fun activateAsLightModel(
        context: Context,
        modelId: String,
        providerStore: ProviderStore,
        featureFlags: FeatureFlags? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val spec = AVAILABLE_MODELS.firstOrNull { it.id == modelId } ?: return@withContext false
        val file = getFile(context, spec)
        if (!file.exists() || file.length() < 10 * 1024 * 1024L) return@withContext false

        // 查找或新建本地模型服务商配置（默认指向本地 8080 端口）
        val localBaseUrl = "http://127.0.0.1:8080/v1"
        val existing = providerStore.configs.value.firstOrNull { it.baseUrl.trimEnd('/') == localBaseUrl }

        val hasVision = (spec.id.contains("tars") || spec.id.contains("vl")) &&
            file.parentFile?.listFiles()?.any { it.name.startsWith("mmproj") && it.name.endsWith(".gguf") } == true

        val configId = if (existing != null) {
            val updated = existing.copy(
                model = spec.fileName.removeSuffix(".gguf"),
                name = "端侧模型 (${spec.name.substringBefore(' ')})",
                capabilities = existing.capabilities.copy(toolCalls = true, vision = hasVision, contextTokens = 4096),
            )
            providerStore.save(updated, "local-embedded")
            updated.id
        } else {
            val newId = UUID.randomUUID().toString()
            val newConfig = ProviderConfig(
                id = newId,
                name = "端侧模型 (${spec.name.substringBefore(' ')})",
                kind = ProviderConfig.Kind.OpenAiCompat,
                baseUrl = localBaseUrl,
                model = spec.fileName.removeSuffix(".gguf"),
                capabilities = Capabilities(toolCalls = true, vision = hasVision, contextTokens = 4096, reasoning = false),
            )
            providerStore.save(newConfig, "local-embedded")
            newId
        }

        // 绑定为轻量模型角色
        providerStore.choose(ModelRole.Light, RoleChoice.Model(configId))

        // 激活提速分工开关 (lightSteps)
        featureFlags?.let { ff ->
            ff.setSpeed(ff.speedFlags().copy(lightSteps = true))
        }

        // 确保端侧 llama-server 守护引擎处于监听状态（若有模型切换则强制重启加载对应权重）
        ensureServerRunning(context, targetModelId = spec.id, forceRestart = true)
        true
    }

    /**
     * 一键停用端侧模型并彻底关闭推理进程，将轻量角色回退给主模型（或云端快速模型），立即释放 1.5GB 内存。
     */
    suspend fun deactivateLightModel(providerStore: ProviderStore): Boolean = withContext(Dispatchers.IO) {
        stopServer()
        providerStore.choose(ModelRole.Light, RoleChoice.SameAsMain)
        true
    }
}

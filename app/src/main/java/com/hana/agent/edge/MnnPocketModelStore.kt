package com.hana.agent.edge

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MNN-Pocket 模型权重下载状态流模型
 */
sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data class Downloading(
        val progress: Float,             // 0.0f ~ 1.0f
        val speedBps: Long,              // 字节/秒
        val downloadedBytes: Long,
        val totalBytes: Long,            // 约 173MB
    ) : DownloadState
    data class Verifying(val sha256: String) : DownloadState
    data object Ready : DownloadState
    data class Error(val message: String, val canRetry: Boolean) : DownloadState
}

/**
 * MNN-Pocket 模型权重管理与生命周期存储 (MNN Pocket Model Store)
 *
 * 遵循四条架构铁律：
 * 1. 零 APK 膨胀：严禁在 assets/ 随包打包任何 173MB 权重或巨型压缩包；
 * 2. 存放在应用外部专属文件目录 (context.getExternalFilesDir("mnn_pocket"))；
 * 3. 支持 HTTP Range 分片断点续传、ModelScope / 镜像源加速下载；
 * 4. SHA-256 完整性强校验、按需就绪检测与一键物理清空释放。
 */
class MnnPocketModelStore(
    private val context: Context? = null,
    private val baseDir: File? = null,
    httpClient: OkHttpClient? = null,
) {
    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val modelDir: File
        get() = (baseDir ?: context?.getExternalFilesDir(DIR_NAME) ?: context?.filesDir?.resolve(DIR_NAME) ?: File("build/tmp/mnn_pocket")).also {
            if (!it.exists()) it.mkdirs()
        }

    val decModelFile: File
        get() = File(modelDir, DEC_MODEL_NAME)

    val genModelFile: File
        get() = File(modelDir, GEN_MODEL_NAME)

    val partFile: File
        get() = File(modelDir, "$DEC_MODEL_NAME.part")

    private val _downloadState = MutableStateFlow<DownloadState>(
        if (isReady()) DownloadState.Ready else DownloadState.NotDownloaded
    )
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var downloadJob: Job? = null

    private val isCancelled = AtomicBoolean(false)

    /**
     * 检测端侧模型文件是否已下载并完整就绪。
     */
    fun isReady(): Boolean {
        return decModelFile.exists() && decModelFile.length() > 0
    }

    /**
     * 启动 HTTP Range 分片断点续传下载管线。
     * 支持 ModelScope 官方源及加速镜像源。
     */
    suspend fun startDownload(mirrorUrl: String? = null, expectedSha256: String = EXPECTED_SHA256) = withContext(Dispatchers.IO) {
        if (isReady()) {
            if (verifyChecksum(decModelFile, expectedSha256)) {
                _downloadState.value = DownloadState.Ready
                return@withContext
            } else {
                // 已存在文件损坏或 SHA-256 不匹配，物理删除并重新下载
                decModelFile.delete()
            }
        }

        val urlsToTry = if (mirrorUrl != null) {
            listOf(mirrorUrl)
        } else {
            listOf(DEFAULT_MODELSCOPE_URL, DEFAULT_MIRROR_URL, HF_MIRROR_URL)
        }

        isCancelled.set(false)
        var lastErrorMessage = "未知下载错误"

        for (targetUrl in urlsToTry) {
            if (isCancelled.get()) {
                _downloadState.value = DownloadState.NotDownloaded
                return@withContext
            }

            val success = tryDownloadFromUrl(targetUrl, expectedSha256) { err -> lastErrorMessage = err }
            if (success) {
                return@withContext
            }
        }

        if (isCancelled.get()) {
            _downloadState.value = DownloadState.NotDownloaded
        } else {
            _downloadState.value = DownloadState.Error(lastErrorMessage, canRetry = true)
        }
    }

    private fun tryDownloadFromUrl(targetUrl: String, expectedSha256: String, onError: (String) -> Unit): Boolean {
        try {
            var existingBytes = if (partFile.exists()) partFile.length() else 0L
            var requestBuilder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 HanaAgent/5.0")

            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            var response = client.newCall(requestBuilder.build()).also { activeCall = it }.execute()

            // 如果 416（Range 不满足），自动删除失效断点文件从 0 开始重新请求
            if (response.code == 416 && existingBytes > 0L) {
                partFile.delete()
                existingBytes = 0L
                requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 HanaAgent/5.0")
                response = client.newCall(requestBuilder.build()).also { activeCall = it }.execute()
            }

            if (!response.isSuccessful && response.code != 206) {
                onError("HTTP 下载错误: ${response.code}")
                return false
            }

            val body = response.body ?: run {
                onError("响应体为空")
                return false
            }

            val isAppend = response.code == 206
            val contentLength = body.contentLength()
            val totalBytes = if (contentLength > 0) {
                if (isAppend) existingBytes + contentLength else contentLength
            } else {
                EXPECTED_TOTAL_BYTES
            }

            var downloadedBytes = if (isAppend) existingBytes else 0L
            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(partFile, isAppend)

            var lastReportTime = System.currentTimeMillis()
            var bytesSinceLastReport = 0L

            outputStream.use { out ->
                inputStream.use { inp ->
                    while (inp.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled.get()) {
                            return false
                        }

                        out.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        bytesSinceLastReport += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastReportTime
                        if (elapsed >= 300) {
                            val speed = (bytesSinceLastReport * 1000L) / elapsed.coerceAtLeast(1L)
                            val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            _downloadState.value = DownloadState.Downloading(
                                progress = progress,
                                speedBps = speed,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                            )
                            lastReportTime = now
                            bytesSinceLastReport = 0L
                        }
                    }
                }
            }

            if (isCancelled.get()) {
                return false
            }

            // 下载完成，进入校验阶段
            _downloadState.value = DownloadState.Verifying("SHA-256 校验中...")
            val computed = computeSha256(partFile)
            if (computed == null || !computed.equals(expectedSha256, ignoreCase = true)) {
                val err = "SHA-256 完整性校验失败: 预期 $expectedSha256, 实际 $computed"
                onError(err)
                partFile.delete()
                _downloadState.value = DownloadState.Error(err, canRetry = true)
                return false
            }

            // 重命名至最终模型文件
            if (partFile.renameTo(decModelFile)) {
                _downloadState.value = DownloadState.Ready
                return true
            } else {
                partFile.copyTo(decModelFile, overwrite = true)
                partFile.delete()
                _downloadState.value = DownloadState.Ready
                return true
            }
        } catch (e: Exception) {
            if (e is CancellationException || isCancelled.get()) {
                return false
            }
            onError(e.message ?: "下载异常")
            return false
        } finally {
            activeCall = null
        }
    }

    /**
     * 取消进行中的模型下载任务。
     */
    fun cancelDownload() {
        isCancelled.set(true)
        activeCall?.cancel()
        downloadJob?.cancel()
        if (_downloadState.value is DownloadState.Downloading) {
            _downloadState.value = DownloadState.NotDownloaded
        }
    }

    /**
     * 校验文件 SHA-256 哈希完整性。
     */
    suspend fun verifyChecksum(file: File? = null, expectedSha256: String = EXPECTED_SHA256): Boolean = withContext(Dispatchers.IO) {
        val target = file ?: decModelFile
        if (!target.exists()) return@withContext false
        val computed = computeSha256(target) ?: return@withContext false
        computed.equals(expectedSha256, ignoreCase = true)
    }

    /**
     * 同步校验方法（兼容旧版本签名调用）。
     */
    fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        if (!file.exists()) return false
        val computed = computeSha256(file) ?: return false
        return computed.equals(expectedSha256, ignoreCase = true)
    }

    private fun computeSha256(file: File): String? {
        if (!file.exists()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 清理所有下载的模型权重与分片缓存，释放全部物理磁盘空间。
     */
    fun purgeWeights(): Boolean {
        cancelDownload()
        return try {
            val ok = if (modelDir.exists()) {
                modelDir.deleteRecursively()
            } else true
            _downloadState.value = DownloadState.NotDownloaded
            ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取当前模型及缓存占用的物理磁盘大小（字节）。
     */
    fun getDiskUsageBytes(): Long {
        return try {
            if (!modelDir.exists()) return 0L
            modelDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (_: Exception) {
            0L
        }
    }

    companion object {
        const val DIR_NAME = "mnn_pocket"
        const val DEC_MODEL_NAME = "mnn_pocket_0.3b_dec.mnn"
        const val GEN_MODEL_NAME = "mnn_pocket_0.3b_q4.mnn"

        // 默认预期模型规格约 163.5MB (171,481,330 字节)
        const val EXPECTED_SIZE_MB = 164
        const val EXPECTED_TOTAL_BYTES = 171481330L
        const val EXPECTED_SHA256 = "c9e0f2950718c057aeb2c20e5b8dd669cd02477c6c355b46d153256a312a248d"

        // ModelScope 官方与国内加速镜像下载地址
        const val DEFAULT_MODELSCOPE_URL = "https://modelscope.cn/api/v1/models/MNN/MNN-Pocket-0.3B-MNN/repo?Revision=master&FilePath=llm.mnn.weight"
        const val DEFAULT_MIRROR_URL = "https://www.modelscope.cn/models/MNN/MNN-Pocket-0.3B-MNN/resolve/master/llm.mnn.weight"
        const val HF_MIRROR_URL = "https://hf-mirror.com/taobao-mnn/MNN-Pocket-0.3B-MNN/resolve/main/llm.mnn.weight"
    }
}

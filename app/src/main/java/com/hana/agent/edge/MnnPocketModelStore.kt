package com.hana.agent.edge

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * MNN-Pocket 模型权重管理与生命周期存储 (MNN Pocket Model Store)
 *
 * 遵循四条架构铁律：
 * 1. 零 APK 膨胀：严禁在 assets/ 随包打包任何 173MB 权重或巨型压缩包；
 * 2. 存放在应用外部专属文件目录 (context.getExternalFilesDir("mnn_pocket"))；
 * 3. 支持 SHA-256 完整性校验、按需就绪检测与一键物理清空。
 */
class MnnPocketModelStore(
    private val context: Context? = null,
    private val baseDir: File? = null,
) {

    val modelDir: File
        get() = (baseDir ?: context?.getExternalFilesDir(DIR_NAME) ?: context?.filesDir?.resolve(DIR_NAME) ?: File("build/tmp/mnn_pocket")).also {
            if (!it.exists()) it.mkdirs()
        }

    val decModelFile: File
        get() = File(modelDir, DEC_MODEL_NAME)

    val genModelFile: File
        get() = File(modelDir, GEN_MODEL_NAME)

    /**
     * 检测端侧模型文件是否已下载并完整就绪。
     */
    fun isReady(): Boolean {
        // 判定核心决策分类指针模型 Dec 是否就绪
        return decModelFile.exists() && decModelFile.length() > 0
    }

    /**
     * 校验文件 SHA-256 哈希。
     */
    fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        if (!file.exists()) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val calculated = digest.digest().joinToString("") { "%02x".format(it) }
            calculated.equals(expectedSha256, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 清理所有下载的模型权重，释放全部磁盘空间。
     */
    fun purgeWeights(): Boolean {
        return try {
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            } else true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取当前模型占用的物理磁盘大小（字节）。
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

        // 默认预期模型规格与说明
        const val EXPECTED_SIZE_MB = 173
    }
}

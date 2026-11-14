package com.hana.agent.cap.root

import android.content.Context
import com.hana.agent.settings.FeatureFlags
import java.io.File

/**
 * 分级 Root 模式辅助启动器。
 *
 * 核心安全机制：
 * 1. 严格受限：仅当 [FeatureFlags.rootHelperEnabled] 被用户显式开启时方可执行 su 启动操作；
 * 2. 隔离沙盒：绝不提权提升 Hana 主应用的运行 uid，主进程始终在标准 Android 应用沙盒内运行；
 * 3. 辅助启动：仅使用 su 权限启动特定的后台非交互 helper 进程；
 * 4. 静默安全探测：[isDeviceRooted] 仅进行静态文件与 PATH 存在性嗅探，绝不盲目执行 `su` 以免触发弹窗轰炸。
 */
object RootHelperStarter {

    /**
     * 常用 root 二进制程序探测路径。
     */
    private val KNOWN_SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        "/data/local/tmp/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su"
    )

    /**
     * 静默探测当前设备是否已 Root。
     *
     * 仅检查 su 二进制文件是否存在，不执行任何提权命令，保证探测过程不弹出授权提示。
     */
    fun isDeviceRooted(): Boolean {
        // 1. 检查已知静态路径
        for (path in KNOWN_SU_PATHS) {
            runCatching {
                val file = File(path)
                if (file.exists() && file.canExecute()) {
                    return true
                }
            }
        }

        // 2. 检查系统环境变量 PATH
        val pathEnv = System.getenv("PATH") ?: return false
        val paths = pathEnv.split(File.pathSeparatorChar)
        for (dir in paths) {
            runCatching {
                val suFile = File(dir, "su")
                if (suFile.exists() && suFile.canExecute()) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * 通过 Root 特权启动辅助进程。
     *
     * @param context 上下文对象
     * @param helperCommand 待通过 su 执行的受限命令行
     * @param stdinLine 可选的标准输入数据（如一次性握手口令），若提供则在启动后写入管道并关闭
     * @return 启动执行结果
     */
    fun startHelperViaRoot(
        context: Context,
        helperCommand: String,
        stdinLine: String? = null,
    ): Result<Unit> {
        val flags = FeatureFlags.get(context)
        if (!flags.rootHelperEnabled()) {
            return Result.failure(
                IllegalStateException("Root 辅助进程启动功能已被用户禁用，拒绝执行提权启动")
            )
        }

        if (helperCommand.isBlank()) {
            return Result.failure(IllegalArgumentException("辅助进程启动命令不能为空"))
        }

        return runCatching {
            val process = ProcessBuilder("su", "-c", helperCommand)
                .redirectErrorStream(true)
                .start()

            if (stdinLine != null) {
                runCatching {
                    process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        val line = if (stdinLine.endsWith("\n")) stdinLine else "$stdinLine\n"
                        writer.write(line)
                        writer.flush()
                    }
                }
            }

            // 稍微等待进程启动，确认非直接被拒绝（退出码非 0）
            val exitedEarly = runCatching {
                process.exitValue()
            }.getOrNull()

            if (exitedEarly != null && exitedEarly != 0) {
                val errorMsg = process.inputStream.bufferedReader().use { it.readText() }
                throw IllegalStateException("Root 进程启动失败 (exit code: $exitedEarly): $errorMsg")
            }
            Unit
        }
    }

    /**
     * 通过 Root 特权启动辅助进程（ByteArray 标准输入重载）。
     */
    fun startHelperViaRoot(
        context: Context,
        helperCommand: String,
        stdinLine: ByteArray,
    ): Result<Unit> = startHelperViaRoot(context, helperCommand, stdinLine.decodeToString())
}

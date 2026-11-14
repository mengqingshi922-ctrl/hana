package com.hana.agent.core.cap.root

/**
 * Root 命令安全风险级别。
 */
enum class RootCommandRisk {
    /** 纯只读命令（如 ls, cat, ps, getprop, dumpsys 等） */
    READ_ONLY,

    /** 受控的安全写操作（如 input tap, am start 等模拟输入或普通应用启动） */
    WRITE_SAFE,

    /** 高危或破坏性系统特权命令（如 rm, dd, mkfs, reboot, chmod, pm uninstall, settings put 等） */
    HIGH_RISK
}

/**
 * Root 命令安全策略判定结果。
 *
 * @property risk 判定的安全风险等级
 * @property allowed 是否允许执行
 * @property requiresConfirmation 是否强制需要用户在界面二次确认
 * @property riskReason 风险原因说明或拦截原因
 */
data class RootPolicyEvaluation(
    val risk: RootCommandRisk,
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val riskReason: String? = null
)

/**
 * 分级 Root 模式命令安全策略拦截器。
 *
 * 核心安全规则：
 * 1. Root 命令总开关未启用时，无条件拒绝一切 Root 命令；
 * 2. 纯只读命令（[RootCommandRisk.READ_ONLY]）在开关启用时允许自动放行，无需弹窗二次打扰；
 * 3. 写操作与高危命令（[RootCommandRisk.WRITE_SAFE] / [RootCommandRisk.HIGH_RISK]）在任何情况下都强制 requiresConfirmation = true；
 * 4. 关键硬核防线：在无人值守（unattended = true）模式下，直接彻底拒绝所有写操作与高危命令，杜绝后台越权破坏。
 */
object RootCommandPolicy {

    /**
     * 对待执行的 Shell 命令行进行严格安全评估。
     *
     * @param command 待执行的 shell 完整命令行
     * @param unattended 是否处于无人值守后台自动化模式
     * @param rootCommandEnabled 用户设置中的 Root 命令总开关状态
     * @return 策略判定结果
     */
    fun evaluate(
        command: String,
        unattended: Boolean = false,
        rootCommandEnabled: Boolean = false
    ): RootPolicyEvaluation {
        val trimmed = command.trim()
        val risk = classifyRisk(trimmed)

        // 1. 开关检查
        if (!rootCommandEnabled) {
            return RootPolicyEvaluation(
                risk = risk,
                allowed = false,
                requiresConfirmation = false,
                riskReason = "Root 命令执行功能未启用（请在设置中显式开启）"
            )
        }

        // 2. 只读命令放行
        if (risk == RootCommandRisk.READ_ONLY) {
            return RootPolicyEvaluation(
                risk = risk,
                allowed = true,
                requiresConfirmation = false,
                riskReason = null
            )
        }

        // 3. 无人值守模式直接拒绝任何写或高危命令
        if (unattended) {
            return RootPolicyEvaluation(
                risk = risk,
                allowed = false,
                requiresConfirmation = false,
                riskReason = "无人值守模式禁止执行写操作与高危 Root 命令"
            )
        }

        // 4. 有人值守模式下，写或高危命令强制要求确认
        val reason = if (risk == RootCommandRisk.HIGH_RISK) {
            "高危 Root 命令，可能影响系统核心文件、应用数据或设备运行状态，必须经人工确认"
        } else {
            "该 Root 命令包含写入或交互操作，需经人工确认后执行"
        }

        return RootPolicyEvaluation(
            risk = risk,
            allowed = true,
            requiresConfirmation = true,
            riskReason = reason
        )
    }

    /**
     * 将命令按语法和关键词分类为对应风险级别。
     */
    fun classifyRisk(command: String): RootCommandRisk {
        if (command.isBlank()) return RootCommandRisk.READ_ONLY

        // 包含重定向写入符（> 或 >>），哪怕是只读命令也会产生写盘风险
        if (command.contains(">")) {
            return RootCommandRisk.HIGH_RISK
        }

        // 分割多语句组合（如 ; && || |）
        val subCommands = command.split(Regex("[;&|]+")).map { it.trim() }.filter { it.isNotEmpty() }
        var highestRisk = RootCommandRisk.READ_ONLY

        for (sub in subCommands) {
            val subRisk = classifySingleCommand(sub)
            if (subRisk == RootCommandRisk.HIGH_RISK) {
                return RootCommandRisk.HIGH_RISK
            }
            if (subRisk == RootCommandRisk.WRITE_SAFE && highestRisk == RootCommandRisk.READ_ONLY) {
                highestRisk = RootCommandRisk.WRITE_SAFE
            }
        }

        return highestRisk
    }

    private fun classifySingleCommand(cmd: String): RootCommandRisk {
        val tokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return RootCommandRisk.READ_ONLY

        // 提取主命令名，去除绝对路径前缀（如 /system/bin/cat -> cat）
        val rawBinary = tokens[0]
        val binary = rawBinary.substringAfterLast('/')

        // 特殊复合命令检查（如 settings put, pm uninstall 等）
        if (tokens.size >= 2) {
            val subArg = tokens[1].lowercase()
            if (binary == "settings" && (subArg == "put" || subArg == "delete" || subArg == "reset")) {
                return RootCommandRisk.HIGH_RISK
            }
            if (binary == "pm" && (subArg == "uninstall" || subArg == "clear" || subArg == "disable" || subArg == "hide")) {
                return RootCommandRisk.HIGH_RISK
            }
        }

        if (HIGH_RISK_BINARIES.contains(binary)) {
            return RootCommandRisk.HIGH_RISK
        }

        if (WRITE_SAFE_BINARIES.contains(binary)) {
            return RootCommandRisk.WRITE_SAFE
        }

        if (READ_ONLY_BINARIES.contains(binary)) {
            return RootCommandRisk.READ_ONLY
        }

        // 未知命令默认作为受控写操作处理，强制确认
        return RootCommandRisk.WRITE_SAFE
    }

    private val READ_ONLY_BINARIES = setOf(
        "ls", "cat", "ps", "dumpsys", "getprop", "id", "which", "whoami",
        "head", "tail", "grep", "wc", "df", "stat", "uptime", "top",
        "logcat", "date", "env", "printenv", "uname", "readlink"
    )

    private val WRITE_SAFE_BINARIES = setOf(
        "input", "am", "mkdir", "touch", "sleep", "echo"
    )

    private val HIGH_RISK_BINARIES = setOf(
        "rm", "dd", "mkfs", "reboot", "chmod", "chown", "mount", "umount",
        "su", "setprop", "kill", "pkill", "killall", "fdisk", "format",
        "insmod", "rmmod", "iptables", "ip6tables", "nft", "busybox", "toybox"
    )
}

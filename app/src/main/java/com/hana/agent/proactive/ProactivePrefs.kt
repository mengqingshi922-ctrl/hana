package com.hana.agent.proactive

import android.content.Context
import android.content.SharedPreferences

/**
 * 主动能力偏好设置管理。
 *
 * 负责主动能力的持久化配置：
 * - 实验性总开关（默认关闭）；
 * - 允许监听的 App 包名白名单（默认空集合，必须由用户显式勾选授权）；
 * - 通知敏感正文脱敏保护选项（默认开启）。
 */
class ProactivePrefs(private val prefs: SharedPreferences) {

    /**
     * 主动感知能力总开关（默认关闭）。
     */
    fun isProactiveEnabled(): Boolean = prefs.getBoolean(KEY_PROACTIVE_ENABLED, false)

    fun setProactiveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROACTIVE_ENABLED, enabled).apply()
    }

    /**
     * 允许监听与感知的目标 App 包名白名单。默认空集合。
     */
    fun getAllowedPackages(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()) ?: emptySet()

    fun setAllowedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, packages).apply()
    }

    /**
     * 检查某个应用包名是否在用户授权的白名单内。
     */
    fun isPackageAllowed(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val set = getAllowedPackages()
        return set.contains(packageName)
    }

    /**
     * 向允许监听白名单中添加包名。
     */
    fun addAllowedPackage(packageName: String) {
        val current = getAllowedPackages().toMutableSet()
        current.add(packageName)
        setAllowedPackages(current)
    }

    /**
     * 从允许监听白名单中移除包名。
     */
    fun removeAllowedPackage(packageName: String) {
        val current = getAllowedPackages().toMutableSet()
        current.remove(packageName)
        setAllowedPackages(current)
    }

    /**
     * 通知卡片是否自动脱敏敏感文本（默认开启）。
     */
    fun isMaskSensitiveEnabled(): Boolean = prefs.getBoolean(KEY_MASK_SENSITIVE, true)

    fun setMaskSensitiveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MASK_SENSITIVE, enabled).apply()
    }

    /**
     * 读取持久化保存的日摘要 JSON 载荷（冷启动恢复使用）。
     */
    fun getDigestJson(): String = prefs.getString(KEY_DIGEST_JSON, "") ?: ""

    /**
     * 将日摘要存储序列化 JSON 写入本地 SharedPreferences。
     */
    fun setDigestJson(json: String) {
        prefs.edit().putString(KEY_DIGEST_JSON, json).apply()
    }

    /**
     * 清空本地持久化的日摘要 JSON。
     */
    fun clearDigestJson() {
        prefs.edit().remove(KEY_DIGEST_JSON).apply()
    }

    companion object {
        const val PREFS_NAME = "hana_proactive_prefs"
        private const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_MASK_SENSITIVE = "mask_sensitive"
        private const val KEY_DIGEST_JSON = "digest_store_json"

        fun get(context: Context): ProactivePrefs {
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ProactivePrefs(sp)
        }
    }
}

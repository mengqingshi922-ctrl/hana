package com.hana.agent.cap.phone.shell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log
import com.hana.agent.core.tool.LinkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 运行时动态发现已安装 App 声明的 DeepLink Scheme 路由引擎：
 * 遍历本机系统已安装应用，查询声明了 ACTION_VIEW + CATEGORY_BROWSABLE 与自定义 Scheme 的 Activity，
 * 提取包名与 Scheme 映射并注册到 [LinkPolicy]，使 Agent 能够即时、精准直达本机任意第三方应用。
 */
object DynamicLinkDiscovery {
    private const val TAG = "DynamicLinkDiscovery"

    private val BLOCKED_SCHEMES = setOf(
        "intent", "file", "content", "javascript", "http", "https", "about", "data", "package", "android"
    )

    /**
     * 异步刷新已安装 App 的 DeepLink Scheme 并注册至 [LinkPolicy]。
     */
    suspend fun refreshAsync(context: Context) {
        withContext(Dispatchers.IO) {
            runCatching {
                discoverAndRegister(context)
            }.onFailure { e ->
                Log.w(TAG, "Failed to refresh dynamic deeplinks", e)
            }
        }
    }

    /**
     * 同步发现并注册。
     */
    fun discoverAndRegister(context: Context) {
        val (schemes, packageToSchemes) = discover(context.packageManager)
        if (schemes.isNotEmpty() || packageToSchemes.isNotEmpty()) {
            LinkPolicy.registerDynamicRoutes(schemes, packageToSchemes)
            Log.i(TAG, "Discovered and registered ${schemes.size} dynamic schemes across ${packageToSchemes.size} packages")
        }
    }

    /**
     * 扫描系统已安装应用，提取声明了 ACTION_VIEW + CATEGORY_BROWSABLE 的非 http/https 自定义 Scheme 列表。
     */
    fun discover(pm: PackageManager): Pair<Set<String>, Map<String, List<String>>> {
        val packageToSchemes = mutableMapOf<String, MutableSet<String>>()
        val discoveredSchemes = mutableSetOf<String>()

        fun record(pkg: String, scheme: String) {
            val s = scheme.trim().lowercase()
            if (s.isNotEmpty() && s !in BLOCKED_SCHEMES && LinkPolicy.PACKAGE.matches(pkg)) {
                packageToSchemes.getOrPut(pkg) { mutableSetOf() }.add(s)
                discoveredSchemes.add(s)
            }
        }

        fun extractFromResolveInfo(info: ResolveInfo) {
            val pkg = info.activityInfo?.packageName ?: return
            val filter = info.filter ?: return
            val count = filter.countDataSchemes()
            for (i in 0 until count) {
                val s = filter.getDataScheme(i) ?: continue
                record(pkg, s)
            }
        }

        // 1. 批量查询所有接收 Intent.ACTION_VIEW + Intent.CATEGORY_BROWSABLE 的 Activity
        runCatching {
            val baseIntent = Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            val resolves = pm.queryIntentActivities(baseIntent, PackageManager.GET_RESOLVED_FILTER)
            for (info in resolves) {
                extractFromResolveInfo(info)
            }
        }

        // 2. 针对常用知名自定义 Scheme 做针对性探测查询
        for (scheme in LinkPolicy.TRUSTED_SCHEMES) {
            runCatching {
                val probeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://probe")).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                val resolves = pm.queryIntentActivities(probeIntent, PackageManager.GET_RESOLVED_FILTER)
                for (info in resolves) {
                    val pkg = info.activityInfo?.packageName ?: continue
                    record(pkg, scheme)
                    extractFromResolveInfo(info)
                }
            }
        }

        // 3. 补充扫描已安装应用的 AndroidManifest.xml (针对部分不返回 filter 对象的设备实现)
        runCatching {
            val installedPackages = pm.getInstalledPackages(0)
            for (pi in installedPackages) {
                val pkg = pi.packageName ?: continue
                if (pkg in packageToSchemes && packageToSchemes[pkg]!!.isNotEmpty()) continue
                runCatching {
                    val res = pm.getResourcesForApplication(pkg)
                    val parser = res.assets.openXmlResourceParser("AndroidManifest.xml")
                    var eventType = parser.eventType
                    var inActivity = false
                    var inFilter = false
                    var hasActionView = false
                    var hasCategoryBrowsable = false
                    val pendingSchemes = mutableListOf<String>()

                    while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                            when (parser.name) {
                                "activity", "activity-alias" -> inActivity = true
                                "intent-filter" -> if (inActivity) {
                                    inFilter = true
                                    hasActionView = false
                                    hasCategoryBrowsable = false
                                    pendingSchemes.clear()
                                }
                                "action" -> if (inFilter) {
                                    val name = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                                    if (name == Intent.ACTION_VIEW) hasActionView = true
                                }
                                "category" -> if (inFilter) {
                                    val name = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                                    if (name == Intent.CATEGORY_BROWSABLE) hasCategoryBrowsable = true
                                }
                                "data" -> if (inFilter) {
                                    val scheme = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "scheme")
                                    if (!scheme.isNullOrBlank()) pendingSchemes.add(scheme)
                                }
                            }
                        } else if (eventType == org.xmlpull.v1.XmlPullParser.END_TAG) {
                            when (parser.name) {
                                "intent-filter" -> {
                                    if (inFilter && hasActionView && hasCategoryBrowsable) {
                                        for (s in pendingSchemes) record(pkg, s)
                                    }
                                    inFilter = false
                                }
                                "activity", "activity-alias" -> inActivity = false
                            }
                        }
                        eventType = parser.next()
                    }
                    parser.close()
                }
            }
        }

        val sortedPackages = packageToSchemes.mapValues { it.value.toList().sorted() }
        return Pair(discoveredSchemes, sortedPackages)
    }
}

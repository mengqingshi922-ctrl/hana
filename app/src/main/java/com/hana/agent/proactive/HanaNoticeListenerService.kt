package com.hana.agent.proactive

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hana.agent.core.proactive.IncomingNotice
import com.hana.agent.settings.FeatureFlags

/**
 * Hana 通知监听服务。
 *
 * 核心运行原则：
 * 1. 严格受控：仅当 [FeatureFlags.proactiveEnabled] 开启且目标包名在 [ProactivePrefs] 白名单中时才转发；
 * 2. 避免循环：彻底忽略 Hana 本应用的通知，杜绝产生通知反馈死循环；
 * 3. 稳健容错：任何系统层通知异常均通过沙盒保护拦截，绝不导致宿主进程崩溃。
 */
class HanaNoticeListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        runCatching {
            val targetPkg = sbn.packageName ?: return

            // 1. 过滤本应用自身通知，防止死循环
            if (targetPkg == packageName) {
                return
            }

            // 2. 检查主动感知总开关
            val flags = FeatureFlags.get(this)
            val prefs = ProactivePrefs.get(this)
            if (!flags.proactiveEnabled() && !prefs.isProactiveEnabled()) {
                return
            }

            // 3. 检查白名单：只处理用户显式勾选的应用
            if (!prefs.isPackageAllowed(targetPkg)) {
                return
            }

            // 4. 解析通知内容
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

            if (title.isBlank() && text.isBlank()) {
                return
            }

            val appLabel = runCatching {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(targetPkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            }.getOrDefault(targetPkg)

            val incomingNotice = IncomingNotice(
                id = "${sbn.key}_${sbn.postTime}",
                packageName = targetPkg,
                appLabel = appLabel,
                title = title,
                text = text,
                postTimeMs = sbn.postTime
            )

            // 5. 移交主动能力单例运行时分流
            ProactiveRuntime.getInstance(this).onNoticeReceived(incomingNotice)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        runCatching {
            val key = "${sbn.key}_${sbn.postTime}"
            ProactiveRuntime.getInstance(this).dismissHint(key)
        }
    }
}

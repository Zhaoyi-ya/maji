package com.zhaoyi.maji.island

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知识别监听服务：系统主动把通知推送给本服务（MIUI/HyperOS 不会拦截，进程被杀后系统会重新拉起），
 * 比短信广播可靠得多。提取标题/正文/大文本/多行文本拼成一段，交给 [NotifIngest] 过白名单后识别。
 *
 * 授权方式：通知使用权是系统级开关，不能像危险权限那样弹窗申请；需在设置页引导用户到
 * 「设置 → 通知使用权」手动开启（见 [com.zhaoyi.maji.ui.NotifySettingsPage]）。
 */
class NotifListener : NotificationListenerService() {

    companion object {
        /** 是否真正与系统通知服务建立连接（设置页实时读取此状态）。 */
        @Volatile var isConnected: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i("NOTIFY", "NotifListener 已创建")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        AppLog.i("NOTIFY", "NotifListener 已连接系统通知服务")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        AppLog.i("NOTIFY", "NotifListener 与系统通知服务断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 跳过持续型通知（媒体播放、前台服务保活等），避免噪音
        if (sbn.isOngoing) return
        val n = sbn.notification ?: return
        val extras: Bundle = n.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() } ?: ""

        val appName = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val combined = listOf(title, text, bigText, lines)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        if (combined.isBlank()) return

        AppLog.i("NOTIFY", "收到通知 from=$appName | ${combined.take(60)}")
        NotifIngest.onNotification(this, appName, combined)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 无需处理：我们只关心新增通知
    }
}

package com.zhaoyi.maji.bill

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.zhaoyi.maji.Prefs
import com.zhaoyi.maji.island.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 定时备份调度器：用 [AlarmManager.setRepeating] 周期性触发 [BackupAlarmReceiver]，
 * 在后台静默跑一次 [BackupManager.runBackup] 并把结果以通知告知。
 *
 * 不引入 WorkManager（项目未依赖），零额外库。闹钟在以下时机重新登记：
 *  - 应用启动（[com.zhaoyi.maji.MaJiApp.onCreate]）
 *  - 开机自启（[com.zhaoyi.maji.BootReceiver]）
 *  - 用户在备份设置页改动开关/频率
 * 因闹钟在重启后失效，上述登记点保证开机/启动后自动恢复周期备份。
 */
object BackupScheduler {

    private const val ACTION = "com.zhaoyi.maji.action.BACKUP_ALARM"
    private const val CHANNEL_ID = "backup"
    private const val NOTIFY_ID = 2001
    private const val WAKE_LOCK_MS = 10 * 60 * 1000L // 最长持锁 10 分钟

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 根据设置重新登记/取消定时备份闹钟。任何设置变更或进程启动都应调用。 */
    fun reschedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = makePendingIntent(context)
        val prefs = Prefs.get(context, Prefs.Category.BACKUP)
        val enabled = prefs.getBoolean("auto_enabled", false)
        if (!enabled) {
            am.cancel(pi)
            AppLog.i("Backup", "定时备份已取消")
            return
        }
        val intervalHours = prefs.getInt("interval_hours", 24).coerceAtLeast(1)
        val intervalMs = intervalHours * 60 * 60 * 1000L
        val triggerAt = System.currentTimeMillis() + intervalMs
        // setRepeating 在 Android 12+ 会被钳制到所设间隔（不会更频繁）；本场景间隔 >= 1h，无影响。
        try {
            @Suppress("MissingPermission")
            am.setRepeating(AlarmManager.RTC_WAKEUP, triggerAt, intervalMs, pi)
            AppLog.i("Backup", "定时备份已登记：每 $intervalHours 小时")
        } catch (e: Exception) {
            AppLog.e("Backup", "登记定时备份失败：${e.message}")
        }
    }

    private fun makePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BackupAlarmReceiver::class.java).apply { action = ACTION }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** 闹钟触发：持锁后在后台执行一次备份，结果用通知告知（不弹界面）。 */
    class BackupAlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MaJi:Backup")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_MS) }
            scope.launch {
                try {
                    val summary = BackupManager.runBackup(context)
                    notifyResult(context, "定时备份完成", summary)
                } catch (e: Exception) {
                    AppLog.e("Backup", "定时备份失败：${e.message}")
                    notifyResult(context, "定时备份失败", e.message ?: "未知错误")
                } finally {
                    if (wl.isHeld) wl.release()
                }
            }
        }
    }

    private fun notifyResult(context: Context, title: String, text: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "备份", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFY_ID, n)
        } catch (e: Exception) {
            AppLog.e("Backup", "通知失败：${e.message}")
        }
    }
}

package com.zhaoyi.maji

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 前台保活服务。
 *
 * 仅用于在后台保持进程存活：进程活着时 [MaJiApp.onCreate] 中建立的 MiMo 暖连接、
 * 超级岛等依赖常驻进程的功能才持续可用。相比"开机拉 MainActivity"，本服务不弹界面，
 * 开机/打开 App 后静默在后台运行。
 *
 * 前台服务硬性要求一条常驻通知（"码记后台保活中"），用户可在系统设置里把该通知渠道隐藏。
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "keep_alive"
        const val NOTIFICATION_ID = 1001

        /** 启动保活服务（按系统版本自动选择 startForegroundService）。 */
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY：进程被系统回收后会尝试重建，维持保活。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台保活",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "码记后台保活服务"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("码记后台保活中")
            .setContentText("保持 MiMo 连接与超级岛可用")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

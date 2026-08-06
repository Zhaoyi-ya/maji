package com.zhaoyi.maji

import android.app.Application
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.zhaoyi.maji.data.AppDatabase
import com.zhaoyi.maji.island.AppLog
import com.zhaoyi.maji.island.MiclawSessionStore
import com.zhaoyi.maji.island.NotifListener
import com.zhaoyi.maji.island.RustBridge
import com.zhaoyi.maji.bill.BackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MaJiApp : Application(), ImageLoaderFactory {
    val database by lazy { AppDatabase.getInstance(this) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("coil_cache")).maxSizePercent(0.05).build() }
        .crossfade(150)
        .build()

    override fun onCreate() {
        super.onCreate()

        // 小米 Widget 独立进程（:widgetProvider）：仅加载 widget 所需，跳过主进程重型初始化，
        // 否则会突破官方 35M 内存限制，且 Rust/native 暖连接在 widget 进程无意义。
        if (isWidgetProcess()) return

        // 覆盖安装（adb install -r）后，通知监听服务的实例会死掉、binder 连到旧进程，
        // 导致 onNotificationPosted 永远不被回调（表现为"收到通知没反应"）。
        // 每次启动强制请求系统用新组件重新绑定，恢复监听。需已授予"通知使用权"。
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, NotifListener::class.java),
            )
            AppLog.i("NOTIFY", "已请求重绑通知监听服务")
        } catch (e: Exception) {
            android.util.Log.e("MaJiApp", "requestRebind failed", e)
        }

        // Pre-init Room on IO so the first DAO call on the main thread doesn't block.
        appScope.launch(Dispatchers.IO) {
            try {
                database.transactionDao().getAll().first()
            } catch (e: Exception) {
                android.util.Log.e("MaJiApp", "Room pre-init failed", e)
            }
        }

        // 暖连接：app 启动（前台、radio 热）时静默预请求 MiMo，把连接池暖热，
        // 这样用户真正截图识别时复用已建立的连接 → 一次成功，无需重试。
        appScope.launch(Dispatchers.IO) {
            try {
                MiclawSessionStore.load(this@MaJiApp)?.let { session ->
                    if (session.serviceToken.isNotBlank()) {
                        RustBridge.warmUpMiclaw(session.serviceToken, session.cUserId)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 不再启动常驻保活前台服务（移除"码记后台保活中"固定通知）。
        // 本机已加入 NoActive 白名单不会被冻结，且冷启动深链已修复，无需强制保活。

        // 重新登记定时备份闹钟：若用户在设置里开启了自动备份，则恢复周期性备份。
        // 闹钟在重启后会失效，故每次进程启动都重新登记一次即可持续生效。
        appScope.launch(Dispatchers.IO) {
            try {
                BackupScheduler.reschedule(this@MaJiApp)
            } catch (e: Exception) {
                android.util.Log.e("MaJiApp", "reschedule backup failed", e)
            }
        }
    }

    private fun isWidgetProcess(): Boolean {
        return try {
            val name = android.os.Process.myProcessName()
            name != null && name.endsWith(":widgetProvider")
        } catch (_: Throwable) { false }
    }

    override fun onTerminate() {
        // onTerminate is only called on emulators; on real devices the process is
        // killed by the OS. We cancel the scope here for the emulator case and rely
        // on the process death to clean up elsewhere.
        appScope.cancel()
        super.onTerminate()
    }
}

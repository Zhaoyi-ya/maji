package com.zhaoyi.maji

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启动接收器。
 *
 * 权限页「允许自启动」项只是引导用户去系统设置授予权限（Android 无标准查询 API），
 * 真正要在开机后恢复 MiMo 暖连接等核心初始化，必须由应用自己注册 BOOT_COMPLETED 广播。
 * 小米等 ROM 在授予「自启动」权限后才会向本接收器投递该广播。
 *
 * 收到后启动 [KeepAliveService]（前台保活服务）：进程随之创建，[MaJiApp.onCreate]
 * 自动跑 Room 预初始化与 MiMo 暖连接，使截图识别 / 超级岛等功能在开机后即可用。
 * 与「拉起 MainActivity」不同，本方式不弹界面，仅在后台静默保活。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            KeepAliveService.start(context)
        }
    }
}

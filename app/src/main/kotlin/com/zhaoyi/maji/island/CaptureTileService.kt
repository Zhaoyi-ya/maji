package com.zhaoyi.maji.island

import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * 快捷磁贴：用户主动点击后直接启动前台截图识别服务，不打开任何界面。
 * 与音量键快捷方式等价，只是多一个触发入口。
 */
class CaptureTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val mode = CaptureHelper.preferredMode()
        if (mode != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ScreenCaptureService::class.java)
                    .putExtra("capture_mode", mode)
                    .putExtra("from_tile", true),
            )
        } else {
            startActivity(
                Intent(this, PermissionBridgeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

package com.zhaoyi.maji.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * 伪按键监听无障碍服务。被系统音量键组合激活后，直接启动前台截图识别服务，
 * 不打开任何界面，随后立即关闭自身。
 */
class VolumeShortcutAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        val mode = CaptureHelper.preferredMode()
        if (mode != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ScreenCaptureService::class.java)
                    .putExtra("capture_mode", mode)
                    .putExtra("from_tile", false),
            )
        } else {
            // 没有 shizuku/root 授权，走权限引导页
            startActivity(
                Intent(this, PermissionBridgeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        disableSelf()
    }
}

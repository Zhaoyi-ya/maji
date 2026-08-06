package com.zhaoyi.maji.island

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * 桥接页：无 Shizuku/Root 时走 MediaProjection 截图流程。
 */
class PermissionBridgeActivity : Activity() {
    private val requestCode = 5301

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CaptureHelper.preferredMode() != null) {
            ContextCompat.startForegroundService(this,
                Intent(this, ScreenCaptureService::class.java)
                    .putExtra("capture_mode", CaptureHelper.preferredMode()))
            finish()
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
    }

    @Deprecated("Deprecated in Android SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this,
                Intent(this, ScreenCaptureService::class.java)
                    .putExtra("resultCode", resultCode)
                    .putExtra("data", data))
        }
        finish()
    }
}

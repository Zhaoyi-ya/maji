package com.zhaoyi.maji.island

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.zhaoyi.maji.MainActivity
import com.zhaoyi.maji.R
import com.zhaoyi.maji.island.MiclawSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zhaoyi.maji.island.CaptureHelper.toUploadJpeg

/**
 * 前台服务：截图 → 在线大模型识别 → 存入 DB + 上岛。
 */
class ScreenCaptureService : Service() {
    companion object {
        /** 识别完成广播：通知透明桥接页退出前台。 */
        const val RECOGNITION_DONE = "com.zhaoyi.maji.RECOGNITION_DONE"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("capture_mode") ?: "root"
        Log.d("maji_dbg", "SCS.onStartCommand mode=$mode -> startForeground 8101")
        startForegroundNotify()

        // 提前并行预热：在截图的同时解析 IPv4 并暖连接，避免识别时再被 DNS/冷连接阻塞。
        // 与下方 recognize() 共享同一单次解析结果（RustBridge 内部 single-flight）。
        scope.launch {
            try {
                val s = MiclawSessionStore.load(applicationContext)
                if (s != null && s.serviceToken.isNotBlank()) {
                    RustBridge.awaitMiclawV4()
                    if (!RustBridge.isWarm()) {
                        RustBridge.warmUpMiclaw(s.serviceToken, s.cUserId)
                    }
                }
            } catch (_: Exception) { }
        }

        val fromTile = intent?.getBooleanExtra("from_tile", false) ?: false
        when (mode) {
            CaptureHelper.CAPTURE_SHIZUKU -> shizukuCapture(fromTile)
            CaptureHelper.CAPTURE_ROOT -> rootCapture(fromTile)
            else -> startMediaProjection(intent)
        }
        return START_NOT_STICKY
    }

    private fun shizukuCapture(fromTile: Boolean) = scope.launch {
        AppLog.i("截屏", "Shizuku 截图开始 fromTile=$fromTile")
        if (fromTile) {
            // 磁贴路径：等控制中心收起，并显示"截图中"进度
            IslandController.showProgress(applicationContext, "截图中")
            delay(450)
        }
        val bitmap = CaptureHelper.captureShizuku()
        if (bitmap != null) {
            AppLog.i("截屏", "Shizuku 截图完成 ${bitmap.width}x${bitmap.height}")
            // 截图返回 → 显示"识别中"
            IslandController.showProgress(applicationContext, "识别中")
            try {
                recognize(bitmap)
            } finally {
                bitmap.recycle()
            }
        } else {
            AppLog.e("截屏", "Shizuku 截图返回 null")
            toast("Shizuku 截图失败")
        }
        stopSelf()
    }

    private fun rootCapture(fromTile: Boolean) = scope.launch {
        AppLog.i("截屏", "Root 截图开始 fromTile=$fromTile")
        if (fromTile) {
            // 磁贴路径：等控制中心收起，并显示"截图中"进度
            IslandController.showProgress(applicationContext, "截图中")
            delay(450)
        }
        val bitmap = CaptureHelper.captureRoot()
        if (bitmap != null) {
            AppLog.i("截屏", "Root 截图完成 ${bitmap.width}x${bitmap.height}")
            // 截图返回 → 显示"识别中"
            IslandController.showProgress(applicationContext, "识别中")
            try {
                recognize(bitmap)
            } finally {
                bitmap.recycle()
            }
        } else {
            AppLog.e("截屏", "Root 截图返回 null")
            toast("Root 截图失败")
        }
        stopSelf()
    }

    private fun startMediaProjection(intent: Intent?) {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data") ?: return stopSelf()

        if (resultCode != Activity.RESULT_OK) return stopSelf()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, android.os.Handler(mainLooper))

        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MajiCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null,
        )

        scope.launch {
            delay(900)
            val bitmap = acquireBitmap()
            if (bitmap != null) { recognize(bitmap); bitmap.recycle() }
            else toast("截图失败")
            stopSelf()
        }
    }

    private suspend fun recognize(bitmap: Bitmap) {
        val t0 = System.currentTimeMillis()
        Log.d("maji_dbg", "recognize start w=${bitmap.width} h=${bitmap.height}")
        try {
            AppLog.i("识别", "开始在线识别，图片大小=${bitmap.width}x${bitmap.height}")
            val data = RecognitionPipeline.recognize(applicationContext, bitmap.toUploadJpeg())
            val dao = com.zhaoyi.maji.data.AppDatabase.getInstance(applicationContext).pickupCodeDao()
            val txnDao = com.zhaoyi.maji.data.AppDatabase.getInstance(applicationContext).transactionDao()

            // 先收掉「识别中」进度通知，腾出大岛位，
            // 否则取餐码会在进度仍占岛时被挤成小球
            IslandController.dismissProgress(applicationContext)

            // 上岛
            data.pickupCodes.forEach { code ->
                dao.insert(code)
                withContext(Dispatchers.Main) { IslandController.show(applicationContext, code) }
            }
            // 记账 → 普通通知（支持多笔）
            data.transactions.forEach { txn ->
                txnDao.insert(txn)
            }
            if (data.transactions.isNotEmpty()) {
                val n = data.transactions.size
                val total = data.transactions.sumOf { it.amount }
                val title = if (n > 1) "已记 $n 笔" else "已记一笔"
                val body = if (n > 1) "共 ¥%.2f".format(total) else {
                    "${data.transactions.first().category} ¥${data.transactions.first().amount}"
                }
                // 点击通知直接打开最后一笔的编辑面板，方便识别出错时立即改正
                IslandController.notifyLedger(applicationContext, title, body, data.transactions.lastOrNull()?.id)
            }

            // 通知主界面刷新
            if (data.pickupCodes.isNotEmpty()) {
                sendBroadcast(android.content.Intent("com.zhaoyi.maji.DATA_CHANGED")
                    .setPackage(packageName))
            }

            if (data.pickupCodes.isEmpty() && data.transactions.isEmpty()) {
                // 未识别到任何账单 / 取件码：发一条普通通知提示，不再显示空白浮窗
                AppLog.i("识别", "完成: 未识别到有效内容")
                IslandController.notifyEmptyResult(applicationContext)
            } else {
                val parts = mutableListOf<String>()
                if (data.pickupCodes.isNotEmpty()) parts.add("${data.pickupCodes.size} 个取件码已上岛")
                if (data.transactions.isNotEmpty()) {
                    parts.add(if (data.transactions.size > 1) "已记 ${data.transactions.size} 笔" else "已记一笔")
                }
                AppLog.i("识别", "完成: ${parts.joinToString(" · ")}")
                toast(parts.joinToString(" · "))
            }
            Log.d("maji_dbg", "recognize DONE cost=${System.currentTimeMillis()-t0}ms codes=${data.pickupCodes.size} txns=${data.transactions.size}")
        } catch (e: Exception) {
            IslandController.dismissProgress(applicationContext)
            AppLog.e("识别", "识别失败", e)
            Log.d("maji_dbg", "recognize FAILED cost=${System.currentTimeMillis()-t0}ms: ${e.message}")
            toast(e.message ?: "识别失败")
        } finally {
            // 通知桥接透明页：识别已结束，可以退出前台
            sendBroadcast(Intent(RECOGNITION_DONE).setPackage(packageName))
        }
    }

    private fun acquireBitmap(): Bitmap? {
        var image: Image? = null
        return try {
            image = imageReader?.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            // 基于 padded 裁出最终副本后立即回收临时 padded，否则每次 MediaProjection
            // 截屏都泄漏一整屏 Bitmap（1080p≈8MB / 2K 屏≈30MB），长时间缓慢累积。
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also {
                padded.recycle()
            }
        } finally { image?.close() }
    }

    private fun startForegroundNotify() {
        val channelId = "maji_capture"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "截图识别", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, channelId)
            .setContentTitle("正在截图识别")
            .setContentText("在线大模型识别中")
            .setSmallIcon(R.drawable.ic_island_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (type != 0) startForeground(8101, n, type) else startForeground(8101, n)
    }

    override fun onDestroy() {
        // 兜底：无论何种退出路径，都通知桥接页退出前台
        runCatching { sendBroadcast(Intent(RECOGNITION_DONE).setPackage(packageName)) }
        // 兜底：确保「识别中」超级岛一定下岛，避免服务异常销毁时岛残留（120s 超时之外的第二重保险）
        runCatching { IslandController.dismissProgress(applicationContext) }
        scope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun toast(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@ScreenCaptureService, text, Toast.LENGTH_SHORT).show()
    }
}

package com.zhaoyi.maji.island

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.zhaoyi.maji.MainActivity
import com.zhaoyi.maji.R
import com.zhaoyi.maji.data.CodeKind
import com.zhaoyi.maji.data.PickupCode

/**
 * Android 16 通用「实时更新（Live Update / 推广常驻通知）」后端。
 *
 * 与小米超级岛（私有 `miui.focus.param`）不同，这是 AOSP 标准能力：
 * 用标准样式（BigTextStyle）+ setOngoing + 请求推广（EXTRA_REQUEST_PROMOTED_ONGOING），
 * 系统会把通知提升到状态栏胶囊、锁屏与通知栏顶部。API < 36 时该 extra 被忽略，
 * 自动降级为普通常驻通知（仍可用，只是不「推广」）。
 *
 * 与 [IslandNotifier] 共用同一通知 id（[IslandNotifier.notificationIdFor]），
 * 这样「已取件」动作与跨模式 dismiss 都能正确命中，无需改动 IslandActionReceiver。
 */
object LiveUpdateNotifier {

    private const val CHANNEL_ID = "maji_island"
    private const val ID_BASE = 7100
    // 与 IslandNotifier 的 PROGRESS_ID(=ID_BASE+9000) 区分，避免 dismissProgress 误伤
    private const val PROGRESS_ID = ID_BASE + 9003

    private const val PROGRESS_TIMEOUT_MS = 120_000L
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressCtx: Context? = null
    private val progressAutoDismiss = Runnable {
        progressCtx?.let { ctx ->
            runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(PROGRESS_ID) }
        }
    }

    fun show(context: Context, code: PickupCode, timeoutSec: Int = 7200): Boolean {
        // 复用小米后端的通知渠道（重要性 HIGH，非 MIN，满足推广条件）
        IslandNotifier.ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val id = IslandNotifier.notificationIdFor(code)
        val brandIconRes = IslandNotifier.brandIslandIcon(code)
        val kind = code.codeKind

        val codeText = code.code.trim()
        val merchant = code.merchant.trim().ifEmpty { kind.label }
        val item = code.item.trim()
        val detail = code.itemDetail.trim()
        val price = code.price.trim().take(8)

        val lines = buildList {
            if (detail.isNotBlank()) add(detail)
            if (merchant.isNotBlank()) add(merchant)
            if (item.isNotBlank()) add(item)
            if (price.isNotBlank()) add(formatAmountLabel(price))
        }
        val bigText = lines.joinToString("\n")

        val openApp = android.app.PendingIntent.getActivity(
            context,
            code.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_PICKUP, true),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val donePi = IslandActionReceiver.donePendingIntent(context, code.id)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(codeText)
            .setContentText(detail.ifBlank { codeText })
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText.ifBlank { codeText }))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, brandIconRes))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColorized(false)
            .addAction(NotificationCompat.Action.Builder(0, kind.action, donePi).build())

        requestPromoted(builder)
        nm.notify(id, builder.build())
        return true
    }

    fun dismiss(context: Context, code: PickupCode) {
        dismiss(context, IslandNotifier.notificationIdFor(code))
    }

    fun dismiss(context: Context, notificationId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId)
    }

    fun showProgress(context: Context, text: String) {
        IslandNotifier.ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(text)
            .setContentText("正在处理截图")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true) // 不确定进度（indeterminate）
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        requestPromoted(builder)
        nm.notify(PROGRESS_ID, builder.build())

        // 安全超时：识别卡死也最多占 120s，绝不永久占通知
        progressCtx = context.applicationContext
        progressHandler.removeCallbacks(progressAutoDismiss)
        progressHandler.postDelayed(progressAutoDismiss, PROGRESS_TIMEOUT_MS)
    }

    fun dismissProgress(context: Context) {
        progressHandler.removeCallbacks(progressAutoDismiss)
        progressCtx = null
        context.getSystemService(NotificationManager::class.java).cancel(PROGRESS_ID)
    }

    /**
     * 请求系统把这条常驻通知「推广」为 Live Update。
     * EXTRA_REQUEST_PROMOTED_ONGOING 仅 Android 16(API 36, BAKLAVA) 及以上识别，
     * 低版本忽略该 extra，退化为普通常驻通知。
     */
    private fun requestPromoted(builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.addExtras(Bundle().apply {
                putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
            })
        }
    }

    private fun formatAmountLabel(raw: String): String {
        val p = raw.trim()
        if (p.isEmpty()) return ""
        if (p.startsWith("¥") || p.startsWith("￥") || p.startsWith("$")) return p
        if (Regex("^[0-9]+(\\.[0-9]{1,2})?元?$").matches(p)) return "¥" + p.removeSuffix("元")
        return p
    }
}

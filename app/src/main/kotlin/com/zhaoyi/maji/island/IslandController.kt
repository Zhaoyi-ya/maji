package com.zhaoyi.maji.island

import android.app.NotificationManager
import android.content.Context
import com.zhaoyi.maji.Prefs
import com.zhaoyi.maji.data.PickupCode

/**
 * 超级岛后端分发器：根据设置里的「岛样式」(island_mode) 在两种实现间切换。
 *  - [MODE_XIAOMI] : 小米超级岛（私有 miui.focus.param，HyperOS 专属浮岛）
 *  - [MODE_LIVE]   : Android 16 通用实时更新（AOSP 推广常驻通知）
 *
 * 对外 API 与 [IslandNotifier] 对齐，调用方只需把 `IslandNotifier` 换成 `IslandController`。
 * 两种后端共用同一通知 id，因此「已取件」动作与跨模式 dismiss 都能正确命中。
 */
object IslandController {

    const val MODE_XIAOMI = "xiaomi"
    const val MODE_LIVE = "live_update"
    private const val KEY = "island_mode"

    fun mode(context: Context): String {
        return Prefs.get(context, Prefs.Category.GENERAL).getString(KEY, MODE_XIAOMI) ?: MODE_XIAOMI
    }

    fun isLive(context: Context) = mode(context) == MODE_LIVE

    fun show(context: Context, code: PickupCode, timeoutSec: Int = 7200): Boolean {
        return if (isLive(context)) LiveUpdateNotifier.show(context, code, timeoutSec)
        else IslandNotifier.show(context, code, timeoutSec)
    }

    fun dismiss(context: Context, code: PickupCode) {
        // 两种后端共用同一通知 id，全部取消即可（避免切换模式后残留）
        IslandNotifier.dismiss(context, code)
        LiveUpdateNotifier.dismiss(context, code)
    }

    fun dismiss(context: Context, notificationId: Int) {
        IslandNotifier.dismiss(context, notificationId)
        LiveUpdateNotifier.dismiss(context, notificationId)
    }

    /**
     * 判断这条码的通知「真的还挂在状态栏/岛上」。
     *
     * DB 的 [PickupCode.onIsland] 只是我们上一次的记录，并不能反映系统实际状态：
     * 用户手动从通知栏划掉后，DB 仍是 true，但通知已经不在了。读取
     * [NotificationManager.getActiveNotifications] 能拿到我们自己发布的、仍在活动的通知，
     * 从而区分「确实在岛上」与「DB 标记为已上岛但已被划掉」。
     *
     * 这是「上岛/下岛」按钮显示哪一侧、以及重新上岛能否成功的关键：
     * 手动划掉后系统会短期屏蔽同 id 重发，先 dismiss 清掉残留再 show 才能重建。
     */
    fun isOnIslandActive(context: Context, code: PickupCode): Boolean {
        if (!code.onIsland) return false
        return runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            val id = notificationIdFor(code)
            nm.activeNotifications.any { it.id == id && it.packageName == context.packageName }
        }.getOrDefault(false)
    }

    fun showProgress(context: Context, text: String) {
        if (isLive(context)) LiveUpdateNotifier.showProgress(context, text)
        else IslandNotifier.showProgress(context, text)
    }

    fun dismissProgress(context: Context) {
        IslandNotifier.dismissProgress(context)
        LiveUpdateNotifier.dismissProgress(context)
    }

    // 以下为非岛类（普通结果通知），两种模式行为一致，统一走小米后端实现
    fun notifyLedger(context: Context, title: String, body: String, txnId: String? = null) {
        IslandNotifier.notifyLedger(context, title, body, txnId)
    }

    fun notifyEmptyResult(context: Context) {
        IslandNotifier.notifyEmptyResult(context)
    }

    fun ensureChannel(context: Context) {
        IslandNotifier.ensureChannel(context)
    }

    fun notificationIdFor(code: PickupCode): Int = IslandNotifier.notificationIdFor(code)
}

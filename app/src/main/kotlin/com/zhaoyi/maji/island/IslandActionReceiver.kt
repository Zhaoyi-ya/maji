package com.zhaoyi.maji.island

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhaoyi.maji.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 处理超级岛上的交互：点「已取餐 / 已取件」按钮，或把岛通知划掉。
 */
class IslandActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_CODE_ID) ?: return
        val markDone = intent.action == ACTION_DONE
        val pending = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(appContext).pickupCodeDao()
                val code = dao.getById(id)
                if (code != null) {
                    // 定向更新：只动 doneAt / onIsland，保留取件码全部内容，
                    // 也避免和 UI 侧并发写时整行互相覆盖。
                    dao.setDoneState(
                        id = code.id,
                        doneAt = if (markDone) System.currentTimeMillis() else code.doneAt,
                        onIsland = false,
                    )
                    IslandController.dismiss(appContext, code)
                }
            } catch (e: Exception) {
                android.util.Log.e("IslandActionReceiver", "handle $id failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_DONE = "com.zhaoyi.maji.island.DONE"
        private const val ACTION_DISMISS = "com.zhaoyi.maji.island.DISMISS"
        private const val EXTRA_CODE_ID = "code_id"

        fun donePendingIntent(context: Context, codeId: String): PendingIntent =
            build(context, ACTION_DONE, codeId, codeId.hashCode())

        fun dismissPendingIntent(context: Context, codeId: String): PendingIntent =
            build(context, ACTION_DISMISS, codeId, codeId.hashCode() + 1)

        private fun build(context: Context, action: String, codeId: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, IslandActionReceiver::class.java).apply {
                    this.action = action
                    setPackage(context.packageName)
                    putExtra(EXTRA_CODE_ID, codeId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

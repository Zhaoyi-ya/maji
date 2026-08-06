package com.zhaoyi.maji.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zhaoyi.maji.MainActivity
import com.zhaoyi.maji.data.AppDatabase
import com.zhaoyi.maji.data.TransactionType
import com.zhaoyi.maji.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * 码记桌面收支 Widget（2×2，竖向排布）：
 * - 今日支出 / 今日收入 上下排列，蓝点 / 紫点前缀
 * - 右上角眼睛图标：点击切换「隐藏内容」(金额变 ****)
 * - 左下角「查看账单 >」跳转报表；右下角蓝色 + 记一笔
 * - 独立进程 :widgetProvider；onReceive 内读 Room 用 goAsync() 保活
 * - 刷新必须用显式组件广播（Android8+ 隐式广播送不到清单接收器）
 */
class MiniWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_HIDE = "com.zhaoyi.maji.widget.TOGGLE_HIDE"
        const val ACTION_REFRESH = "com.zhaoyi.maji.widget.REFRESH"

        private const val PREF_NAME = "maji_widget"
        private const val KEY_HIDE = "hide_content"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 由应用主进程（如记账后）调用，主动刷新桌面 widget */
        fun refreshAll(context: Context) {
            val intent = Intent(context, MiniWidgetProvider::class.java).setAction(ACTION_REFRESH)
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "miui.appwidget.action.APPWIDGET_UPDATE" -> {
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                if (ids != null) {
                    onUpdate(context, AppWidgetManager.getInstance(context), ids)
                } else {
                    super.onReceive(context, intent)
                }
            }
            ACTION_TOGGLE_HIDE -> {
                val cur = loadHide(context)
                saveHide(context, !cur)
                refreshAll(context)
            }
            ACTION_REFRESH -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, MiniWidgetProvider::class.java)) ?: return
                ids.forEach { update(context, it) }
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun update(context: Context, appWidgetId: Int) {
        val pending = goAsync()
        scope.launch {
            try {
                val dao = AppDatabase.getInstance(context).transactionDao()
                val all = dao.getAllList()
                // 今日 0 点 ~ 当前
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val start = cal.timeInMillis
                val end = System.currentTimeMillis()
                var expense = 0.0
                var income = 0.0
                for (t in all) {
                    if (t.date in start..end) {
                        if (t.type == TransactionType.EXPENSE) expense += t.amount
                        else income += t.amount
                    }
                }
                val hide = loadHide(context)
                val rv = RemoteViews(context.packageName, R.layout.widget_income)
                // 隐藏态：金额统一打码为 ****；眼睛图标随之切换开/合
                rv.setTextViewText(R.id.expense, if (hide) "****" else formatMoney(expense))
                rv.setTextViewText(R.id.income, if (hide) "****" else formatMoney(income))
                rv.setImageViewResource(
                    R.id.eye_icon,
                    if (hide) R.drawable.widget_eye_off else R.drawable.widget_eye_open
                )

                rv.setOnClickPendingIntent(R.id.eye_icon, broadcastPI(context, ACTION_TOGGLE_HIDE, 1))
                rv.setOnClickPendingIntent(R.id.root, activityPI(context, MainActivity.EXTRA_OPEN_REPORT, 2))
                rv.setOnClickPendingIntent(R.id.view_bill, activityPI(context, MainActivity.EXTRA_OPEN_REPORT, 3))
                rv.setOnClickPendingIntent(R.id.add_btn, activityPI(context, MainActivity.EXTRA_NEW_TXN, 4))

                AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, rv)
            } catch (e: Throwable) {
                android.util.Log.e("MaJiWidget", "update failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun activityPI(context: Context, extraKey: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(extraKey, true)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun broadcastPI(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MiniWidgetProvider::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun loadHide(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_HIDE, false)
    }

    private fun saveHide(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDE, hide).apply()
    }

    private fun formatMoney(v: Double): String {
        val s = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
        return "¥$s"
    }
}

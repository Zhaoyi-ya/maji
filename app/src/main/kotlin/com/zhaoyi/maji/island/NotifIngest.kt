package com.zhaoyi.maji.island

import android.content.Context
import com.zhaoyi.maji.Prefs
import com.zhaoyi.maji.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知识别入口：通知监听服务 [NotifListener] 提取到通知文本后调用 [onNotification]，
 * 先过仅关键词白名单，命中才把整段文本交给大模型，结果（取件码/账单）直接入库并上岛。
 *
 * 诊断：每条通知的处理轨迹（收到/命中/跳过/识别失败/上岛/记账）都写进
 * `notify_recent` 的 JSON 列表（最近 20 条），设置页「通认识别 → 最近收到」实时展示。
 * 这样即便 HyperOS 隐藏 app 的 logcat，也能一眼看出是「listener 没收到」「白名单没命中」
 * 还是「识别失败」，无需抓日志。
 */
object NotifIngest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val RECENT_FILE = "notify_recent"
    private const val RECENT_KEY = "recent"
    private const val RECENT_MAX = 20
    private val recentLock = Any()
    @Volatile private var recentCache: List<String> = emptyList()

    internal val DEFAULT_KEYWORDS = setOf(
        // 取件 / 取餐
        "取件", "取餐", "取件码", "取餐码", "快递", "柜", "外卖", "包裹", "自提", "取货",
        // 记账：支付 / 转账类通知（命中后由大模型解析成账单）
        "支付", "付款", "账单", "交易", "收款", "转账", "消费", "扣款", "已付", "已缴",
    )

    /** 由 [NotifListener] 或设置页「发送测试」调用。appName 为来源，text 为拼接后的通知正文。 */
    fun onNotification(context: Context, appName: String, text: String) {
        record(context, "[收到] $appName｜${text.take(80)}")
        if (!whitelistHit(context, text)) {
            record(context, "[跳过] $appName 未命中白名单")
            return
        }
        record(context, "[命中] 提交大模型识别")
        scope.launch {
            try {
                val payload = "来源应用: $appName\n通知内容:\n$text"
                val data = RecognitionPipeline.recognizeText(context, payload)
                val db = AppDatabase.getInstance(context)
                val pDao = db.pickupCodeDao()
                val tDao = db.transactionDao()
                data.pickupCodes.forEach { code ->
                    val pushed = IslandController.show(context, code)
                    pDao.insert(code.copy(onIsland = pushed))
                    record(context, "[上岛] ${code.code}（${code.kind}）")
                }
                data.transactions.forEach { tDao.insert(it) }

                // 记账成功 → 弹「已记一笔」系统通知（与截图识别一致），点击可进编辑面板改正
                if (data.transactions.isNotEmpty()) {
                    val n = data.transactions.size
                    val total = data.transactions.sumOf { it.amount }
                    val title = if (n > 1) "已记 $n 笔" else "已记一笔"
                    val body = if (n > 1) "共 ¥%.2f".format(total) else {
                        "${data.transactions.first().category} ¥${data.transactions.first().amount}"
                    }
                    IslandController.notifyLedger(
                        context,
                        title,
                        body,
                        data.transactions.lastOrNull()?.id,
                    )
                    record(context, "[记账] $n 笔 共 ¥%.2f".format(total))
                }

                if (data.pickupCodes.isEmpty() && data.transactions.isEmpty()) {
                    record(context, "[结果] 模型未提取到取件码/账单（可能是无关通知）")
                }

                // 通知主界面刷新（取件码 / 账单有变化时）
                if (data.pickupCodes.isNotEmpty() || data.transactions.isNotEmpty()) {
                    context.sendBroadcast(
                        android.content.Intent("com.zhaoyi.maji.DATA_CHANGED").setPackage(context.packageName),
                    )
                }
                AppLog.i("NOTIFY", "已处理：${data.pickupCodes.size} 取件码 / ${data.transactions.size} 账单")
            } catch (e: Exception) {
                record(context, "[失败] ${e.javaClass.simpleName}：${e.message}")
                AppLog.e("NOTIFY", "处理失败：${e.message}")
            }
        }
    }

    /** 读取最近处理记录（设置页展示用）。返回最近 20 条，最新在列表头部。 */
    fun getRecent(context: Context): List<String> {
        synchronized(recentLock) {
            if (recentCache.isEmpty()) {
                val arr = JSONArray(context.getSharedPreferences(RECENT_FILE, Context.MODE_PRIVATE).getString(RECENT_KEY, "[]"))
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i))
                recentCache = out
            }
            return recentCache
        }
    }

    /** 清空最近记录（设置页「清空」按钮）。 */
    fun clearRecent(context: Context) {
        synchronized(recentLock) {
            recentCache = emptyList()
            context.getSharedPreferences(RECENT_FILE, Context.MODE_PRIVATE).edit().putString(RECENT_KEY, "[]").apply()
        }
    }

    private fun whitelistHit(context: Context, text: String): Boolean {
        val prefs = Prefs.get(context, Prefs.Category.NOTIFY)
        if (!prefs.getBoolean("enabled", true)) return false
        val keywords = prefs.getStringSet("keywords", null) ?: DEFAULT_KEYWORDS
        return keywords.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
    }

    private fun record(context: Context, line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "$ts $line"
        val prefs = context.getSharedPreferences(RECENT_FILE, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(RECENT_KEY, "[]"))
        arr.put(0, entry)
        while (arr.length() > RECENT_MAX) arr.remove(arr.length() - 1)
        prefs.edit().putString(RECENT_KEY, arr.toString()).apply()
        synchronized(recentLock) {
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            recentCache = out
        }
    }
}

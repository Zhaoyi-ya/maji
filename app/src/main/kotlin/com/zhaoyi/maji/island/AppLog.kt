package com.zhaoyi.maji.island

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 内存日志，最多保留 200 条，也写入文件到 cache/logs/。
 */
object AppLog {
    private const val MAX_LINES = 200
    private val lines = CopyOnWriteArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun i(tag: String, msg: String) = append("[$tag] $msg")
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val suffix = tr?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        append("[$tag] ❌ $msg$suffix")
    }

    private fun append(s: String) {
        val ts = fmt.format(Date())
        val line = "$ts $s"
        android.util.Log.i("AppLog", line)
        lines.add(line)
        if (lines.size > MAX_LINES) lines.removeAt(0)
    }

    fun all(): List<String> = lines.toList()

    /** 追加写入文件 */
    fun dumpToFile(context: Context) {
        try {
            val dir = File(context.cacheDir, "logs")
            dir.mkdirs()
            val f = File(dir, "maji_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.log")
            f.appendText(all().joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }
}

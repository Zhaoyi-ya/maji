package com.zhaoyi.maji.bill

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.zhaoyi.maji.Prefs
import com.zhaoyi.maji.data.AppDatabase
import com.zhaoyi.maji.island.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 备份执行器：把全部账单生成为加密 `.zyk`（AES-256-GCM，见 [BillBackup]）或通用 CSV，
 * 按设置开关写入本地公共目录（Download/MaJiBackup，媒体库可见）、并/或上传到 WebDAV。
 *
 * 本地写到公共 [MediaStore.Downloads]，无需任何运行时权限，也不会触发存储权限弹窗；
 * 用户用文件管理器 / 电脑 MTP（USB 传输）即可直接取出，**无需 root**。
 * 文件名固定为 `maji_backup.<ext>`，每次备份覆盖旧文件，避免在 Download 里堆积副本。
 */
object BackupManager {

    private const val LOCAL_REL_PATH = "MaJiBackup"
    private const val LOCAL_NAME = "maji_backup"

    /** 执行一次完整备份（本地 + WebDAV 按开关）。返回简要结果描述。 */
    suspend fun runBackup(context: Context): String = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(context, Prefs.Category.BACKUP)
        val all = AppDatabase.getInstance(context).transactionDao().getAllList()
        val encrypt = prefs.getBoolean("encrypt", true)
        val password = prefs.getString("password", "") ?: ""
        val useEncrypt = encrypt && password.isNotBlank()
        val bytes = if (useEncrypt) {
            BillBackup.encryptZyk(all, password)
        } else {
            BillBackup.buildCsv(all).toByteArray(StandardCharsets.UTF_8)
        }
        val ext = if (useEncrypt) "zyk" else "csv"
        val filename = "$LOCAL_NAME.$ext"

        val results = mutableListOf<String>()
        if (prefs.getBoolean("local_enabled", true)) {
            results.add(if (writeLocal(context, bytes, filename)) "本地✓" else "本地✗")
        }
        if (prefs.getBoolean("webdav_enabled", false)) {
            val base = (prefs.getString("webdav_url", "") ?: "").trimEnd('/')
            val user = prefs.getString("webdav_user", "") ?: ""
            val pass = prefs.getString("webdav_pass", "") ?: ""
            if (base.isNotBlank()) {
                val ok = WebDavClient.upload("$base/$filename", user, pass, bytes)
                results.add(if (ok) "WebDAV✓" else "WebDAV✗")
            } else {
                results.add("WebDAV:未配置地址")
            }
        }
        val summary = "备份 ${all.size} 条 → ${results.joinToString(" ")}"
        AppLog.i("Backup", summary)
        summary
    }

    /**
     * 写入公共 Download/MaJiBackup 目录（媒体库可见，文件管理器 / MTP 可直接读取，无需 root）。
     * 先删除同名旧备份再插入，保持单文件覆盖。返回是否成功。
     */
    private fun writeLocal(context: Context, bytes: ByteArray, filename: String): Boolean {
        return try {
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val relPath = Environment.DIRECTORY_DOWNLOADS + "/$LOCAL_REL_PATH/"
            // 清理同名旧文件，避免公共目录堆积
            resolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(filename, relPath),
            )

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    if (filename.endsWith("zyk")) "application/octet-stream" else "text/csv",
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$LOCAL_REL_PATH")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            AppLog.e("Backup", "本地写入失败：${e.message}")
            false
        }
    }
}

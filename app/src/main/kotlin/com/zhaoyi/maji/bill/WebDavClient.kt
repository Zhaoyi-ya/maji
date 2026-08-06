package com.zhaoyi.maji.bill

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 最小 WebDAV 上传客户端：纯 [HttpURLConnection]，零第三方依赖（与项目"不引第三方"原则一致）。
 *
 * WebDAV 本质就是带认证的文件型 HTTP：PUT 即上传。部分服务端要求父集合已存在，
 * 此时 PUT 返回 409，我们对父路径 MKCOL 建目录后重试一次。
 */
object WebDavClient {

    /**
     * 把 [data] PUT 到 WebDAV 地址 [url]。
     * @return 是否成功（2xx / 201 / 204）。
     */
    fun upload(
        url: String,
        username: String,
        password: String,
        data: ByteArray,
        connectTimeoutMs: Int = 30_000,
        readTimeoutMs: Int = 60_000,
    ): Boolean {
        val auth = buildAuth(username, password)
        if (!put(url, auth, data, connectTimeoutMs, readTimeoutMs)) {
            mkcol(parentDir(url), auth, connectTimeoutMs, readTimeoutMs)
            return put(url, auth, data, connectTimeoutMs, readTimeoutMs)
        }
        return true
    }

    private fun put(url: String, auth: String?, data: ByteArray, cto: Int, rto: Int): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = cto
            readTimeout = rto
            setRequestProperty("Content-Type", "application/octet-stream")
            if (auth != null) setRequestProperty("Authorization", auth)
        }
        return try {
            conn.outputStream.use { it.write(data) }
            val code = conn.responseCode
            code in 200..299 || code == 201 || code == 204
        } catch (_: Exception) {
            false
        }
    }

    private fun mkcol(dirUrl: String, auth: String?, cto: Int, rto: Int): Boolean {
        if (dirUrl.isBlank()) return false
        val conn = (URL(dirUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "MKCOL"
            connectTimeout = cto
            readTimeout = rto
            if (auth != null) setRequestProperty("Authorization", auth)
        }
        return try {
            val code = conn.responseCode
            // 405 = 已存在，也视为成功
            code in 200..299 || code == 201 || code == 204 || code == 405
        } catch (_: Exception) {
            false
        }
    }

    private fun parentDir(url: String): String {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val slash = path.lastIndexOf('/')
        return if (slash > 8) path.substring(0, slash) else ""
    }

    private fun buildAuth(user: String, pass: String): String? {
        if (user.isBlank() && pass.isBlank()) return null
        val raw = "$user:$pass"
        val encoded = Base64.encodeToString(raw.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encoded"
    }
}

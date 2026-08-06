package com.zhaoyi.maji.island

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 小米 Passport 换票：用 passToken 换取 serviceToken。
 */
object MiclawPassportClient {
    fun refresh(session: MiclawSession): MiclawSession {
        check(session.canRefresh) { "自动续期需要 passToken 和 userId" }
        val deviceId = "pc_${md5(session.userId)}"
        val uDevId = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((session.userId + deviceId).toByteArray()),
            Base64.NO_WRAP
        )
        val cookie = "passToken=${session.passToken}; userId=${session.userId}; " +
            "cUserId=${session.cUserId}; deviceId=$deviceId; uDevId=$uDevId; uLocale=zh_CN; pass_ua=pc"
        val phase1 = request(
            "https://account.xiaomi.com/pass/serviceLogin?_locale=zh_CN&_snsNone=true&sid=osbotapi&_json=true", cookie
        )
        check(phase1.code in 200..299) { "Passport 阶段1 HTTP ${phase1.code}" }
        val raw = phase1.body.removePrefix("&&&START&&&")
        val json = JSONObject(raw)
        check(json.optInt("code", -1) == 0) { "Passport 换票失败：${json.optString("description", "未知错误")}" }
        val location = json.getString("location")
        val nonce = Regex("\"nonce\"\\s*:\\s*(-?\\d+)").find(raw)?.groupValues?.get(1)
            ?: error("缺少 nonce")
        val sig = "nonce=$nonce" + json.optString("ssecurity").takeIf(String::isNotBlank)?.let { "&$it" }.orEmpty()
        val sign = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()), Base64.NO_WRAP
        )
        val phase2Url = location + (if ('?' in location) "&" else "?") + "clientSign=${URLEncoder.encode(sign, "UTF-8")}"
        var phase2 = request(phase2Url)
        var token = phase2.token()
        if (token == null && phase2.code in 300..399 && phase2.location != null) {
            phase2 = request(URI(phase2Url).resolve(phase2.location).toString())
            token = phase2.token()
        }
        check(!token.isNullOrBlank() && token != "EXPIRED") { "Passport 未返回 serviceToken" }
        return session.copy(serviceToken = token, savedAt = System.currentTimeMillis())
    }

    private fun request(url: String, cookie: String? = null): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.instanceFollowRedirects = false; c.connectTimeout = 30_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "*/*")
            cookie?.let { c.setRequestProperty("Cookie", it) }
            val code = c.responseCode
            HttpResult(code, (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty(),
                c.headerFields.filterKeys { it.equals("Set-Cookie", true) }.values.flatten(), c.getHeaderField("Location"))
        } finally { c.disconnect() }
    }

    private data class HttpResult(val code: Int, val body: String, val cookies: List<String>, val location: String?) {
        fun token() = cookies.firstNotNullOfOrNull {
            it.substringBefore(';').takeIf { v -> v.startsWith("serviceToken=") }?.substringAfter('=')
        }
    }

    private fun md5(v: String) = MessageDigest.getInstance("MD5").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }
    private const val UA = "miNative PC/Normal(Apple Mac16,10) Darwin/25.6.0 SDKV/0.0.1 MK/TWFjLW1pbmkubGFu L/zh-CN DEVT/UEM= DEVS/TWFj BRA/QXBwbGU= APP/Xiaomi miclaw APPV/0.0.1-beta.114+a3e3203f Chrome/144.0.7559.111"
}

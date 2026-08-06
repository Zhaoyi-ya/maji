package com.zhaoyi.maji.island

import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 小米账号密码登录 → 换取 Miclaw serviceToken。
 */
class MiclawAccountLoginClient {
    sealed interface Outcome {
        data class Authenticated(val session: MiclawSession) : Outcome
        data class CaptchaRequired(val imageBytes: ByteArray) : Outcome
        data class TwoFactorRequired(val options: List<Int>) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private var cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private var pendingAccount = ""
    private var pendingHash = ""

    fun login(account: String, password: String, captcha: String = ""): Outcome {
        require(account.isNotBlank() && password.isNotBlank()) { "请输入小米账号和密码" }
        if (captcha.isBlank()) resetTransport()
        pendingAccount = account.trim()
        pendingHash = md5Upper(password)
        val json = postForm(SERVICE_LOGIN_AUTH2, mapOf(
            "user" to pendingAccount, "hash" to pendingHash, "sid" to "osbotapi", "_json" to "true", "_locale" to "zh_CN",
        ) + if (captcha.isBlank()) emptyMap() else mapOf("captCode" to captcha.trim())).json()
        json.optString("captchaUrl").takeIf { it.isNotBlank() && it != "null" }?.let { path ->
            return Outcome.CaptchaRequired(request(resolve(ACCOUNT_HOST, path)).body)
        }
        json.optString("notificationUrl").takeIf { it.isNotBlank() && it != "null" }?.let { path ->
            val list = request(resolve(ACCOUNT_HOST, path)
                .replace("fe/service/identity/authStart", "identity/list")
                .replace("fe/service/identityauthStart", "identity/list")).json()
            val options = list.optJSONArray("options")?.let { arr ->
                buildList { repeat(arr.length()) { add(arr.optInt(it)) } }.filter { it > 0 }
            } ?: emptyList()
            return if (options.isEmpty()) Outcome.Failed("二次验证未返回可用方式")
            else Outcome.TwoFactorRequired(options)
        }
        val code = json.optInt("code", -1)
        if (code != 0) return Outcome.Failed(errMsg(json, "登录失败", code))
        return runCatching { Outcome.Authenticated(refresh(json)) }
            .getOrElse { Outcome.Failed(it.message ?: "换票失败") }
    }

    fun sendTicket(flag: Int) {
        check(flag == PHONE_FLAG || flag == EMAIL_FLAG) { "不支持的验证方式" }
        val r = postForm("$ACCOUNT_HOST/${if (flag == PHONE_FLAG) "identity/auth/sendPhoneTicket" else "identity/auth/sendEmailTicket"}?_dc=${System.currentTimeMillis()}",
            mapOf("_json" to "true", "retry" to "0", "icode" to "")).json()
        check(r.optInt("code", -1) == 0) { errMsg(r, "发送验证码失败", r.optInt("code", -1)) }
    }

    fun verifyTicket(flag: Int, ticket: String): Outcome {
        check(pendingAccount.isNotBlank()) { "登录状态已失效" }
        val verifyUrl = "$ACCOUNT_HOST/${
            if (flag == PHONE_FLAG) "identity/auth/verifyPhone" else "identity/auth/verifyEmail"
        }?_dc=${System.currentTimeMillis()}"
        val v = postForm(verifyUrl, mapOf("_flag" to flag.toString(), "ticket" to ticket.trim(), "trust" to "true", "_json" to "true")).json()
        val code = v.optInt("code", -1)
        if (code != 0) return Outcome.Failed(errMsg(v, "验证失败", code))
        v.optString("location").takeIf(String::isNotBlank)?.let(::followRedirects)
        val auth = postForm(SERVICE_LOGIN_AUTH2, mapOf(
            "user" to pendingAccount, "hash" to pendingHash, "sid" to "osbotapi", "_json" to "true", "_locale" to "zh_CN"
        )).json()
        return runCatching { Outcome.Authenticated(refresh(auth)) }
            .getOrElse { Outcome.Failed(it.message ?: "二次验证后换票失败") }
    }

    fun reset() { resetTransport(); pendingAccount = ""; pendingHash = "" }

    private fun refresh(json: JSONObject): MiclawSession {
        val rawUserId = json.opt("userId")
        val userId = when (rawUserId) { is String -> rawUserId; is Number -> rawUserId.toString(); else -> "" }
        val session = MiclawSession(
            passToken = json.optString("passToken"), userId = userId,
            cUserId = json.optString("cUserId"))
        check(session.canRefresh) { "缺少 passToken 或 userId" }
        return MiclawPassportClient.refresh(session)
    }

    private fun followRedirects(start: String) {
        var cur = start
        repeat(6) {
            val r = request(cur)
            if (r.code !in 300..399 || r.location.isNullOrBlank()) return
            cur = resolve(cur, r.location)
        }
    }

    private fun postForm(url: String, form: Map<String, String>) = request(url, "POST",
        "application/x-www-form-urlencoded; charset=UTF-8",
        form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }.toByteArray())

    private fun request(url: String, method: String = "GET", ct: String? = null, body: ByteArray? = null): HttpResult {
        val uri = URI(url); val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.instanceFollowRedirects = false; c.connectTimeout = 30_000; c.readTimeout = 30_000
            c.requestMethod = method
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "*/*")
            cookies.get(uri, emptyMap())["Cookie"]?.let { c.setRequestProperty("Cookie", it.joinToString("; ")) }
            if (body != null) {
                c.doOutput = true; c.setFixedLengthStreamingMode(body.size)
                ct?.let { c.setRequestProperty("Content-Type", it) }
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode; cookies.put(uri, c.headerFields)
            HttpResult(code, (if (code >= 400) c.errorStream else c.inputStream)?.readBytes() ?: byteArrayOf(), c.getHeaderField("Location"))
        } finally { c.disconnect() }
    }

    private data class HttpResult(val code: Int, val body: ByteArray, val location: String?) {
        fun json() = JSONObject(body.toString(Charsets.UTF_8).removePrefix("&&&START&&&"))
            .also { check(code in 200..299) { "小米账号服务 HTTP $code" } }
    }

    private fun resetTransport() { cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL) }

    companion object {
        const val ACCOUNT_HOST = "https://account.xiaomi.com"
        const val SERVICE_LOGIN_AUTH2 = "$ACCOUNT_HOST/pass/serviceLoginAuth2"
        const val PHONE_FLAG = 4
        const val EMAIL_FLAG = 8
        const val UA = "Dalvik/2.1.0 (Linux; U; Android 16; 2509FPN0BC Build/BP2A.250605.031.A3)"
        fun encode(v: String) = URLEncoder.encode(v, "UTF-8")
        fun resolve(base: String, target: String) = URI(base).resolve(target).toString()
        fun md5Upper(v: String) = MessageDigest.getInstance("MD5").digest(v.toByteArray()).joinToString("") { "%02X".format(it) }
        fun errMsg(json: JSONObject, fallback: String, code: Int) =
            listOf("tips", "desc", "description").map(json::optString).firstOrNull(String::isNotBlank)?.let { "$it (code=$code)" } ?: "$fallback (code=$code)"
    }
}

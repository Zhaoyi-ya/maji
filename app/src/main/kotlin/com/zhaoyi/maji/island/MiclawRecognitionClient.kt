package com.zhaoyi.maji.island

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小米 MiMo 模型识别客户端。通过 MiclawSession 的 serviceToken 鉴权。
 */
class MiclawRecognitionClient(private val context: Context) {

    private val API_URL = "https://api.miclaw.xiaomi.net/osbot/pc/llm/v1/chat/completions"
    private val MODEL = "xiaomi/mimo"

    private companion object {
        /** Rust 侧非 2xx 抛出的异常格式为 "HTTP 403: {...}"，用它取回真实状态码 */
        val HTTP_CODE = Regex("""HTTP (\d{3})""")
    }

    suspend fun recognize(imageBytes: ByteArray): String {
        var session = MiclawSessionStore.load(context)
            ?: error("请先登录小米 MiMo 账号（设置页→MiMo登录）")

        try {
        // 尽早并行启动 IPv4 解析（钉死，绕过 IPv6 黑洞），与下方组包并行，避免识别线程被 DNS 阻塞
        RustBridge.awaitMiclawV4()

        val payload = buildPayload(imageBytes)

        AppLog.i("Mimo", "发送请求 ${API_URL} 模型=${MODEL} token=${session.serviceToken.take(10)}…")
        // 识别前若连接池已冷，静默暖连接一次，避免首次连接偶发失败
        if (!RustBridge.isWarm()) {
            withContext(Dispatchers.IO) { RustBridge.warmUpMiclaw(session.serviceToken, session.cUserId) }
        }
        var resp = post(session, payload)
        AppLog.i("Mimo", "HTTP ${resp.first}, bodyLen=${resp.second.length}")
        if (resp.first == 401 && session.canRefresh) {
            AppLog.i("Mimo", "401 尝试 Passport 换票")
            try {
                session = MiclawPassportClient.refresh(session)
                MiclawSessionStore.save(context, session)
                AppLog.i("Mimo", "换票成功, 重试请求")
                if (!RustBridge.isWarm()) {
                    withContext(Dispatchers.IO) { RustBridge.warmUpMiclaw(session.serviceToken, session.cUserId) }
                }
                resp = post(session, payload)
            } catch (e: Exception) {
                AppLog.e("Mimo", "换票失败", e)
                MiclawSessionStore.clear(context)
                error("MiMo 登录已过期，请重新登录")
            }
        }

        // 服务端图片内容审核误伤（电商页满屏商品图/模特图最常见）：
        // 转灰度 + 降采样后重试一次，去色可大幅降低审核模型的违规打分，而文字仍可读。
        if (resp.isContentRejected()) {
            AppLog.i("Mimo", "403 图片内容审核拒绝，改用灰度降采样图重试")
            val soft = withContext(Dispatchers.Default) { ImageSanitizer.sanitize(imageBytes) }
            if (soft != null) {
                AppLog.i("Mimo", "净化重试：${imageBytes.size / 1024}KB → ${soft.size / 1024}KB")
                resp = post(session, buildPayload(soft))
                AppLog.i("Mimo", "净化重试结果 HTTP ${resp.first}")
            } else {
                AppLog.e("Mimo", "图片净化失败，无法重试")
            }
        }
        if (resp.isContentRejected()) {
            dumpRejected(imageBytes)
            error("图片被小米内容审核拦截，建议截取只含取件码的区域后重试")
        }

        if (resp.first !in 200..299) error("MiMo 识别失败：HTTP ${resp.first} ${resp.second.take(300)}")
        AppLog.i("MiMo", "识别成功, bodyLen=${resp.second.length}")
        return resp.second
        } catch (e: Exception) {
            AppLog.e("MiMo", "post 异常: ${e.javaClass.simpleName}: ${e.message} | ${e.stackTraceToString().take(500)}")
            throw e
        }
    }

    /**
     * 纯文本识别（短信取件码等）：复用 Rust 桥 [RustBridge.callMiclaw] 走 MiMo 文本接口，
     * 不附带图片，因此无需图片内容审核净化；鉴权/换票逻辑与 [recognize] 一致。
     */
    suspend fun recognizeText(text: String): String {
        var session = MiclawSessionStore.load(context)
            ?: error("请先登录小米 MiMo 账号（设置页→MiMo登录）")
        try {
            RustBridge.awaitMiclawV4()
            val payload = buildTextPayload(text)
            if (!RustBridge.isWarm()) {
                withContext(Dispatchers.IO) { RustBridge.warmUpMiclaw(session.serviceToken, session.cUserId) }
            }
            var resp = post(session, payload)
            if (resp.first == 401 && session.canRefresh) {
                AppLog.i("Mimo", "401 尝试 Passport 换票")
                session = MiclawPassportClient.refresh(session)
                MiclawSessionStore.save(context, session)
                if (!RustBridge.isWarm()) {
                    withContext(Dispatchers.IO) { RustBridge.warmUpMiclaw(session.serviceToken, session.cUserId) }
                }
                resp = post(session, payload)
            }
            if (resp.first !in 200..299) error("MiMo 文本识别失败：HTTP ${resp.first} ${resp.second.take(300)}")
            return resp.second
        } catch (e: Exception) {
            AppLog.e("MiMo", "text post 异常: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /**
     * 组装一次纯文本请求体（与 [buildPayload] 同构）：把截图识别的提示词 [loadPrompt]
     * 作为 text 内容、短信正文作为第二个 text 内容——相当于"把图片换成文字"交给同一套模型，
     * 因此大模型按已适配取餐码/取件码的同一 JSON 格式返回，无需任何独立提示词。
     */
    private fun buildTextPayload(text: String): JSONObject {
        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", RecognitionPipeline.loadPrompt(context)))
            put(JSONObject().put("type", "text").put("text", text))
        }
        return JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            put("temperature", 0)
            put("max_tokens", 4096)
            put("stream", false)
            put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            put("response_format", JSONObject().put("type", "json_object"))
        }
    }

    /** 组装一次多模态请求体（文本提示 + base64 图片） */
    private fun buildPayload(imageBytes: ByteArray): JSONObject {
        val encoded = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", RecognitionPipeline.loadPrompt(context)))
            put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$encoded")))
        }
        return JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            put("temperature", 0)
            put("max_tokens", 4096)
            put("stream", false)
            put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            put("response_format", JSONObject().put("type", "json_object"))
        }
    }

    /** 服务端图片内容审核拒绝：HTTP 403 + content_policy_violation / code 30003 */
    private fun Pair<Int, String>.isContentRejected(): Boolean =
        first == 403 && (second.contains("content_policy", true) ||
            second.contains("content moderation", true) || second.contains("30003"))

    /** 把被审核拒绝的原图留一份到 cache/logs/，方便事后确认到底是什么画面触发的（只保留最近一张） */
    private fun dumpRejected(imageBytes: ByteArray) {
        try {
            val dir = java.io.File(context.cacheDir, "logs")
            dir.mkdirs()
            java.io.File(dir, "rejected_last.jpg").writeBytes(imageBytes)
            AppLog.i("Mimo", "已保存被拒图片到 cache/logs/rejected_last.jpg")
        } catch (_: Exception) {}
    }

    private suspend fun post(session: MiclawSession, payload: JSONObject): Pair<Int, String> {
        val t0 = System.currentTimeMillis()
        Log.d("maji_dbg", "post -> callMiclaw start")
        val json = try {
            RustBridge.callMiclaw(session.serviceToken, session.cUserId, payload.toString())
        } catch (e: Exception) {
            Log.d("maji_dbg", "post FAILED cost=${System.currentTimeMillis()-t0}ms: ${e.javaClass.simpleName}: ${e.message}")
            AppLog.e("MiMo", "Rust 调用失败: ${e.javaClass.simpleName}: ${e.message}")
            val msg = e.message ?: "unknown"
            // Rust 侧把非 2xx 统一抛成 "HTTP <code>: <body>"，这里解析出真实状态码交给上层分流。
            // 旧写法是 msg.contains("401")，既漏掉 403，又会被响应体里恰好出现的 "401" 误伤。
            HTTP_CODE.find(msg)?.groupValues?.get(1)?.toIntOrNull()?.let { return it to msg }
            throw e
        }
        Log.d("maji_dbg", "post OK cost=${System.currentTimeMillis()-t0}ms")
        return 200 to json
    }

    suspend fun testConnection(): String {
        val session = MiclawSessionStore.load(context) ?: error("请先登录小米 MiMo")
        val payload = JSONObject().apply {
            put("model", MODEL)
            put("stream", false)
            put("temperature", 0)
            put("max_tokens", 32)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "只返回{\"ok\":true}")))
        }
        val (code, _) = post(session, payload)
        if (code !in 200..299) error("Miclaw 通讯失败：HTTP $code")
        return "MiMo 通讯正常"
    }
}

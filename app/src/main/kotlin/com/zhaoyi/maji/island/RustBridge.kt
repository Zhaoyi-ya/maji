package com.zhaoyi.maji.island

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * JNI 桥接到 Rust maji_core.so。
 * 通过 reqwest + rustls 调用 MiMo API，避免 HttpURLConnection keep-alive 问题。
 */
object RustBridge {
    private var loaded = false

    private const val MICLAW_HOST = "api.miclaw.xiaomi.net"

    /** 上次暖连接成功的时间戳，用于判断当前连接池是否还热。 */
    private val lastWarmTs = AtomicLong(0)

    // ── IPv4 钉死：冷启动也能可靠拿到 IPv4，绕过 IPv6 黑洞 ──
    @Volatile private var cachedV4: String? = null
    @Volatile private var cachedV4Ts: Long = 0
    private val resolveLock = Any()
    private var inFlight: Deferred<String?>? = null
    private val resolveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dnsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "maji-dns").also { it.isDaemon = true }
    }
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}\$")

    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("maji_core")
            loaded = true
        }
    }

    /**
     * 调用 MiMo API。
     * @return JSON: {"success":true,"body":"..."} 或 {"success":false,"error":"..."}
     */
    @JvmStatic
    external fun callMiclawNative(
        serviceToken: String,
        cUserId: String,
        bodyJson: String,
    ): String

    /**
     * 暖连接：轻量预请求，把连接池（DNS/TLS/套接字）暖热，避免正式请求首次连接失败。
     * 失败会内部静默重试，调用方无需关心。成功时记录时间戳。
     */
    @JvmStatic
    external fun warmUpMiclawNative(
        serviceToken: String,
        cUserId: String,
    ): String

    /** Kotlin 侧用 Android 平台解析器解析出 IPv4，传给 Rust 钉死，绕过 IPv6 黑洞。 */
    @JvmStatic
    external fun setMiclawV4Native(ip: String)

    /** 连接池是否在温热窗口内（60s）。 */
    fun isWarm(): Boolean = System.currentTimeMillis() - lastWarmTs.get() < 60_000

    /** 在专用线程上解析 IPv4，带超时。返回纯 IPv4 或 null。不阻塞调用方线程。 */
    private fun resolveV4Now(timeoutMs: Long): String? = try {
        val f = dnsExecutor.submit<String?> {
            try {
                InetAddress.getAllByName(MICLAW_HOST)
                    .firstOrNull { it is Inet4Address }
                    ?.hostAddress?.trim()
                    ?.takeIf { IPV4.matches(it) }
            } catch (_: Exception) { null }
        }
        f.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: Exception) { null }

    /** 单次基础设施：解析并钉死 IPv4（重试若干次，给冷启动 radio 起来的时间）。
     *  结果通过 Deferred 在多调用方（预热的 service、识别的 call）间共享，避免重复 DNS。 */
    private fun resolveOnce(): Deferred<String?> = synchronized(resolveLock) {
        inFlight?.let { return it }
        val d = resolveScope.async {
            var ip: String? = null
            repeat(4) { i ->
                ip = resolveV4Now(4000)
                if (ip != null) {
                    cachedV4 = ip
                    cachedV4Ts = System.currentTimeMillis()
                    try { setMiclawV4Native(ip!!) } catch (_: Exception) {}
                    return@async ip
                }
                if (i < 3) delay(2000)
            }
            // 冷启动网络始终解析不到时，回退用上次成功过（若有）的 IP 兜底，
            // 保证至少钉死 IPv4，不走 IPv6 黑洞。
            cachedV4
        }
        inFlight = d
        d.invokeOnCompletion { synchronized(resolveLock) { inFlight = null } }
        d
    }

    /** 挂起等待 IPv4 钉死完成（或超时）。识别前调用，避免识别线程被 DNS 阻塞挂死。 */
    suspend fun awaitMiclawV4(timeoutMs: Long = 12_000): String? {
        val c = cachedV4
        if (c != null && System.currentTimeMillis() - cachedV4Ts < 5 * 60_000) return c
        return withTimeoutOrNull(timeoutMs) { resolveOnce().await() } ?: cachedV4
    }

    /** 同步保底：识别/暖连接调用时若已有缓存 IP 直接钉死（不等待），避免任何时刻走默认解析。 */
    private fun pinCachedIfAny() {
        cachedV4?.let { try { setMiclawV4Native(it) } catch (_: Exception) {} }
    }

    /**
     * 同步包装，返回结果文本，抛异常时含错误信息。
     * 内部会先确保 IPv4 已钉死（挂起等待，带超时），避免冷启动走 IPv6 黑洞/默认解析。
     */
    suspend fun callMiclaw(serviceToken: String, cUserId: String, bodyJson: String): String {
        ensureLoaded()
        awaitMiclawV4()
        pinCachedIfAny()
        // 兜底超时：即便 Rust 原生调用因系统延迟等原因挂住，协程也会在 35s 后失败，
        // 走正常 catch→dismiss 流程，绝不让"识别中"永久卡死。
        val raw = withTimeoutOrNull(35_000) {
            callMiclawNative(serviceToken, cUserId, bodyJson)
        } ?: error("MiMo 识别超时：网络被系统延迟，请先打开一次 app 或检查网络")
        val json = JSONObject(raw)
        if (!json.optBoolean("success", false)) {
            error(json.optString("error", "unknown rust error"))
        }
        return json.optString("body", "")
    }

    /**
     * 执行一次暖连接（需在非 UI 线程调用）。返回是否成功。
     * 会先确保 IPv4 已钉死，再暖连接。
     */
    suspend fun warmUpMiclaw(serviceToken: String, cUserId: String): Boolean {
        ensureLoaded()
        awaitMiclawV4()
        pinCachedIfAny()
        return try {
            val raw = withTimeoutOrNull(20_000) {
                warmUpMiclawNative(serviceToken, cUserId)
            } ?: return false
            val ok = JSONObject(raw).optBoolean("success", false)
            if (ok) lastWarmTs.set(System.currentTimeMillis())
            ok
        } catch (_: Exception) { false }
    }
}

package com.zhaoyi.maji.island

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 通过 Shizuku UserService 从系统账号 DB 读取小米凭据。
 */
object MiclawCredentialImporter {
    fun import(context: Context): Result<MiclawSession> {
        if (!CaptureHelper.shizukuReady()) return Result.failure(IllegalStateException("需要已授权的 Shizuku"))
        if (runCatching { Shizuku.getUid() }.getOrDefault(-1) != 0) {
            return Result.failure(IllegalStateException("Shizuku 需要 root 模式"))
        }
        val appContext = context.applicationContext
        val args = Shizuku.UserServiceArgs(
            ComponentName(appContext, MiclawCredentialService::class.java)
        ).daemon(false).processNameSuffix("miclaw").debuggable(false).version(1)
        val latch = CountDownLatch(1)
        val ref = AtomicReference<IMiclawProxy?>()
        val err = AtomicReference<Throwable?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                ref.set(MiclawStub.asInterface(binder)); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (ref.get() == null) err.set(IllegalStateException("Miclaw 服务断开"))
                latch.countDown()
            }
        }
        return runCatching {
            Shizuku.bindUserService(args, conn)
            check(latch.await(20, TimeUnit.SECONDS)) { "连接超时" }
            err.get()?.let { throw it }
            val svc = checkNotNull(ref.get()) { "服务未返回 Binder" }
            val json = JSONObject(svc.getSessionJson(false))
            MiclawSession(
                serviceToken = json.optString("serviceToken"),
                passToken = json.optString("passToken"),
                cUserId = json.optString("cUserId"),
                userId = json.optString("userId"),
            ).also { MiclawSessionStore.save(appContext, it) }
        }.also {
            runCatching { ref.get()?.destroy() }
            runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
    }
}

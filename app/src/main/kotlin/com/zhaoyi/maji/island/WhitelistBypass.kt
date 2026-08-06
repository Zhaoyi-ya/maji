package com.zhaoyi.maji.island

import android.content.Context
import android.os.IBinder
import android.util.Log
import com.zhaoyi.maji.Prefs
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 断网绕过小米超级岛白名单。
 *
 * 小米「超级岛」要求应用进入白名单才能上岛：在发布超级岛通知的瞬间，
 * 临时断开小米推送服务（com.xiaomi.xmsf）的联网，
 * 让白名单校验走不通，从而绕过；发送完成后立即恢复联网。
 *
 * 两条路径（任选成功其一），都只需 Shizuku（shell 身份即可，无需 root）：
 * 1. 通过 Shizuku 包装的 ConnectivityService Binder 反射调用隐藏 API
 *    setFirewallChainEnabled / setUidFirewallRule（OEM_DENY_3 链 = 9）。
 * 2. 兜底：用 `cmd connectivity` shell 命令（无需隐藏 API，纯 shell 即可，
 *    适合没有 root、只用 Shizuku 无线调试的用户）。
 *
 * 任意一步失败都优雅降级为「直接发布通知」，不会崩溃。
 */
object WhitelistBypass {
    private const val TAG = "WhitelistBypass"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val FIREWALL_CHAIN_OEM_DENY = 9
    private const val RULE_ALLOW = 0
    private const val RULE_DENY = 2
    private const val BLIND_WINDOW_MS = 100L

    /** 设置项 key（存于 GENERAL prefs） */
    const val PREF_KEY = "whitelist_bypass_enabled"

    private val lock = Any()

    fun isEnabled(context: Context): Boolean =
        Prefs.get(context, Prefs.Category.GENERAL).getBoolean(PREF_KEY, false)

    /**
     * 若 [enabled] 为真且 Shizuku 已授权，则在断开 xmsf 联网期间执行 [block]（发通知），
     * 盲窗 [BLIND_WINDOW_MS] 后恢复联网；否则直接执行 [block]。
     */
    fun runIfEnabled(context: Context, enabled: Boolean, block: () -> Unit) {
        if (!enabled) {
            block()
            return
        }
        if (!CaptureHelper.shizukuReady()) {
            Log.i(TAG, "绕过已开启但 Shizuku 未授权，改为直接发布通知")
            block()
            return
        }

        synchronized(lock) {
            Log.i(TAG, "绕过流程 1/3：断开 $XMSF_PACKAGE 联网")
            val disconnected = setXmsfNetworkingEnabled(context, false)
            if (!disconnected) {
                Log.i(TAG, "断开 $XMSF_PACKAGE 联网失败，改为直接发布通知")
                block()
                return@synchronized
            }

            try {
                Log.i(TAG, "绕过流程 2/3：$XMSF_PACKAGE 已断网，开始发布超级岛通知")
                block()
                Thread.sleep(BLIND_WINDOW_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.e(TAG, "绕过流程被中断：通知发布后准备恢复联网", e)
            } finally {
                Log.i(TAG, "绕过流程 3/3：恢复 $XMSF_PACKAGE 联网")
                if (setXmsfNetworkingEnabled(context, true)) {
                    Log.i(TAG, "绕过流程完成：$XMSF_PACKAGE 联网已恢复")
                } else {
                    Log.e(TAG, "恢复 $XMSF_PACKAGE 联网失败，请检查 Shizuku 状态或重启设备")
                }
            }
        }
    }

    private fun setXmsfNetworkingEnabled(context: Context, enabled: Boolean): Boolean {
        if (setXmsfNetworkingEnabledByConnectivityBinder(context, enabled)) return true
        return setXmsfNetworkingEnabledByConnectivityCommand(context, enabled)
    }

    /** 路径 1：Shizuku 包装的 ConnectivityService Binder + 反射隐藏 API */
    private fun setXmsfNetworkingEnabledByConnectivityBinder(context: Context, enabled: Boolean): Boolean {
        return try {
            val uid = context.packageManager.getPackageUid(XMSF_PACKAGE, 0)
            val connectivityManager = SystemServiceHelper.getSystemService("connectivity")
                ?: run {
                    Log.e(TAG, "无法通过 Shizuku 获取 connectivity 系统服务")
                    return false
                }
            val wrappedBinder = ShizukuBinderWrapper(connectivityManager)
            val service = Class.forName("android.net.IConnectivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, wrappedBinder)

            service.javaClass
                .getMethod("setFirewallChainEnabled", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(service, FIREWALL_CHAIN_OEM_DENY, true)

            val rule = if (enabled) RULE_ALLOW else RULE_DENY
            service.javaClass
                .getMethod(
                    "setUidFirewallRule",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(service, FIREWALL_CHAIN_OEM_DENY, uid, rule)
            Log.i(TAG, "Binder 路径成功：enabled=$enabled, uid=$uid")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Binder 路径失败，准备尝试 cmd connectivity 兜底", e)
            false
        }
    }

    /** 路径 2：cmd connectivity shell 命令兜底（无需隐藏 API，纯 shell 即可） */
    private fun setXmsfNetworkingEnabledByConnectivityCommand(context: Context, enabled: Boolean): Boolean {
        val chainResult = runShizukuCommand(arrayOf("cmd", "connectivity", "set-chain3-enabled", "true"))
        if (chainResult.exitCode != 0) {
            Log.e(TAG, "cmd 兜底失败：无法启用 OEM_DENY_3 链，exit=${chainResult.exitCode}, stderr=${chainResult.stderr}")
            return false
        }
        val packageResult = runShizukuCommand(
            arrayOf("cmd", "connectivity", "set-package-networking-enabled", enabled.toString(), XMSF_PACKAGE),
        )
        if (packageResult.exitCode != 0) {
            Log.e(TAG, "cmd 兜底失败：无法设置 $XMSF_PACKAGE 联网，exit=${packageResult.exitCode}, stderr=${packageResult.stderr}")
            return false
        }
        Log.i(TAG, "cmd 兜底成功：enabled=$enabled")
        return true
    }

    private fun runShizukuCommand(command: Array<String>): ShizukuCommandResult {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val process = method.invoke(null, command, null, null) as rikka.shizuku.ShizukuRemoteProcess
            val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
            val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
            ShizukuCommandResult(process.waitFor(), stdout, stderr)
        } catch (e: Exception) {
            ShizukuCommandResult(-1, "", e.stackTraceToString())
        }
    }

    private data class ShizukuCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}

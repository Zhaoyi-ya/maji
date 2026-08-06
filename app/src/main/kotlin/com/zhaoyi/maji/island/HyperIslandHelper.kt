package com.zhaoyi.maji.island

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.net.toUri

/**
 * HyperOS 超级岛（焦点通知）设备探测。
 *
 * 全部使用公开 API + 反射读 SystemProperties，不依赖 Shizuku / root。
 * 本机已通过 root 解除焦点通知白名单，所以这里只做能力探测、不做鉴权绕过。
 */
object HyperIslandHelper {

    /** 是否是小米（含 Redmi / POCO）设备 */
    fun isXiaomiDevice(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val known = listOf("xiaomi", "redmi", "poco")
        return brand in known || manufacturer in known
    }

    /** 是否运行 HyperOS / MIUI */
    fun isHyperOS(): Boolean =
        getProp("ro.mi.os.version.name").isNotBlank() || getProp("ro.miui.ui.version.name").isNotBlank()

    fun isEligibleDevice(): Boolean = isXiaomiDevice() && isHyperOS()

    /** 系统是否支持超级岛（OS3 feature 开关） */
    fun isSupportIsland(): Boolean = getBoolProp("persist.sys.feature.island")

    /**
     * 焦点通知协议版本：0=不支持，1=OS1，2=OS2，3=OS3（超级岛）。
     */
    fun getFocusProtocolVersion(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
    }.getOrDefault(0)

    /**
     * 查询本应用是否已获得焦点通知权限。**耗时操作，勿在主线程调用。**
     * root 解白名单后这里应返回 true。
     */
    fun hasFocusPermission(context: Context): Boolean = runCatching {
        val uri = "content://miui.statusbar.notification.public".toUri()
        val extras = Bundle().apply { putString("package", context.packageName) }
        context.contentResolver.call(uri, "canShowFocus", null, extras)
            ?.getBoolean("canShowFocus", false) == true
    }.getOrDefault(false)

    /** 综合判断：是否值得走超级岛通道 */
    fun shouldUseSuperIsland(context: Context): Boolean {
        if (!isEligibleDevice()) return false
        return isSupportIsland() || getFocusProtocolVersion(context) >= 3
    }

    /** 给设置页展示的一行状态描述 */
    fun describe(context: Context): String = when {
        !isXiaomiDevice() -> "非小米设备，将降级为普通通知"
        !isHyperOS() -> "未检测到 HyperOS，将降级为普通通知"
        !shouldUseSuperIsland(context) -> "系统不支持超级岛（协议版本 ${getFocusProtocolVersion(context)}）"
        else -> "支持超级岛（协议版本 ${getFocusProtocolVersion(context)}）"
    }

    private fun getProp(key: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
        method.invoke(null, key, "") as String
    }.getOrDefault("")

    private fun getBoolProp(key: String): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        method.invoke(null, key, false) as Boolean
    }.getOrDefault(false)
}

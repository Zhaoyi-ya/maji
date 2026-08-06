package com.zhaoyi.maji.island

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import rikka.shizuku.Shizuku

/**
 * 通过 Shizuku 或 Root 配置系统无障碍快捷方式，
 * 将音量键组合目标指向 [VolumeShortcutAccessibilityService]。
 */
object AccessibilityShortcutConfigurator {

    private const val KEY_TARGET = "accessibility_shortcut_target_service"
    private const val KEY_TARGETS = "accessibility_shortcut_targets"
    private const val KEY_ENABLED = "accessibility_shortcut_enabled"
    private const val KEY_DIALOG_SHOWN = "accessibility_shortcut_dialog_shown"
    private const val KEY_ON_LOCK_SCREEN = "accessibility_shortcut_on_lock_screen"

    /** 系统快捷方式是否已指向本应用的无障碍服务（读取 secure 设置，无需高权限） */
    fun isConfigured(context: Context): Boolean {
        val component = ComponentName(
            context,
            VolumeShortcutAccessibilityService::class.java,
        ).flattenToString()
        val targets = Settings.Secure.getString(context.contentResolver, KEY_TARGETS) ?: ""
        val target = Settings.Secure.getString(context.contentResolver, KEY_TARGET) ?: ""
        return targets.split(":").filter { it.isNotBlank() }.contains(component) || target == component
    }

    fun configure(context: Context): Boolean {
        val component = ComponentName(
            context,
            VolumeShortcutAccessibilityService::class.java,
        ).flattenToString()

        return when {
            CaptureHelper.shizukuReady() -> configureShizuku(component)
            CaptureHelper.hasRoot() -> configureRoot(component)
            else -> false
        }
    }

    /** 关闭系统无障碍快捷方式：清除本组件相关的 secure 设置（经 Shizuku/Root） */
    fun unconfigure(context: Context): Boolean {
        return when {
            CaptureHelper.shizukuReady() -> unconfigureShizuku()
            CaptureHelper.hasRoot() -> unconfigureRoot()
            else -> false
        }
    }

    private fun configureShizuku(component: String): Boolean = listOf(
        shizukuCmd("settings", "put", "secure", KEY_TARGET, component),
        shizukuCmd("settings", "put", "secure", KEY_TARGETS, component),
        shizukuCmd("settings", "put", "secure", KEY_ENABLED, "1"),
        shizukuCmd("settings", "put", "secure", KEY_DIALOG_SHOWN, "1"),
        shizukuCmd("settings", "put", "secure", KEY_ON_LOCK_SCREEN, "1"),
    ).all { it }

    private fun configureRoot(component: String): Boolean = listOf(
        rootCmd("settings put secure $KEY_TARGET \"$component\""),
        rootCmd("settings put secure $KEY_TARGETS \"$component\""),
        rootCmd("settings put secure $KEY_ENABLED 1"),
        rootCmd("settings put secure $KEY_DIALOG_SHOWN 1"),
        rootCmd("settings put secure $KEY_ON_LOCK_SCREEN 1"),
    ).all { it }

    private fun unconfigureShizuku(): Boolean = listOf(
        shizukuCmd("settings", "delete", "secure", KEY_TARGETS),
        shizukuCmd("settings", "delete", "secure", KEY_TARGET),
        shizukuCmd("settings", "delete", "secure", KEY_ENABLED),
    ).all { it }

    private fun unconfigureRoot(): Boolean = listOf(
        rootCmd("settings delete secure $KEY_TARGETS"),
        rootCmd("settings delete secure $KEY_TARGET"),
        rootCmd("settings delete secure $KEY_ENABLED"),
    ).all { it }

    private fun shizukuCmd(vararg args: String): Boolean = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val process = method.invoke(null, args as Array<String>, null, null)
            as rikka.shizuku.ShizukuRemoteProcess
        process.waitFor() == 0
    } catch (_: Exception) { false }

    private fun rootCmd(cmd: String): Boolean = try {
        ProcessBuilder("su", "-c", cmd).start().waitFor() == 0
    } catch (_: Exception) { false }
}

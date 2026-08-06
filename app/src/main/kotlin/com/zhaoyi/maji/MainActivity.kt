package com.zhaoyi.maji

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zhaoyi.maji.ui.MainScreen
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    companion object {
        /** 点击取件码 / 取餐码通知后，进入软件的取件码页面（tab） */
        const val EXTRA_OPEN_PICKUP = "com.zhaoyi.maji.extra.OPEN_PICKUP"
        /** 点击「记一笔」通知后，进入软件并打开对应账单的编辑面板 */
        const val EXTRA_EDIT_TXN_ID = "com.zhaoyi.maji.extra.EDIT_TXN_ID"
        /** 桌面小组件「查看账单」→ 进入软件并切换到报表页 */
        const val EXTRA_OPEN_REPORT = "com.zhaoyi.maji.extra.OPEN_REPORT"
        /** 桌面小组件「+」→ 进入软件并直接弹出「记一笔」新建面板 */
        const val EXTRA_NEW_TXN = "com.zhaoyi.maji.extra.NEW_TXN"
        /** 设置项：是否在多任务最近列表隐藏本应用 */
        const val HIDE_FROM_RECENTS = "hide_from_recents"

        /** 动态控制当前 task 是否从最近任务列表排除（API 21+）。 */
        fun applyHideFromRecents(context: Context, enabled: Boolean) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            am.appTasks?.forEach { it.setExcludeFromRecents(enabled) }
        }
    }

    private val dataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Room Flow 自动刷新，这里仅作标记
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerReceiver(dataChangedReceiver,
            IntentFilter("com.zhaoyi.maji.DATA_CHANGED"),
            Context.RECEIVER_NOT_EXPORTED)

        // 冷启动来自通知点击：先转发意图，缓冲区会保留到 MainScreen 收集器就绪
        handleLaunchIntent(intent)

        val app = application as MaJiApp
        val prefs = Prefs.get(this, Prefs.Category.GENERAL)
        val savedMode = prefs.getString("theme_mode", "system") ?: "system"
        val themeMode = mutableIntStateOf(0)
        val accentIdx = mutableIntStateOf(0)

        themeMode.value = when (savedMode) { "light" -> 1; "dark" -> 2; else -> 0 }
        accentIdx.value = when (prefs.getString("accent_color", "blue")) { "blue" -> 0; "red" -> 1; "yellow" -> 2; "green" -> 3; "teal" -> 4; else -> 0 }

        setContent {
            val mode = themeMode.value
            val colorSchemeMode = when (mode) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            val currentAccent = accentIdx.value
            val accentColor = remember(currentAccent) {
                when (currentAccent) {
                    0 -> Color(0xFF3482FF)
                    1 -> Color(0xFFE94634)
                    2 -> Color(0xFFFFB21D)
                    3 -> Color(0xFF36D167)
                    else -> Color(0xFF1ABC9C)
                }
            }

            val controller = remember(colorSchemeMode, accentColor) {
                ThemeController(
                    colorSchemeMode,
                    lightColors = lightColorScheme(primary = accentColor, onPrimary = Color.White),
                    darkColors = darkColorScheme(primary = accentColor, onPrimary = Color.White)
                )
            }

            val dark = when (mode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose {}
            }

            // 打开 App 即确保前台保活服务在运行：进程在 App 关闭后仍保持存活，
            // MiMo 暖连接 / 超级岛等依赖常驻进程的功能持续可用（不弹额外界面）。
            KeepAliveService.start(this)

            MiuixTheme(controller = controller) {
                CompositionLocalProvider(LocalAppScope provides app.appScope) {
                    MainScreen(
                        initialTab = launchTab(intent),
                        onThemeChange = { newMode ->
                            themeMode.value = when (newMode) { "light" -> 1; "dark" -> 2; else -> 0 }
                            prefs.edit().putString("theme_mode", newMode).apply()
                        },
                        onAccentColorChange = { accent ->
                            accentIdx.value = when (accent) { "blue" -> 0; "red" -> 1; "yellow" -> 2; "green" -> 3; "teal" -> 4; else -> 0 }
                            prefs.edit().putString("accent_color", accent).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销 DATA_CHANGED 广播接收器，否则匿名内部类 receiver 会强持有 Activity
        // 及其整棵 Compose 树，Activity 销毁重建时造成泄漏。
        runCatching { unregisterReceiver(dataChangedReceiver) }
    }

    override fun onResume() {
        super.onResume()
        val enabled = Prefs.get(this, Prefs.Category.GENERAL).getBoolean(HIDE_FROM_RECENTS, false)
        applyHideFromRecents(this, enabled)
    }

    /** App 已在前台时点击通知：系统走 onNewIntent 而非重建。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /**
     * 根据冷启动意图推算首页应停在哪个 tab（0=记账 1=报表 2=取件码）。
     * 仅用于首帧初始化（pager 初始页 + selectedTab），让底部高亮从第一帧就正确；
     * 运行时（App 已在前台）的深链由 [AppLaunchRequests] 流驱动滚动，不依赖此值。
     */
    private fun launchTab(intent: Intent?): Int = when {
        intent?.getBooleanExtra(EXTRA_OPEN_PICKUP, false) == true -> 2
        intent?.getStringExtra(EXTRA_EDIT_TXN_ID) != null -> 0
        intent?.getBooleanExtra(EXTRA_OPEN_REPORT, false) == true -> 1
        else -> 0
    }

    /** 把通知点击携带的动作转发出去，交给 MainScreen 处理。 */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_PICKUP, false)) {
            AppLaunchRequests.send(AppLaunchRequests.OpenPickup)
        }
        val editTxnId = intent.getStringExtra(EXTRA_EDIT_TXN_ID)
        if (editTxnId != null) {
            AppLaunchRequests.send(AppLaunchRequests.OpenEditTxn(editTxnId))
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_REPORT, false)) {
            AppLaunchRequests.send(AppLaunchRequests.OpenReport)
        }
        if (intent.getBooleanExtra(EXTRA_NEW_TXN, false)) {
            AppLaunchRequests.send(AppLaunchRequests.OpenNewTxn)
        }
    }
}

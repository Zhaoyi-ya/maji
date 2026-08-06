package com.zhaoyi.maji.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zhaoyi.maji.island.AccessibilityShortcutConfigurator
import com.zhaoyi.maji.island.CaptureHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun PermissionCheckPage(onBack: () -> Unit, enableBlur: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val blurSupported = isRuntimeShaderSupported()
    val blurOn = enableBlur && blurSupported
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
    val barColor = if (blurOn) Color.Transparent else surface
    val scrollBehavior = MiuixScrollBehavior()

    // 从系统设置返回后重新检测各项权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    var recheck by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var batteryIgnored by remember { mutableStateOf(isIgnoringBattery(context)) }
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var shizukuGranted by remember { mutableStateOf(CaptureHelper.shizukuReady()) }
    var accessibilityOn by remember { mutableStateOf(AccessibilityShortcutConfigurator.isConfigured(context)) }

    LaunchedEffect(recheck) {
        batteryIgnored = isIgnoringBattery(context)
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        shizukuGranted = CaptureHelper.shizukuReady()
        accessibilityOn = AccessibilityShortcutConfigurator.isConfigured(context)
    }

    val highPriv = CaptureHelper.shizukuReady() || CaptureHelper.hasRoot()

    Scaffold(
        topBar = {
            Box(
                modifier = if (blurOn) {
                    Modifier.textureBlur(backdrop = backdrop, shape = RectangleShape, blurRadius = 25f)
                } else {
                    Modifier
                },
            ) {
                TopAppBar(
                    title = "权限检查",
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回") }
                    },
                )
            }
        },
    ) { innerPadding ->
        val lazyListState = rememberLazyListState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurOn) Modifier.layerBackdrop(backdrop) else Modifier)
                .padding(innerPadding),
        ) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
            ) {
                item {
                    SmallTitle("权限与后台")
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        PermissionRow(
                            title = "允许后台运行",
                            summary = "忽略电池优化，避免后台被系统清理",
                            granted = batteryIgnored,
                            onClick = { openIgnoreBattery(context); recheck++ },
                        )
                        PermissionRow(
                            title = "允许自启动",
                            summary = "前往系统设置开启自启动（状态无法自动检测）",
                            granted = false,
                            onClick = { openAutoStart(context); recheck++ },
                        )
                        PermissionRow(
                            title = "允许通知",
                            summary = "接收取件提醒与岛消息",
                            granted = notificationsOn,
                            onClick = { openNotificationSettings(context); recheck++ },
                        )
                        PermissionRow(
                            title = "Shizuku 授权",
                            summary = if (shizukuGranted) "已授权，可用于截图 / 绕过白名单" else "用于截图与绕过白名单，需先授权 Shizuku",
                            granted = shizukuGranted,
                            onClick = { requestShizuku(context); recheck++ },
                        )
                    }
                }

                item {
                    SmallTitle("无障碍")
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        SwitchPreference(
                            title = "无障碍快捷方式",
                            summary = if (!highPriv) "需要 Shizuku 或 Root 才能配置" else "同时按音量+-2s 触发",
                            checked = accessibilityOn,
                            enabled = highPriv,
                            onCheckedChange = { want ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (want) AccessibilityShortcutConfigurator.configure(context)
                                        else AccessibilityShortcutConfigurator.unconfigure(context)
                                    }
                                    // 以系统实际状态为准重新检测，避免用返回值推导导致关闭后 UI 不刷新
                                    accessibilityOn = AccessibilityShortcutConfigurator.isConfigured(context)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单行权限：未授予 -> 可点击并跳转；已授予 -> 灰态不可点击 + 绿色对勾。
 * 自启动无标准查询 API，[granted] 始终传 false（只作引导跳转，不显示对勾）。
 */
@Composable
private fun PermissionRow(
    title: String,
    summary: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        enabled = !granted,
        onClick = if (granted) null else onClick,
        endActions = {
            if (granted) {
                Text(
                    text = "✓",
                    color = Color(0xFF36D167),
                    fontSize = 18.sp,
                )
            } else {
                Image(
                    imageVector = MiuixIcons.Basic.ArrowRight,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions),
                    modifier = Modifier
                        .size(width = 10.dp, height = 16.dp)
                        .align(Alignment.CenterVertically),
                )
            }
        },
    )
}

// ---- 各项权限的检测与跳转 ----

private fun isIgnoringBattery(context: Context): Boolean = try {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.isIgnoringBatteryOptimizations(context.packageName)
} catch (_: Exception) { false }

private fun openIgnoreBattery(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // 个别 ROM 不支持直接申请，退回电池优化总设置页
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openAutoStart(context: Context) {
    // 小米 / MIUI 自启动管理页（尽力跳转），失败退回应用详情页
    val miui = Intent().apply {
        setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(miui)
    } catch (_: Exception) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }
}

private fun requestShizuku(context: Context) {
    if (CaptureHelper.shizukuReady()) return
    if (!CaptureHelper.requestShizuku()) {
        Toast.makeText(context, "请先安装并启动 Shizuku", Toast.LENGTH_LONG).show()
    }
}

package com.zhaoyi.maji.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.zhaoyi.maji.Prefs
import com.zhaoyi.maji.island.NotifIngest
import com.zhaoyi.maji.island.NotifListener
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import dev.chrisbanes.haze.rememberHazeState

/** 是否已授予「通知使用权」（系统级开关，需手动在系统设置里开启）。 */
private fun isNotifListenerEnabled(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
    val self = "${ctx.packageName}/${NotifListener::class.java.name}"
    return flat?.split(":")?.any { it.equals(self, ignoreCase = true) } ?: false
}

@Composable
fun NotifySettingsPage(
    onBack: () -> Unit,
    enableBlur: Boolean = true,
    blurStyle: String = "default",
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx, Prefs.Category.NOTIFY) }

    var notifyEnabled by remember { mutableStateOf(prefs.getBoolean("enabled", true)) }
    var keywords by remember {
        mutableStateOf((prefs.getStringSet("keywords", null) ?: NotifIngest.DEFAULT_KEYWORDS).toMutableSet())
    }
    var systemGranted by remember { mutableStateOf(isNotifListenerEnabled(ctx)) }

    // 长按多选状态（参考取件码页面多选逻辑）
    var multiSelect by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var addMode by remember { mutableStateOf(false) }
    var addText by remember { mutableStateOf("") }

    // 实时读取监听服务连接状态（替代 logcat，HyperOS 对三方 app 隐藏日志）
    var listenerAlive by remember { mutableStateOf(NotifListener.isConnected) }
    LaunchedEffect(Unit) {
        while (true) {
            listenerAlive = NotifListener.isConnected
            kotlinx.coroutines.delay(1500)
        }
    }

    fun persistNotify(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply { block() }.apply()
    }

    fun persistKeywords(set: Set<String>) {
        keywords = set.toMutableSet()
        persistNotify { putStringSet("keywords", keywords) }
    }

    fun deleteSelected() {
        val rm = selected.toSet()
        persistKeywords(keywords - rm)
        selected.clear()
        multiSelect = false
    }

    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = if (enableBlur && isRuntimeShaderSupported()) {
        rememberLayerBackdrop { drawRect(surfaceColor); drawContent() }
    } else null
    val blurActive = backdrop != null
    val hazeState = rememberHazeState()
    val useProgressive = blurStyle == "progressive" && blurActive
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurTopBar(backdrop, blurActive, useProgressive, hazeState) {
                CollapseTopBar(
                    title = if (multiSelect) "已选 ${selected.size} 项" else "通认识别",
                    scrollBehavior = scrollBehavior,
                    barColor = barColor,
                    navigationIcon = {
                        IconButton(onClick = {
                            if (multiSelect) {
                                multiSelect = false
                                selected.clear()
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(
                                if (multiSelect) MiuixIcons.Close else MiuixIcons.Back,
                                contentDescription = if (multiSelect) "退出多选" else "返回",
                            )
                        }
                    },
                    actions = {
                        if (multiSelect) {
                            IconButton(onClick = {
                                selected.clear()
                                selected.addAll(keywords)
                            }) {
                                Icon(
                                    MiuixIcons.SelectAll,
                                    tint = MiuixTheme.colorScheme.onSurface,
                                    contentDescription = "全选",
                                )
                            }
                            IconButton(
                                onClick = { deleteSelected() },
                                enabled = selected.isNotEmpty(),
                            ) {
                                Icon(
                                    MiuixIcons.Delete,
                                    tint = if (selected.isNotEmpty()) {
                                        Color(0xFFE53935)
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                    contentDescription = "删除",
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    addText = ""
                    addMode = true
                },
                containerColor = MiuixTheme.colorScheme.primary,
            ) {
                Icon(
                    MiuixIcons.Add,
                    tint = MiuixTheme.colorScheme.onPrimary,
                    contentDescription = "添加关键词",
                )
            }
        },
    ) { innerPadding ->
        BlurContentBox(backdrop, useProgressive, hazeState) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .overscroll(rememberOverscrollEffect()),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = innerPadding.calculateTopPadding() + 12.dp,
                    bottom = innerPadding.calculateBottomPadding() + 88.dp,
                ),
            ) {
                item(key = "notify") {
                    SmallTitle("通认识别")
                    Card(modifier = Modifier.padding(bottom = 12.dp)) {
                        Text(
                            text = if (listenerAlive) "监听服务：运行中" else "监听服务：未运行（请杀掉后台后重新打开码记）",
                            color = if (listenerAlive) Color(0xFF3F9C4B) else Color(0xFFE53935),
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                        )
                        SwitchPreference(
                            title = "启用通知识别",
                            summary = "命中白名单的通知自动交给大模型提取取件码 / 自动记账",
                            checked = notifyEnabled,
                            onCheckedChange = { wanted ->
                                if (wanted) {
                                    systemGranted = isNotifListenerEnabled(ctx)
                                    if (systemGranted) {
                                        notifyEnabled = true
                                        persistNotify { putBoolean("enabled", true) }
                                    } else {
                                        notifyEnabled = true
                                        persistNotify { putBoolean("enabled", true) }
                                        Toast.makeText(ctx, "请在系统设置中开启「码记」的通知使用权", Toast.LENGTH_LONG).show()
                                        ctx.startActivity(
                                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                } else {
                                    notifyEnabled = false
                                    persistNotify { putBoolean("enabled", false) }
                                }
                            },
                        )
                        AnimatedVisibility(
                            visible = notifyEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            val hint = if (systemGranted) {
                                "通知使用权已开启：仅当通知正文包含白名单关键词时才发送大模型；其余通知不会被读取或上传。"
                            } else {
                                "已开启识别开关，但系统「通知使用权」尚未授予——点击上方开关可重新跳转到系统设置开启，否则收不到通知。"
                            }
                            Text(
                                text = hint,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            )
                        }
                    }
                }

                item(key = "whitelist") {
                    SmallTitle("白名单关键词")
                    if (keywords.isEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            insideMargin = PaddingValues(16.dp),
                            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
                        ) {
                            Text(
                                text = "暂无关键词，点击右下角 + 添加",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                            )
                        }
                    } else {
                        keywords.forEach { kw ->
                            val isSel = kw in selected
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp),
                                insideMargin = PaddingValues(16.dp),
                                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
                                pressFeedbackType = PressFeedbackType.Sink,
                                showIndication = true,
                                onClick = {
                                    if (multiSelect) {
                                        if (isSel) selected.remove(kw) else selected.add(kw)
                                    }
                                },
                                onLongPress = {
                                    if (!multiSelect) {
                                        multiSelect = true
                                        if (!isSel) selected.add(kw)
                                    }
                                },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = kw,
                                        color = if (isSel) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.onSurface
                                        },
                                        fontSize = 15.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (multiSelect) {
                                        Checkbox(
                                            state = if (isSel) ToggleableState.On else ToggleableState.Off,
                                            onClick = {
                                                if (isSel) selected.remove(kw) else selected.add(kw)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加关键词弹窗（+ 号触发）：单条输入框，直接代表「添加」
    WindowDialog(
        show = addMode,
        title = "添加关键词",
        summary = "命中该关键词的通知才会交给大模型（含支付类词可自动记账）",
        onDismissRequest = { addMode = false },
        content = {
            Column {
                TextField(
                    value = addText,
                    onValueChange = { addText = it },
                    label = "关键词",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { addMode = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "保存",
                        onClick = {
                            val t = addText.trim()
                            if (t.isNotBlank()) persistKeywords((keywords + t).toSet())
                            addMode = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

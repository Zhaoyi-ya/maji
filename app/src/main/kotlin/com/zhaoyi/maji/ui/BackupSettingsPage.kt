package com.zhaoyi.maji.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.overscroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.zhaoyi.maji.Prefs
import com.zhaoyi.maji.bill.BackupManager
import com.zhaoyi.maji.bill.BackupScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import dev.chrisbanes.haze.rememberHazeState

@Composable
fun BackupSettingsPage(
    onBack: () -> Unit,
    enableBlur: Boolean = true,
    blurStyle: String = "default",
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs.get(ctx, Prefs.Category.BACKUP) }

    var autoEnabled by remember { mutableStateOf(prefs.getBoolean("auto_enabled", false)) }
    var intervalHours by remember { mutableStateOf(prefs.getInt("interval_hours", 24)) }
    var localEnabled by remember { mutableStateOf(prefs.getBoolean("local_enabled", true)) }
    var webdavEnabled by remember { mutableStateOf(prefs.getBoolean("webdav_enabled", false)) }
    var webdavUrl by remember { mutableStateOf(prefs.getString("webdav_url", "") ?: "") }
    var webdavUser by remember { mutableStateOf(prefs.getString("webdav_user", "") ?: "") }
    var webdavPass by remember { mutableStateOf(prefs.getString("webdav_pass", "") ?: "") }
    var encryptEnabled by remember { mutableStateOf(prefs.getBoolean("encrypt", true)) }
    var encryptPass by remember { mutableStateOf(prefs.getString("password", "") ?: "") }

    val intervalItems = listOf("每小时", "每 6 小时", "每 12 小时", "每天", "每 2 天")
    val intervalKeys = listOf(1, 6, 12, 24, 48)
    val intervalIdx = intervalKeys.indexOf(intervalHours).coerceAtLeast(0)

    fun persistBackup(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply { block() }.apply()
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
                    title = "备份",
                    scrollBehavior = scrollBehavior,
                    barColor = barColor,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            }
        }
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
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                item(key = "auto") {
                    SmallTitle("自动备份")
                    Card(modifier = Modifier.padding(bottom = 12.dp)) {
                        SwitchPreference(
                            title = "自动备份",
                            summary = "按频率周期性备份到本地 / WebDAV",
                            checked = autoEnabled,
                            onCheckedChange = {
                                autoEnabled = it
                                persistBackup { putBoolean("auto_enabled", it) }
                                BackupScheduler.reschedule(ctx)
                            },
                        )
                        AnimatedVisibility(
                            visible = autoEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            WindowDropdownPreference(
                                title = "备份频率",
                                items = intervalItems,
                                selectedIndex = intervalIdx,
                                onSelectedIndexChange = { idx ->
                                    intervalHours = intervalKeys[idx]
                                    persistBackup { putInt("interval_hours", intervalHours) }
                                    BackupScheduler.reschedule(ctx)
                                },
                            )
                        }
                    }
                }

                item(key = "local") {
                    SmallTitle("本地")
                    Card(modifier = Modifier.padding(bottom = 12.dp)) {
                        SwitchPreference(
                            title = "本地备份",
                            summary = "保存到 Download/MaJiBackup（文件管理器 / 电脑可直接读取，无需 root）",
                            checked = localEnabled,
                            onCheckedChange = {
                                localEnabled = it
                                persistBackup { putBoolean("local_enabled", it) }
                            },
                        )
                    }
                }

                item(key = "webdav") {
                    SmallTitle("WebDAV")
                    Card(modifier = Modifier.padding(bottom = 12.dp)) {
                        SwitchPreference(
                            title = "WebDAV 备份",
                            summary = "同步到自建/第三方 WebDAV（如 NAS）",
                            checked = webdavEnabled,
                            onCheckedChange = {
                                webdavEnabled = it
                                persistBackup { putBoolean("webdav_enabled", it) }
                            },
                        )
                        AnimatedVisibility(
                            visible = webdavEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column {
                                TextField(
                                    value = webdavUrl,
                                    onValueChange = {
                                        webdavUrl = it
                                        persistBackup { putString("webdav_url", it) }
                                    },
                                    label = "WebDAV 地址（含目录）",
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                TextField(
                                    value = webdavUser,
                                    onValueChange = {
                                        webdavUser = it
                                        persistBackup { putString("webdav_user", it) }
                                    },
                                    label = "账号",
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                TextField(
                                    value = webdavPass,
                                    onValueChange = {
                                        webdavPass = it
                                        persistBackup { putString("webdav_pass", it) }
                                    },
                                    label = "密码",
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }

                item(key = "encrypt") {
                    SmallTitle("加密")
                    Card(modifier = Modifier.padding(bottom = 12.dp)) {
                        SwitchPreference(
                            title = "加密备份",
                            summary = "AES-256-GCM 加密后再存储/上传",
                            checked = encryptEnabled,
                            onCheckedChange = {
                                encryptEnabled = it
                                persistBackup { putBoolean("encrypt", it) }
                            },
                        )
                        AnimatedVisibility(
                            visible = encryptEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            TextField(
                                value = encryptPass,
                                onValueChange = {
                                    encryptPass = it
                                    persistBackup { putString("password", it) }
                                },
                                label = "加密密码",
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                item(key = "action") {
                    SmallTitle("操作")
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val summary = BackupManager.runBackup(ctx)
                                    launch(Dispatchers.Main) {
                                        Toast.makeText(ctx, summary, Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    launch(Dispatchers.Main) {
                                        Toast.makeText(ctx, "备份失败：${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("立即备份")
                    }
                }
            }
        }
    }
}

package com.zhaoyi.maji.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.overscroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.zhaoyi.maji.island.RecognitionPipeline
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
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import dev.chrisbanes.haze.rememberHazeState

@Composable
fun PromptSettingsPage(
    onBack: () -> Unit,
    enableBlur: Boolean = true,
    blurStyle: String = "default",
) {
    val ctx = LocalContext.current
    val recPrefs = remember { ctx.getSharedPreferences("recognition", android.content.Context.MODE_PRIVATE) }
    val def = RecognitionPipeline.DEFAULT_PROMPT

    // 初始：用户未设置（空白）则展示内置默认；已自定义则展示用户文案。
    var text by remember {
        mutableStateOf((recPrefs.getString("user_prompt", "") ?: "").let { if (it.isBlank()) def else it })
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
                    title = "大模型提示词",
                    scrollBehavior = scrollBehavior,
                    barColor = barColor,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
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
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                item(key = "editor") {
                    SmallTitle("提示词")
                    Card(modifier = Modifier.padding(bottom = 12.dp)) {
                        TextField(
                            value = text,
                            onValueChange = { text = it },
                            label = "大模型提示词",
                            singleLine = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .height(360.dp),
                        )
                        Text(
                            text = "识别时会把通知 / 截图文本附在提示词之后一起发给模型。留空并保存将回退到内置默认提示词；下次打开此处会显示默认文案而非空白。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }

                item(key = "action") {
                    SmallTitle("操作")
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Button(
                            onClick = {
                                val trimmed = text.trim()
                                val toStore = if (trimmed.isBlank() || trimmed == def.trim()) "" else trimmed
                                recPrefs.edit().putString("user_prompt", toStore).apply()
                                Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                                text = if (toStore.isBlank()) def else toStore
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text("保存")
                        }
                        Button(
                            onClick = {
                                text = def
                                recPrefs.edit().putString("user_prompt", "").apply()
                                Toast.makeText(ctx, "已恢复默认提示词", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("恢复默认")
                        }
                    }
                }
            }
        }
    }
}

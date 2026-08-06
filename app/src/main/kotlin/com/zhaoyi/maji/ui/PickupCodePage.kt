package com.zhaoyi.maji.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import com.zhaoyi.maji.data.PickupCode
import com.zhaoyi.maji.R
import com.zhaoyi.maji.util.brandIconRes
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.foundation.combinedClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PickupCodePage(
    viewModel: MainViewModel,
    bottomPadding: androidx.compose.ui.unit.Dp = 72.dp,
    onSettingsClick: () -> Unit = {},
    multiSelect: Boolean = false,
    selectedIds: androidx.compose.runtime.snapshots.SnapshotStateList<String> = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf() },
    onEnterMultiSelect: (String) -> Unit = {},
    onExitMultiSelect: () -> Unit = {},
    enableBlur: Boolean = true,
    blurStyle: String = "default"
) {
    val codes by viewModel.pickupCodes.collectAsState()
    var editingCode by remember { mutableStateOf<PickupCode?>(null) }
    var showEditPickup by remember { mutableStateOf(false) }
    // 单击取件码弹出的操作表（上岛/下岛、已取/未取、编辑、取消）
    var actionCode by remember { mutableStateOf<PickupCode?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val ctx = LocalContext.current

    // 页面级 backdrop：只捕获本页内容（LazyColumn），顶栏作为兄弟节点消费它，
    // 避免自引用层（否则小米 MiBackgroundBlurBlend 触发 SIGSEGV 闪退）。
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
                    title = "取件码",
                    scrollBehavior = scrollBehavior,
                    actions = { HomeTopActions(onSettingsClick) },
                    barColor = barColor
                )
            }
        }
    ) { innerPadding ->
        BlurContentBox(backdrop, useProgressive, hazeState) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomPadding + 80.dp
                )
            ) {
                if (codes.isEmpty()) {
                    item {
                        Text(
                            "还没有取件码，点底部 + 添加一条",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp, start = 32.dp, end = 32.dp)
                        )
                    }
                } else {
                    items(codes, key = { it.id }) { code ->
                        PickupCodeRow(
                            code = code,
                            onClick = {
                                if (multiSelect) {
                                    if (code.id in selectedIds) selectedIds.remove(code.id) else selectedIds.add(code.id)
                                } else {
                                    actionCode = code
                                    showActionDialog = true
                                }
                            },
                            onLongPress = { if (!multiSelect) onEnterMultiSelect(code.id) },
                            selected = code.id in selectedIds,
                            showCheckbox = multiSelect,
                            onToggleSelect = {
                                if (code.id in selectedIds) selectedIds.remove(code.id)
                                else selectedIds.add(code.id)
                            }
                        )
                    }
                }
            }
        }
    }

    AddPickupCodeDialog(
        show = showEditPickup,
        editing = editingCode,
        onDismiss = { showEditPickup = false },
        onDismissFinished = { editingCode = null },
        onSave = { code ->
            if (editingCode != null) viewModel.updatePickupCode(code) else viewModel.addPickupCode(code)
            showEditPickup = false
        },
        onDelete = { viewModel.deletePickupCode(it); showEditPickup = false }
    )

    PickupActionDialog(
        show = showActionDialog,
        code = actionCode,
        onDismiss = { showActionDialog = false },
        onPinToggle = { c ->
            viewModel.toggleIsland(c)
            showActionDialog = false
        },
        onToggleDone = { c ->
            viewModel.toggleDone(c)
            showActionDialog = false
        },
        onEdit = { c ->
            editingCode = c
            showEditPickup = true
            showActionDialog = false
        },
    )
}

@Composable
private fun PickupCodeRow(
    code: PickupCode,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    selected: Boolean,
    showCheckbox: Boolean,
    onToggleSelect: () -> Unit
) {
    // 已取件：内容完整保留，只加删除线并整体变灰
    val done = code.isDone
    val strike = if (done) TextDecoration.LineThrough else null
    val codeColor = if (done) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else MiuixTheme.colorScheme.primary
    val bodyColor = if (done) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else MiuixTheme.colorScheme.onBackground

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        // 按 Miuix 官方 CardSection 示例的写法：点击事件必须交给 Card 本身，
        // 由它内部的 pressable + combinedClickable 统一处理，才能拿到正确的
        // 下沉动效和水波纹；手写在内部 Row 上会导致无反馈、且 insideMargin 点不到。
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
        onLongPress = onLongPress,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(brandIconRes(code.merchant, code.codeKind)),
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .alpha(if (done) 0.45f else 1f)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    code.code,
                    style = MiuixTheme.textStyles.title1.copy(
                        fontWeight = FontWeight.Bold,
                        textDecoration = strike,
                    ),
                    color = codeColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${code.merchant}  ${code.item}",
                    style = MiuixTheme.textStyles.body2.copy(textDecoration = strike),
                    color = bodyColor
                )
                val addr = listOf(code.itemDetail, code.note)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (addr.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        addr,
                        style = MiuixTheme.textStyles.body2.copy(textDecoration = strike),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            if (showCheckbox) {
                Spacer(Modifier.width(8.dp))
                Checkbox(state = if (selected) ToggleableState.On else ToggleableState.Off, onClick = onToggleSelect)
            }
        }
    }
}


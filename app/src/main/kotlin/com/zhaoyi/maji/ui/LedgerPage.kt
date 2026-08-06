package com.zhaoyi.maji.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import com.zhaoyi.maji.data.Transaction
import com.zhaoyi.maji.data.TransactionType
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
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
fun LedgerPage(
    viewModel: MainViewModel,
    bottomPadding: androidx.compose.ui.unit.Dp = 72.dp,
    onSettingsClick: () -> Unit = {},
    multiSelect: Boolean = false,
    selectedIds: androidx.compose.runtime.snapshots.SnapshotStateList<String> = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf() },
    onEnterMultiSelect: (String) -> Unit = {},
    onExitMultiSelect: () -> Unit = {},
    pendingEditTxnId: String? = null,
    onConsumePendingEdit: () -> Unit = {},
    enableBlur: Boolean = true,
    blurStyle: String = "default"
) {
    val transactions by viewModel.transactions.collectAsState()
    var editingTx by remember { mutableStateOf<Transaction?>(null) }
    var showEditTx by remember { mutableStateOf(false) }

    // 点击「记一笔」通知后，打开对应账单的编辑面板（方便识别出错时立即改正）
    LaunchedEffect(pendingEditTxnId, transactions) {
        if (pendingEditTxnId != null) {
            val tx = transactions.firstOrNull { it.id == pendingEditTxnId }
            if (tx != null) {
                editingTx = tx
                showEditTx = true
                onConsumePendingEdit()
            }
        }
    }

    val dayFormat = remember { SimpleDateFormat("M月d日 EEEE", Locale.getDefault()) }
    val monthFormat = remember { SimpleDateFormat("yyyy-MM", Locale.getDefault()) }
    val fullDayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    val thisMonth = monthFormat.format(Date())
    val monthTx = transactions.filter { monthFormat.format(Date(it.date)) == thisMonth }
    val income = monthTx.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
    val expense = monthTx.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    val today = fullDayFormat.format(Date())
    val todayExpense = transactions.filter {
        fullDayFormat.format(Date(it.date)) == today && it.type == TransactionType.EXPENSE
    }.sumOf { it.amount }

    val dayGroups = transactions
        .groupBy { fullDayFormat.format(Date(it.date)) }
        .toSortedMap(compareByDescending { it })

    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()

    // 页面级 backdrop：只捕获本页内容（LazyColumn），顶栏作为兄弟节点消费它，
    // 绝不把顶栏本身放进被捕获的层里——否则会形成自引用层，在小米的
    // MiBackgroundBlurBlend 上触发 SIGSEGV 闪退。对齐 Miuix 示例 app 的每页
    // rememberBlurBackdrop + BlurredBar 模式。
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
                    title = "记账",
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
                item {
                    SummaryCard(
                        todayExpense = todayExpense,
                        monthExpense = expense,
                        monthIncome = income,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                }
                if (transactions.isEmpty()) {
                    item {
                        Text(
                            "还没有记账，点右下角 + 记一笔",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 32.dp, end = 32.dp)
                        )
                    }
                } else {
                    // 一天一张卡片：日期与当日收支作为卡片头部，当日流水收进同一张卡
                    dayGroups.forEach { (day, list) ->
                        item(key = "day_$day") {
                            val d = runCatching { fullDayFormat.parse(day) }.getOrNull()
                            DayCard(
                                dayLabel = if (d != null) dayFormat.format(d) else day,
                                dayIncome = list.filter { it.type == TransactionType.INCOME }.sumOf { it.amount },
                                dayExpense = list.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount },
                                list = list,
                                multiSelect = multiSelect,
                                selectedIds = selectedIds,
                                onEnterMultiSelect = onEnterMultiSelect,
                                onEdit = { editingTx = it; showEditTx = true },
                            )
                        }
                    }
                }
            }
        }
    }

    AddTransactionDialog(
        show = showEditTx,
        editing = editingTx,
        onDismiss = { showEditTx = false },
        onDismissFinished = { editingTx = null },
        onAdd = { updated ->
            if (editingTx != null) viewModel.updateTransaction(updated)
            else viewModel.addTransaction(updated)
            showEditTx = false
        }
    )
}

@Composable
private fun SummaryCard(todayExpense: Double, monthExpense: Double, monthIncome: Double, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Column {
            Text("今日支出（元）", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(4.dp))
            Text("¥%.2f".format(todayExpense), style = MiuixTheme.textStyles.title1, color = MiuixTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("本月支出（元）", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(4.dp))
                    Text("¥%.2f".format(monthExpense), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
                }
                Column(Modifier.weight(1f)) {
                    Text("本月收入（元）", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(4.dp))
                    Text("¥%.2f".format(monthIncome), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
                }
            }
        }
    }
}

/**
 * 一天一张卡片。卡片头部是日期 + 当日收支合计，下面是当日全部流水。
 * insideMargin 设为 0，由每一行自己带内边距，这样行的点击区域能铺满整张卡的宽度。
 */
@Composable
private fun DayCard(
    dayLabel: String,
    dayIncome: Double,
    dayExpense: Double,
    list: List<Transaction>,
    multiSelect: Boolean,
    selectedIds: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    onEnterMultiSelect: (String) -> Unit,
    onEdit: (Transaction) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                dayLabel,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                "支${"%.2f".format(dayExpense)} 收${"%.2f".format(dayIncome)}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        // 只在日期头部与账单内容之间保留一条分界线，账单行之间不再分隔
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        list.forEach { t ->
            TransactionRow(
                t,
                onClick = {
                    if (multiSelect) {
                        if (t.id in selectedIds) selectedIds.remove(t.id) else selectedIds.add(t.id)
                    } else {
                        onEdit(t)
                    }
                },
                onLongPress = { if (!multiSelect) onEnterMultiSelect(t.id) },
                selected = t.id in selectedIds,
                showCheckbox = multiSelect,
                onToggleSelect = {
                    if (t.id in selectedIds) selectedIds.remove(t.id)
                    else selectedIds.add(t.id)
                }
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun TransactionRow(
    t: Transaction,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    selected: Boolean,
    showCheckbox: Boolean,
    onToggleSelect: () -> Unit
) {
    val isIncome = t.type == TransactionType.INCOME
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val visual = categoryVisual(t.category)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(visual.color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = visual.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = visual.color
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.category, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
            Spacer(Modifier.height(2.dp))
            val sub = if (t.note.isNullOrBlank()) t.category else t.note
            Text("${timeFmt.format(Date(t.date))} | $sub", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        val sign = if (isIncome) "+" else "-"
        val color = if (isIncome) Color(0xFF43A047) else Color(0xFFE53935)
        Text("$sign¥%.2f".format(t.amount), style = MiuixTheme.textStyles.body2, color = color)
        if (showCheckbox) {
            Spacer(Modifier.width(8.dp))
            Checkbox(state = if (selected) ToggleableState.On else ToggleableState.Off, onClick = onToggleSelect)
        }
    }
}


fun DefaultDp(): androidx.compose.ui.unit.Dp = 72.dp

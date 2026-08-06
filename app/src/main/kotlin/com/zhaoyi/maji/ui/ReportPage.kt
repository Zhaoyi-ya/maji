package com.zhaoyi.maji.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import com.zhaoyi.maji.data.Transaction
import com.zhaoyi.maji.data.TransactionType
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class ReportPeriod { WEEK, MONTH, YEAR }

@Composable
fun ReportPage(
    viewModel: MainViewModel,
    bottomPadding: androidx.compose.ui.unit.Dp = 72.dp,
    onSettingsClick: () -> Unit = {},
    enableBlur: Boolean = true,
    blurStyle: String = "default",
) {
    val transactions by viewModel.transactions.collectAsState()
    var period by remember { mutableStateOf(ReportPeriod.MONTH) }
    var offset by remember { mutableIntStateOf(0) }
    var showDatePicker by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(TransactionType.EXPENSE) }

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

    val range = remember(period, offset) { periodRange(period, offset) }
    val filtered = remember(transactions, range, mode) {
        transactions.filter {
            it.type == mode && it.date in range.start..range.endInclusive
        }
    }
    val prevRange = remember(period, offset) { periodRange(period, offset - 1) }
    val prevFiltered = remember(transactions, prevRange, mode) {
        transactions.filter { it.type == mode && it.date in prevRange.start..prevRange.endInclusive }
    }

    val total = filtered.sumOf { it.amount }
    val prevTotal = prevFiltered.sumOf { it.amount }
    val diff = total - prevTotal
    val days = maxOf(1, ((range.endInclusive - range.start) / (24 * 3600 * 1000) + 1).toInt())
    val avg = total / days

    val incomeTotal = transactions.filter { it.date in range.start..range.endInclusive && it.type == TransactionType.INCOME }.sumOf { it.amount }
    val expenseTotal = transactions.filter { it.date in range.start..range.endInclusive && it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    val balance = incomeTotal - expenseTotal

    val dateLabel = remember(range, period) { formatRangeLabel(period, range) }

    Scaffold(
        topBar = {
            BlurTopBar(backdrop, blurActive, useProgressive, hazeState) {
                CollapseTopBar(
                    title = "报表",
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
                    bottom = bottomPadding + WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 16.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    PeriodTabs(
                        selected = period,
                        onSelect = { period = it; offset = 0 }
                    )
                }

                item {
                    DateNavigator(
                        label = dateLabel,
                        onPrev = { offset-- },
                        onNext = { offset++ },
                        onPick = { showDatePicker = true }
                    )
                }

                item {
                    ModeToggle(
                        selected = mode,
                        onSelect = { mode = it }
                    )
                }

                item {
                    SummaryGrid(
                        total = total,
                        avg = avg,
                        diff = diff,
                        balance = balance,
                        mode = mode
                    )
                }

                item {
                    TrendCard(
                        period = period,
                        range = range,
                        transactions = filtered,
                        primaryColor = MiuixTheme.colorScheme.primary,
                        empty = filtered.isEmpty(),
                        mode = mode
                    )
                }

                item {
                    if (mode == TransactionType.EXPENSE) {
                        PeriodTrendCard(
                            title = "支出趋势",
                            period = period,
                            offset = offset,
                            transactions = transactions.filter { it.type == TransactionType.EXPENSE },
                            barColor = Color(0xFFE53935),
                            typeLabel = "支出"
                        )
                    } else {
                        PeriodTrendCard(
                            title = "收入趋势",
                            period = period,
                            offset = offset,
                            transactions = transactions.filter { it.type == TransactionType.INCOME },
                            barColor = Color(0xFF43A047),
                            typeLabel = "收入"
                        )
                    }
                }

                item {
                    CategoryCard(
                        transactions = filtered,
                        empty = filtered.isEmpty(),
                        primaryColor = MiuixTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    ReportDatePicker(
        show = showDatePicker,
        initial = Calendar.getInstance().apply { timeInMillis = range.start },
        onDismissRequest = { showDatePicker = false },
        onConfirm = { y, m, d ->
            val picked = Calendar.getInstance().apply {
                // clear() 后再 set，避免沿用"今天"的日号导致 lenient 模式静默跨月
                clear()
                set(y, m - 1, d.coerceIn(1, daysInMonth(y, m)))
            }
            offset = computeOffset(period, picked)
            showDatePicker = false
        }
    )
}

@Composable
private fun PeriodTabs(
    selected: ReportPeriod,
    onSelect: (ReportPeriod) -> Unit
) {
    val tabs = listOf("周报", "月报", "年报")
    val index = when (selected) {
        ReportPeriod.WEEK -> 0
        ReportPeriod.MONTH -> 1
        ReportPeriod.YEAR -> 2
    }
    TabRow(
        tabs = tabs,
        selectedTabIndex = index,
        onTabSelected = { idx ->
            onSelect(
                when (idx) {
                    0 -> ReportPeriod.WEEK
                    1 -> ReportPeriod.MONTH
                    else -> ReportPeriod.YEAR
                }
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DateNavigator(
    label: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable { onPrev() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = "上一个",
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.rotate(180f)
            )
        }
        Row(
            modifier = Modifier
                .clickable { onPick() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = 16.sp,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = "选择日期",
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(16.dp).rotate(90f)
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable { onNext() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = "下一个",
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

private fun Modifier.rotate(degrees: Float): Modifier {
    return this.then(Modifier.graphicsLayer(rotationZ = degrees))
}

@Composable
private fun ReportDatePicker(
    show: Boolean,
    initial: Calendar,
    onDismissRequest: () -> Unit,
    onConfirm: (year: Int, month: Int, day: Int) -> Unit
) {
    var pickYear by remember { mutableStateOf(initial.get(Calendar.YEAR)) }
    var pickMonth by remember { mutableStateOf(initial.get(Calendar.MONTH) + 1) }
    var pickDay by remember { mutableStateOf(initial.get(Calendar.DAY_OF_MONTH)) }
    var liveYear by remember { mutableStateOf(pickYear) }
    var liveMonth by remember { mutableStateOf(pickMonth) }
    var liveDay by remember { mutableStateOf(pickDay) }

    val now = Calendar.getInstance()
    val minYear = now.get(Calendar.YEAR) - 50
    val maxYear = now.get(Calendar.YEAR) + 1

    // 当前年月的真实天数（闰年规则由 GregorianCalendar 保证：能被4整除且非整百，或能被400整除）
    val maxDayOfMonth = remember(pickYear, pickMonth) { daysInMonth(pickYear, pickMonth) }

    LaunchedEffect(show) {
        if (show) {
            pickYear = initial.get(Calendar.YEAR)
            pickMonth = initial.get(Calendar.MONTH) + 1
            pickDay = initial.get(Calendar.DAY_OF_MONTH)
            liveYear = pickYear
            liveMonth = pickMonth
            liveDay = pickDay
        }
    }

    // 年/月变动导致当月天数缩短时（如 2月29 → 平年2月、31日 → 30天的月份），把日夹回合法范围
    LaunchedEffect(maxDayOfMonth) {
        if (pickDay > maxDayOfMonth) {
            pickDay = maxDayOfMonth
            liveDay = maxDayOfMonth
        }
    }

    val summaryText = remember(liveYear, liveMonth, liveDay) {
        // 滚动过程中三列是独立回调的，可能出现"2023年2月29日"这种中间态，这里统一夹紧后再显示
        val safeDay = liveDay.coerceIn(1, daysInMonth(liveYear, liveMonth))
        val cal = Calendar.getInstance().apply {
            clear()
            set(liveYear, liveMonth - 1, safeDay)
        }
        val wk = arrayOf("日", "一", "二", "三", "四", "五", "六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        "${liveYear}年${liveMonth}月${safeDay}日 周$wk"
    }

    WindowDialog(
        show = show,
        title = "选择日期",
        onDismissRequest = onDismissRequest,
        onDismissFinished = {}
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = summaryText,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveNumberPicker(
                        value = pickYear,
                        onValueChange = { pickYear = it },
                        onScrolling = { liveYear = it },
                        range = minYear..maxYear,
                        label = { "${it}年" },
                        textStyle = MiuixTheme.textStyles.body2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveNumberPicker(
                        value = pickMonth,
                        onValueChange = { pickMonth = it },
                        onScrolling = { liveMonth = it },
                        range = 1..12,
                        label = { "${it}月" },
                        textStyle = MiuixTheme.textStyles.body2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveNumberPicker(
                        // range 随年月动态变化，2月只显示到28/29，滚轮从根上滚不到不存在的日期
                        value = pickDay.coerceIn(1, maxDayOfMonth),
                        onValueChange = { pickDay = it },
                        onScrolling = { liveDay = it },
                        range = 1..maxDayOfMonth,
                        label = { "${it}日" },
                        textStyle = MiuixTheme.textStyles.body2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors()
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确定",
                    onClick = {
                        onConfirm(pickYear, pickMonth, pickDay.coerceIn(1, daysInMonth(pickYear, pickMonth)))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

private fun computeOffset(period: ReportPeriod, picked: Calendar): Int {
    val now = Calendar.getInstance().apply { setHourMinSec(0, 0, 0) }
    return when (period) {
        ReportPeriod.WEEK -> {
            val nowWS = now.weekStartMonday()
            val pickedWS = picked.weekStartMonday()
            ((pickedWS.timeInMillis - nowWS.timeInMillis) / (7L * 24 * 3600 * 1000)).toInt()
        }
        ReportPeriod.MONTH -> (picked.get(Calendar.YEAR) - now.get(Calendar.YEAR)) * 12 + (picked.get(Calendar.MONTH) - now.get(Calendar.MONTH))
        ReportPeriod.YEAR -> picked.get(Calendar.YEAR) - now.get(Calendar.YEAR)
    }
}

/**
 * 取该日期所在周的周一 0 点。
 *
 * 不能用 `set(Calendar.DAY_OF_WEEK, MONDAY)`：该 API 依赖 Calendar 内部字段的 stamp 状态，
 * 在 clear() 过、只设了年月日的 Calendar 上会被 DAY_OF_MONTH 覆盖而**完全不生效**（实测 8/4~8/9 原地不动）。
 * 改用纯天数回退，与 Calendar 构造方式无关，跨月/跨年/闰年边界均已实测通过。
 */
private fun Calendar.weekStartMonday(): Calendar {
    val c = clone() as Calendar
    c.setHourMinSec(0, 0, 0)
    // DAY_OF_WEEK: SUNDAY=1 … SATURDAY=7；距本周一的天数：周一=0、周二=1 … 周日=6
    val diff = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
    c.add(Calendar.DAY_OF_MONTH, -diff)
    return c
}

@Composable
private fun ModeToggle(
    selected: TransactionType,
    onSelect: (TransactionType) -> Unit
) {
    TabRow(
        tabs = listOf("支出", "收入"),
        selectedTabIndex = if (selected == TransactionType.EXPENSE) 0 else 1,
        onTabSelected = { idx ->
            onSelect(if (idx == 0) TransactionType.EXPENSE else TransactionType.INCOME)
        },
        height = 36.dp,
        contentAlignment = Alignment.Center,
    )
}

@Composable
private fun SummaryGrid(
    total: Double,
    avg: Double,
    diff: Double,
    balance: Double,
    mode: TransactionType
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryItem(
                    modifier = Modifier.weight(1f),
                    label = if (mode == TransactionType.EXPENSE) "本期支出（元）" else "本期收入（元）",
                    value = formatMoney(total),
                    valueColor = MiuixTheme.colorScheme.onSurface
                )
                SummaryItem(
                    modifier = Modifier.weight(1f),
                    label = "日均${if (mode == TransactionType.EXPENSE) "支出" else "收入"}（元）",
                    value = formatMoney(avg),
                    valueColor = MiuixTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryItem(
                    modifier = Modifier.weight(1f),
                    label = "比上期${if (mode == TransactionType.EXPENSE) "支出" else "收入"}（元）",
                    value = (if (diff >= 0) "+" else "") + formatMoney(diff),
                    valueColor = if (diff >= 0) Color(0xFFE53935) else Color(0xFF43A047)
                )
                SummaryItem(
                    modifier = Modifier.weight(1f),
                    label = "收支结余（元）",
                    value = formatMoney(balance),
                    valueColor = if (balance >= 0) Color(0xFF43A047) else Color(0xFFE53935)
                )
            }
        }
    }
}

@Composable
private fun SummaryItem(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 20.sp,
            color = valueColor
        )
    }
}

@Composable
private fun TrendCard(
    period: ReportPeriod,
    range: LongRange,
    transactions: List<Transaction>,
    primaryColor: Color,
    empty: Boolean,
    mode: TransactionType
) {
    val title = when (period) {
        ReportPeriod.WEEK -> "本周趋势"
        ReportPeriod.MONTH -> "本月趋势"
        ReportPeriod.YEAR -> "本年趋势"
    }
    val data = remember(period, range, transactions) {
        when (period) {
            ReportPeriod.WEEK -> weekBuckets(transactions, range)
            ReportPeriod.MONTH -> monthBuckets(transactions, range)
            ReportPeriod.YEAR -> yearBuckets(transactions, range)
        }
    }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.height(16.dp))
            if (empty) {
                EmptyHint()
            } else {
                TrendLineChart(data.values, data.axisLabels, data.pointLabels, primaryColor, mode)
            }
        }
    }
}

@Composable
private fun PeriodTrendCard(
    title: String,
    period: ReportPeriod,
    offset: Int,
    transactions: List<Transaction>,
    barColor: Color,
    typeLabel: String
) {
    val data = remember(period, offset, transactions) {
        multiPeriodBuckets(period, offset, transactions)
    }
    val empty = data.values.all { it == 0.0 }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.height(16.dp))
            if (empty) {
                EmptyHint()
            } else {
                BarChart(data.values, data.axisLabels, data.pointLabels, barColor, typeLabel)
            }
        }
    }
}

@Composable
private fun CategoryCard(
    transactions: List<Transaction>,
    empty: Boolean,
    primaryColor: Color
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "分类构成",
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Box(
                    modifier = Modifier
                        .background(MiuixTheme.colorScheme.primary.copy(0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "主分类",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (empty) {
                EmptyHint()
            } else {
                val groups = transactions.groupBy { it.category }
                    .map { (cat, list) -> CatSummary(cat, list.sumOf { it.amount }, list.size) }
                    .sortedByDescending { it.amount }
                val total = groups.sumOf { it.amount }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                        DonutChart(groups, total)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "总计", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(text = formatMoney(total), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                groups.forEachIndexed { index, cat ->
                    val visual = categoryVisual(cat.category)
                    CategoryRow(cat, total, visual, showCount = true, rank = index + 1)
                    if (index < groups.lastIndex) {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    cat: CatSummary,
    total: Double,
    visual: CategoryVisual,
    showCount: Boolean = false,
    rank: Int = 0
) {
    val pct = if (total > 0) cat.amount / total else 0.0
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (rank > 0) {
            Text(
                text = "$rank",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.width(20.dp),
                textAlign = TextAlign.Center
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(visual.color.copy(0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = visual.icon,
                contentDescription = null,
                tint = visual.color,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cat.category + if (showCount) " ${cat.count}笔" else "",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${(pct * 100).toInt()}%",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Text(
            text = formatMoney(cat.amount),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary.copy(0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = MiuixIcons.Send,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "暂无记录~",
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

private data class TrendData(val values: List<Double>, val axisLabels: List<String>, val pointLabels: List<String>)

private fun weekBuckets(transactions: List<Transaction>, range: LongRange): TrendData {
    val buckets = MutableList(7) { 0.0 }
    val dayMs = 24 * 3600 * 1000L
    transactions.forEach { t ->
        val day = ((t.date - range.start) / dayMs).toInt().coerceIn(0, 6)
        buckets[day] += t.amount
    }
    val fmt = SimpleDateFormat("MM.dd", Locale.getDefault())
    val labels = (0..6).map { i -> fmt.format(Date(range.start + i * dayMs)) }
    return TrendData(buckets, labels, labels)
}

private fun monthBuckets(transactions: List<Transaction>, range: LongRange): TrendData {
    val dayMs = 24 * 3600 * 1000L
    val days = ((range.endInclusive - range.start) / dayMs + 1).toInt()
    val buckets = MutableList(days) { 0.0 }
    transactions.forEach { t ->
        val day = ((t.date - range.start) / dayMs).toInt().coerceIn(0, days - 1)
        buckets[day] += t.amount
    }
    val fmt = SimpleDateFormat("MM.dd", Locale.getDefault())
    val pointLabels = (0 until days).map { i -> fmt.format(Date(range.start + i * dayMs)) }
    // 在首尾之间均匀取 6 个刻度。
    // 旧写法 `i % 5 == 0 || i == days - 1` 会在天数不是 31 时产生不规则的末尾间距：
    // 28 天(平年2月) 时 26日(idx25) 与 28日(idx27) 只隔 2 个点位，标签必定重叠；29/30 天也偏挤。
    // 均匀取点后最小间距恒为 5 个点位以上，任何月份都不会撞字。
    val axisLabels = MutableList(days) { "" }
    val tickCount = minOf(6, days)
    if (tickCount <= 1) {
        axisLabels[0] = pointLabels[0]
    } else {
        for (k in 0 until tickCount) {
            val idx = ((k.toDouble() * (days - 1)) / (tickCount - 1)).roundToInt().coerceIn(0, days - 1)
            axisLabels[idx] = pointLabels[idx]
        }
    }
    return TrendData(buckets, axisLabels, pointLabels)
}

private fun yearBuckets(transactions: List<Transaction>, range: LongRange): TrendData {
    val buckets = MutableList(12) { 0.0 }
    transactions.forEach { t ->
        val c = Calendar.getInstance().apply { timeInMillis = t.date }
        buckets[c.get(Calendar.MONTH)] += t.amount
    }
    val pointLabels = (0..11).map { m -> "${m + 1}月" }
    val axisLabels = (0..11).map { m -> if (m % 2 == 0) "${m + 1}月" else "" }
    return TrendData(buckets, axisLabels, pointLabels)
}

@Composable
private fun TrendLineChart(
    values: List<Double>,
    axisLabels: List<String>,
    pointLabels: List<String>,
    color: Color,
    mode: TransactionType
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val count = values.size
    LaunchedEffect(count) { selected = null }
    val axisW = 48.dp
    val plotTop = 16.dp
    val xLabelH = 24.dp
    val containerH = 200.dp
    val plotBottom = containerH - xLabelH
    val plotH = plotBottom - plotTop
    val padH = 12.dp

    val minVal = values.minOrNull() ?: 0.0
    val maxVal = values.maxOrNull() ?: 0.0
    val (yMin, yMax) = if (minVal == maxVal) {
        if (maxVal == 0.0) 0.0 to 1.0 else 0.0 to (maxVal * 1.2)
    } else {
        minVal to maxVal
    }
    val yRange = if (yMax > yMin) yMax - yMin else 1.0
    val yTicks = 5
    val yLabels = List(yTicks) { i -> formatMoney(yMax - i * (yRange / (yTicks - 1))) }
    val typeLabel = if (mode == TransactionType.INCOME) "收入" else "支出"

    Row(modifier = Modifier.fillMaxWidth().height(containerH)) {
        Column(
            modifier = Modifier
                .width(axisW)
                .fillMaxHeight()
                .padding(top = plotTop, bottom = xLabelH, end = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            yLabels.forEach { label ->
                Text(text = label, fontSize = 9.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(count) {
                    detectTapGestures { offset ->
                        if (count <= 1) return@detectTapGestures
                        val padHPx = with(density) { padH.toPx() }
                        val usable = size.width.toFloat() - 2 * padHPx
                        val step = usable / (count - 1)
                        val idx = (((offset.x - padHPx) / step).roundToInt()).coerceIn(0, count - 1)
                        selected = idx
                    }
                }
        ) {
            val padHPx = with(density) { padH.toPx() }
            val plotTopPx = with(density) { plotTop.toPx() }
            val plotBottomPx = with(density) { plotBottom.toPx() }
            val plotHPx = with(density) { plotH.toPx() }
            val plotWPx = with(density) { (maxWidth - padH * 2).toPx() }
            val stepPx = if (count > 1) plotWPx / (count - 1) else 0f
            val points = List(count) { i ->
                val x = padHPx + if (count > 1) i * stepPx else plotWPx / 2
                val v = values.getOrNull(i) ?: 0.0
                val y = plotTopPx + plotHPx - ((v - yMin) / yRange * plotHPx).toFloat()
                Offset(x, y)
            }
            val tickYs = List(yTicks) { i ->
                plotTopPx + (i * plotHPx / (yTicks - 1))
            }
            val gridColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                tickYs.forEach { y ->
                    drawLine(
                        color = gridColor,
                        start = Offset(padHPx, y),
                        end = Offset(padHPx + plotWPx, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                    )
                }
                for (i in 0 until points.lastIndex) {
                    drawLine(color, points[i], points[i + 1], strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                }
                points.forEachIndexed { i, p ->
                    if (selected == i) {
                        drawCircle(color.copy(alpha = 0.25f), radius = 11.dp.toPx(), center = p)
                        drawCircle(color, radius = 6.dp.toPx(), center = p)
                    }
                }
                selected?.let { idx ->
                    if (idx !in points.indices) return@let
                    val p = points[idx]
                    drawLine(color.copy(alpha = 0.35f), Offset(p.x, plotTopPx), Offset(p.x, plotBottomPx), strokeWidth = 1.dp.toPx())
                }
            }
            val slotW = if (count > 1) stepPx else plotWPx
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, with(density) { plotBottom.toPx().toInt() }) }
                    .fillMaxWidth()
                    .height(xLabelH)
            ) {
                axisLabels.forEachIndexed { i, label ->
                    if (label.isEmpty()) return@forEachIndexed
                    val xPx = (points[i].x - slotW / 2).toInt()
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(xPx, 0) }
                            .width(with(density) { slotW.toDp() }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 9.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true)
                        )
                    }
                }
            }
            var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
            selected?.let { idx ->
                if (idx !in values.indices) return@let
                val bubbleText = "${pointLabels[idx]}$typeLabel ¥${formatMoney(values[idx])}"
                val p = points[idx]
                val bwDp = with(density) { if (bubbleSize.width > 0) bubbleSize.width.toDp() else 96.dp }
                val bhDp = with(density) { if (bubbleSize.height > 0) bubbleSize.height.toDp() else 24.dp }
                val bx = with(density) { (p.x.toDp() - bwDp / 2).coerceIn(0.dp, maxWidth - bwDp) }
                val byDp = with(density) { p.y.toDp() }
                val above = (byDp - bhDp - 10.dp) >= plotTop
                val by = if (above) (byDp - bhDp - 10.dp) else (byDp + 14.dp)
                val byClamped = by.coerceIn(plotTop, containerH - bhDp)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(with(density) { bx.toPx().toInt() }, with(density) { byClamped.toPx().toInt() }) }
                        .background(color, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .onSizeChanged { bubbleSize = it },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = bubbleText,
                        color = Color.White,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun BarChart(
    values: List<Double>,
    axisLabels: List<String>,
    pointLabels: List<String>,
    color: Color,
    typeLabel: String
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val count = values.size
    LaunchedEffect(count) { selected = null }
    val axisW = 48.dp
    val plotTop = 16.dp
    val xLabelH = 24.dp
    val containerH = 200.dp
    val plotBottom = containerH - xLabelH
    val plotH = plotBottom - plotTop
    val padH = 12.dp
    val barRatio = 0.55f

    val minVal = values.minOrNull() ?: 0.0
    val maxVal = values.maxOrNull() ?: 0.0
    val (yMin, yMax) = if (minVal == maxVal) {
        if (maxVal == 0.0) 0.0 to 1.0 else 0.0 to (maxVal * 1.2)
    } else {
        0.0 to maxVal
    }
    val yRange = if (yMax > yMin) yMax - yMin else 1.0
    val yTicks = 5
    val yLabels = List(yTicks) { i -> formatMoney(yMax - i * (yRange / (yTicks - 1))) }

    Row(modifier = Modifier.fillMaxWidth().height(containerH)) {
        Column(
            modifier = Modifier
                .width(axisW)
                .fillMaxHeight()
                .padding(top = plotTop, bottom = xLabelH, end = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            yLabels.forEach { label ->
                Text(text = label, fontSize = 9.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(count) {
                    detectTapGestures { offset ->
                        if (count <= 0) return@detectTapGestures
                        val padHPx = with(density) { padH.toPx() }
                        val usable = size.width.toFloat() - 2 * padHPx
                        val step = usable / count
                        val idx = (((offset.x - padHPx) / step).toInt()).coerceIn(0, count - 1)
                        selected = idx
                    }
                }
        ) {
            val padHPx = with(density) { padH.toPx() }
            val plotTopPx = with(density) { plotTop.toPx() }
            val plotBottomPx = with(density) { plotBottom.toPx() }
            val plotHPx = with(density) { plotH.toPx() }
            val plotWPx = with(density) { (maxWidth - padH * 2).toPx() }
            val slotWPx = if (count > 0) plotWPx / count else plotWPx
            val barWPx = slotWPx * barRatio
            val corner = with(density) { 4.dp.toPx() }
            val tickYs = List(yTicks) { i ->
                plotTopPx + (i * plotHPx / (yTicks - 1))
            }
            val gridColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)

            Canvas(modifier = Modifier.fillMaxSize()) {
                tickYs.forEach { y ->
                    drawLine(
                        color = gridColor,
                        start = Offset(padHPx, y),
                        end = Offset(padHPx + plotWPx, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                    )
                }
                repeat(count) { i ->
                    val v = values.getOrNull(i) ?: 0.0
                    val h = if (yRange > 0) ((v - yMin) / yRange * plotHPx).toFloat() else 0f
                    val x = padHPx + i * slotWPx + (slotWPx - barWPx) / 2
                    val y = plotBottomPx - h
                    drawRoundRect(
                        color = if (selected == i) color else color.copy(alpha = 0.85f),
                        topLeft = Offset(x, y),
                        size = Size(barWPx, h.coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(corner, corner)
                    )
                }
                selected?.let { idx ->
                    if (idx !in 0 until count) return@let
                    val x = padHPx + idx * slotWPx + slotWPx / 2
                    drawLine(color.copy(alpha = 0.35f), Offset(x, plotTopPx), Offset(x, plotBottomPx), strokeWidth = 1.dp.toPx())
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, with(density) { plotBottom.toPx().toInt() }) }
                    .fillMaxWidth()
                    .height(xLabelH)
            ) {
                axisLabels.forEachIndexed { i, label ->
                    if (label.isEmpty()) return@forEachIndexed
                    val xPx = (padHPx + i * slotWPx).toInt()
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(xPx, 0) }
                            .width(with(density) { slotWPx.toDp() }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 9.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true)
                        )
                    }
                }
            }
            var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
            selected?.let { idx ->
                if (idx !in values.indices) return@let
                val bubbleText = "${pointLabels[idx]}$typeLabel ¥${formatMoney(values[idx])}"
                val xDp = with(density) { (padHPx + idx * slotWPx + slotWPx / 2).toDp() }
                val bwDp = with(density) { if (bubbleSize.width > 0) bubbleSize.width.toDp() else 96.dp }
                val bhDp = with(density) { if (bubbleSize.height > 0) bubbleSize.height.toDp() else 24.dp }
                val bx = (xDp - bwDp / 2).coerceIn(0.dp, maxWidth - bwDp)
                val by = plotTop
                Box(
                    modifier = Modifier
                        .offset { IntOffset(with(density) { bx.toPx().toInt() }, with(density) { by.toPx().toInt() }) }
                        .background(color, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .onSizeChanged { bubbleSize = it },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = bubbleText,
                        color = Color.White,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun DonutChart(groups: List<CatSummary>, total: Double) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2
        val stroke = 22.dp.toPx()
        var start = -90f
        groups.forEach { cat ->
            val sweep = if (total > 0) (cat.amount / total * 360).toFloat() else 0f
            val visual = categoryVisual(cat.category)
            drawArc(
                color = visual.color,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
                size = Size(radius * 2 - stroke, radius * 2 - stroke),
                topLeft = Offset(stroke / 2, stroke / 2)
            )
            start += sweep
        }
    }
}

private data class CatSummary(val category: String, val amount: Double, val count: Int)

private fun formatMoney(value: Double): String {
    return String.format(Locale.getDefault(), "%.2f", value)
}

private fun periodRange(period: ReportPeriod, offset: Int): LongRange {
    val cal = Calendar.getInstance()
    return when (period) {
        ReportPeriod.WEEK -> {
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.add(Calendar.WEEK_OF_YEAR, offset)
            val start = cal.apply { setHourMinSec(0, 0, 0) }.timeInMillis
            cal.add(Calendar.WEEK_OF_YEAR, 1)
            cal.add(Calendar.MILLISECOND, -1)
            start..cal.timeInMillis
        }
        ReportPeriod.MONTH -> {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, offset)
            val start = cal.apply { setHourMinSec(0, 0, 0) }.timeInMillis
            cal.add(Calendar.MONTH, 1)
            cal.add(Calendar.MILLISECOND, -1)
            start..cal.timeInMillis
        }
        ReportPeriod.YEAR -> {
            cal.set(Calendar.MONTH, Calendar.JANUARY)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.YEAR, offset)
            val start = cal.apply { setHourMinSec(0, 0, 0) }.timeInMillis
            cal.add(Calendar.YEAR, 1)
            cal.add(Calendar.MILLISECOND, -1)
            start..cal.timeInMillis
        }
    }
}

/**
 * 返回指定年月的真实天数（month 为 1..12）。
 * 依赖 GregorianCalendar 的 getActualMaximum，闰年规则完整：
 * 能被 4 整除且不能被 100 整除，或能被 400 整除 → 2月 29 天（如 2024、2000），否则 28 天（如 2023、1900）。
 * 必须先 clear()，否则 Calendar.getInstance() 残留的"今天"字段会污染计算
 * （例：今天 31 号时 set(y, 1, 1) 在 lenient 模式下可能把月份顶到 3 月）。
 */
private fun daysInMonth(year: Int, month: Int): Int =
    Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)

private fun Calendar.setHourMinSec(h: Int, m: Int, s: Int) {
    set(Calendar.HOUR_OF_DAY, h)
    set(Calendar.MINUTE, m)
    set(Calendar.SECOND, s)
    set(Calendar.MILLISECOND, 0)
}

private fun formatRangeLabel(period: ReportPeriod, range: LongRange): String {
    val fmt = when (period) {
        ReportPeriod.WEEK -> SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
        ReportPeriod.MONTH -> SimpleDateFormat("yyyy年M月", Locale.getDefault())
        ReportPeriod.YEAR -> SimpleDateFormat("yyyy年", Locale.getDefault())
    }
    return when (period) {
        ReportPeriod.WEEK -> {
            val end = Calendar.getInstance().apply { timeInMillis = range.endInclusive }
            fmt.format(Date(range.start)) + "~" + fmt.format(Date(end.timeInMillis))
        }
        else -> fmt.format(Date(range.start))
    }
}

/**
 * 生成最近 6 个连续周期的趋势数据。最右侧为当前选中的周期（offset），
 * 左侧依次往前推 5 个周期。X 轴标签统一显示周数/月份/年份，
 * 因此第 6 个柱子随时间选择器所选周期而变化。
 */
private fun multiPeriodBuckets(
    period: ReportPeriod,
    offset: Int,
    transactions: List<Transaction>
): TrendData {
    val count = 6
    val values = MutableList(count) { 0.0 }
    val axisLabels = MutableList(count) { "" }
    val pointLabels = MutableList(count) { "" }

    for (i in 0 until count) {
        val currentOffset = offset - (count - 1 - i)
        val range = periodRange(period, currentOffset)
        values[i] = transactions.filter { it.date in range.start..range.endInclusive }.sumOf { it.amount }

        val cal = Calendar.getInstance().apply { timeInMillis = range.start }
        axisLabels[i] = when (period) {
            ReportPeriod.WEEK -> "${cal.get(Calendar.WEEK_OF_YEAR)}周"
            ReportPeriod.MONTH -> String.format("%02d月", cal.get(Calendar.MONTH) + 1)
            ReportPeriod.YEAR -> "${cal.get(Calendar.YEAR)}年"
        }
        pointLabels[i] = when (period) {
            ReportPeriod.WEEK -> {
                val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
                val end = Calendar.getInstance().apply { timeInMillis = range.endInclusive }
                fmt.format(Date(range.start)) + "~" + fmt.format(Date(end.timeInMillis))
            }
            ReportPeriod.MONTH -> SimpleDateFormat("yyyy年M月", Locale.getDefault()).format(Date(range.start))
            ReportPeriod.YEAR -> SimpleDateFormat("yyyy年", Locale.getDefault()).format(Date(range.start))
        }
    }
    return TrendData(values, axisLabels, pointLabels)
}

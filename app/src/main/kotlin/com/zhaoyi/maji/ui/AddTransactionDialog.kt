package com.zhaoyi.maji.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhaoyi.maji.data.Categories
import com.zhaoyi.maji.data.Transaction
import com.zhaoyi.maji.data.TransactionType
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun categoriesFor(type: TransactionType) =
    if (type == TransactionType.EXPENSE) Categories.EXPENSE else Categories.INCOME

private const val DATE_PICKER_DAY_RANGE = 20000 // about ±55 years

private fun Calendar.setToStartOfDay(): Calendar {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    return this
}

private fun Calendar.atStartOfDay(): Calendar = (clone() as Calendar).setToStartOfDay()

private fun dayOffsetBetween(base: Calendar, target: Calendar): Int {
    val msPerDay = 24L * 60 * 60 * 1000
    return ((target.atStartOfDay().timeInMillis - base.atStartOfDay().timeInMillis) / msPerDay).toInt()
}

@Composable
fun AddTransactionDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onAdd: (Transaction) -> Unit,
    editing: Transaction? = null,
    onDismissFinished: (() -> Unit)? = null
) {
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    val initCal = Calendar.getInstance().apply { timeInMillis = date }
    var amountText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }
    var categoryIndex by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    val imagePaths = remember { mutableStateListOf<String>() }
    val activeCategories = categoriesFor(type)

    var showDatePicker by remember { mutableStateOf(false) }
    var dateOffset by remember { mutableStateOf(0) }
    var pickHour by remember { mutableStateOf(initCal.get(Calendar.HOUR_OF_DAY)) }
    var pickMinute by remember { mutableStateOf(initCal.get(Calendar.MINUTE)) }
    // 实时跟随滚轮的状态（拖动/惯性过程中即更新，停稳后由 onValueChange 同步到上面的提交值）
    var liveDateOffset by remember { mutableStateOf(0) }
    var liveHour by remember { mutableStateOf(initCal.get(Calendar.HOUR_OF_DAY)) }
    var liveMinute by remember { mutableStateOf(initCal.get(Calendar.MINUTE)) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val dateDisplayFormat = remember { SimpleDateFormat("M月d日", Locale.getDefault()) }
    val summaryFormat = remember { SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()) }

    LaunchedEffect(showDatePicker) {
        if (showDatePicker) {
            val today = Calendar.getInstance().setToStartOfDay()
            val target = Calendar.getInstance().apply { timeInMillis = date }
            dateOffset = dayOffsetBetween(today, target)
            pickHour = target.get(Calendar.HOUR_OF_DAY)
            pickMinute = target.get(Calendar.MINUTE)
            liveDateOffset = dateOffset
            liveHour = pickHour
            liveMinute = pickMinute
        }
    }

    LaunchedEffect(show, editing) {
        if (show) {
            if (editing != null) {
                amountText = if (editing.amount % 1.0 == 0.0) editing.amount.toInt().toString() else editing.amount.toString()
                type = editing.type
                val cats = categoriesFor(type)
                categoryIndex = cats.indexOf(editing.category).let { if (it < 0) cats.indexOf("其他").coerceAtLeast(0) else it }
                note = editing.note ?: ""
                date = editing.date
                imagePaths.clear()
                editing.imagePath?.let { imagePaths.add(it) }
            } else {
                amountText = ""
                type = TransactionType.EXPENSE
                categoryIndex = 0
                note = ""
                date = System.currentTimeMillis()
                imagePaths.clear()
            }
        }
    }

    WindowBottomSheet(
        show = show,
        title = if (editing != null) "编辑记录" else "记一笔",
        startAction = {
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(MiuixIcons.Light.Close, "关闭", tint = MiuixTheme.colorScheme.onBackground)
            }
        },
        endAction = {
            IconButton(onClick = {
                val amt = amountText.toDoubleOrNull()
                if (amt == null || amt <= 0) return@IconButton
                val t = if (editing != null) {
                    editing.copy(
                        amount = amt,
                        type = type,
                        category = activeCategories[categoryIndex],
                        note = note.takeIf { it.isNotBlank() },
                        imagePath = imagePaths.firstOrNull(),
                        date = date
                    )
                } else {
                    Transaction(
                        amount = amt,
                        type = type,
                        category = activeCategories[categoryIndex],
                        note = note.takeIf { it.isNotBlank() },
                        imagePath = imagePaths.firstOrNull(),
                        date = date
                    )
                }
                onAdd(t)
                onDismiss()
            }) { Icon(MiuixIcons.Light.Ok, "保存", tint = MiuixTheme.colorScheme.onBackground) }
        },
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerTypeChip(selected = type == TransactionType.EXPENSE, label = "支出", modifier = Modifier.weight(1f)) { type = TransactionType.EXPENSE; categoryIndex = 0 }
                    LedgerTypeChip(selected = type == TransactionType.INCOME, label = "收入", modifier = Modifier.weight(1f)) { type = TransactionType.INCOME; categoryIndex = 0 }
                }
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = "金额",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Card {
                    IconValueSpinnerPreference(
                        title = "分类",
                        items = activeCategories.map { cat ->
                            DropdownItem(
                                text = cat,
                                icon = { modifier ->
                                    Icon(
                                        imageVector = categoryVisual(cat).icon,
                                        contentDescription = null,
                                        modifier = modifier,
                                        tint = categoryVisual(cat).color,
                                    )
                                },
                            )
                        },
                        selectedIndex = categoryIndex,
                        onSelectedIndexChange = { categoryIndex = it },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Card(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("日期", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(dateFormat.format(Date(date)), style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(10.dp))
                TextField(value = note, onValueChange = { note = it }, label = "备注（选填）", singleLine = false, modifier = Modifier.fillMaxWidth())
                if (imagePaths.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("已附 ${imagePaths.size} 张截图（后续可用于识别）", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    val summaryText = remember(liveDateOffset, liveHour, liveMinute) {
        val cal = Calendar.getInstance().setToStartOfDay().apply { add(Calendar.DAY_OF_MONTH, liveDateOffset) }
        cal.set(Calendar.HOUR_OF_DAY, liveHour)
        cal.set(Calendar.MINUTE, liveMinute)
        summaryFormat.format(cal.time)
    }

    WindowDialog(
        show = showDatePicker,
        title = "选择时间",
        onDismissRequest = { showDatePicker = false },
        onDismissFinished = {}
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 标题下方的总览直接放在 content 里，随滚轮实时刷新（WindowDialog 的 summary 参数只在弹窗打开时快照一次）
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
                Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveNumberPicker(
                        value = dateOffset,
                        onValueChange = { dateOffset = it },
                        onScrolling = { liveDateOffset = it },
                        range = -DATE_PICKER_DAY_RANGE..DATE_PICKER_DAY_RANGE,
                        label = { offset ->
                            val cal = Calendar.getInstance().setToStartOfDay().apply { add(Calendar.DAY_OF_MONTH, offset) }
                            dateDisplayFormat.format(cal.time)
                        },
                        textStyle = MiuixTheme.textStyles.body2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveNumberPicker(value = pickHour, onValueChange = { pickHour = it }, onScrolling = { liveHour = it }, range = 0..23, textStyle = MiuixTheme.textStyles.body2, modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveNumberPicker(value = pickMinute, onValueChange = { pickMinute = it }, onScrolling = { liveMinute = it }, range = 0..59, textStyle = MiuixTheme.textStyles.body2, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showDatePicker = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors()
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确定",
                    onClick = {
                        val c = Calendar.getInstance().setToStartOfDay()
                        c.add(Calendar.DAY_OF_MONTH, dateOffset)
                        c.set(Calendar.HOUR_OF_DAY, pickHour)
                        c.set(Calendar.MINUTE, pickMinute)
                        c.set(Calendar.SECOND, 0)
                        c.set(Calendar.MILLISECOND, 0)
                        date = c.timeInMillis
                        showDatePicker = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
private fun LedgerTypeChip(selected: Boolean, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer
    val fg = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).clickable { onClick() }.padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MiuixTheme.textStyles.body2, color = fg)
    }
}

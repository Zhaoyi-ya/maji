package com.zhaoyi.maji.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhaoyi.maji.data.PickupCode
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*

@Composable
fun AddPickupCodeDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onSave: (PickupCode) -> Unit,
    editing: PickupCode? = null,
    onDelete: (PickupCode) -> Unit = {},
    onDismissFinished: (() -> Unit)? = null,
) {
    var code by remember { mutableStateOf(editing?.code ?: "") }
    var merchant by remember { mutableStateOf(editing?.merchant ?: "") }
    var item by remember { mutableStateOf(editing?.item ?: "") }
    var itemDetail by remember { mutableStateOf(editing?.itemDetail ?: "") }
    var price by remember { mutableStateOf(editing?.price ?: "") }

    LaunchedEffect(show, editing) {
        if (show) {
            code = editing?.code ?: ""
            merchant = editing?.merchant ?: ""
            item = editing?.item ?: ""
            itemDetail = editing?.itemDetail ?: ""
            price = editing?.price ?: ""
        }
    }

    WindowBottomSheet(
        show = show,
        title = if (editing != null) "编辑取件码" else "添加取件码",
        startAction = {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(40.dp)
            ) { Icon(MiuixIcons.Light.Close, "关闭") }
        },
        endAction = {
            IconButton(
                onClick = {
                    val updated = editing?.copy(
                        code = code,
                        merchant = merchant,
                        item = item,
                        itemDetail = itemDetail,
                        price = price,
                        note = ""
                    ) ?: PickupCode(
                        code = code,
                        merchant = merchant,
                        item = item,
                        itemDetail = itemDetail,
                        price = price,
                    )
                    onSave(updated)
                    onDismiss()
                },
                modifier = Modifier.size(40.dp)
            ) { Icon(MiuixIcons.Light.Ok, "保存") }
        },
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                TextField(
                    value = code,
                    onValueChange = { code = it },
                    label = "取件码（如 K555）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = "商家",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = item,
                    onValueChange = { item = it },
                    label = "商品",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = itemDetail,
                    onValueChange = { itemDetail = it },
                    label = "地址（选填）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = price,
                    onValueChange = { price = it },
                    label = "价格（选填，如 ¥29.90）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (editing != null) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        text = "删除",
                        onClick = { onDelete(editing); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error)
                    )
                }
            }
        }
    }
}

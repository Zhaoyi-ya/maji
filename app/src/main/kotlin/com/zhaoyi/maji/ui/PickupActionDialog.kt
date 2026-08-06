package com.zhaoyi.maji.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhaoyi.maji.data.PickupCode
import com.zhaoyi.maji.island.IslandController
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 取件码单击后弹出的操作面板（居中卡片式 Dialog，对应 miuix 示例的 Wide Dialog）。
 *
 * 选项随真实状态变化：
 *  - 上岛 / 下岛：按 [IslandController.isOnIslandActive] 显示——通知确在被划掉时显示「上岛」
 *    （而非 DB 标记的「已上岛」），避免点了却因系统屏蔽同 id 重发而看似无反应；已取件时禁用。
 *  - 已取 / 未取：按 [PickupCode.isDone] 切换文案。
 *  - 编辑：打开取件码编辑面板。
 *  - 取消：关闭。
 */
@Composable
fun PickupActionDialog(
    show: Boolean,
    code: PickupCode?,
    onDismiss: () -> Unit,
    onPinToggle: (PickupCode) -> Unit,
    onToggleDone: (PickupCode) -> Unit,
    onEdit: (PickupCode) -> Unit,
) {
    val ctx = LocalContext.current
    // 仅在打开那一刻按真实通知状态判定一次，避免在对话框存活期间反复查询
    val onIsland = remember(show, code) {
        code?.let { IslandController.isOnIslandActive(ctx, it) } ?: false
    }
    val done = code?.isDone == true

    WindowDialog(
        show = show,
        title = code?.code ?: "",
        summary = code?.item?.takeIf { it.isNotBlank() } ?: "选择操作",
        onDismissRequest = onDismiss,
        onDismissFinished = {},
    ) {
        val dismiss = LocalDismissState.current
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = if (onIsland) "下岛" else "上岛",
                onClick = {
                    code?.let(onPinToggle)
                    dismiss?.invoke()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !done,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(
                text = if (done) "标记未取" else "标记已取",
                onClick = {
                    code?.let(onToggleDone)
                    dismiss?.invoke()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = "编辑",
                onClick = {
                    code?.let(onEdit)
                    dismiss?.invoke()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = "取消",
                onClick = { dismiss?.invoke() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

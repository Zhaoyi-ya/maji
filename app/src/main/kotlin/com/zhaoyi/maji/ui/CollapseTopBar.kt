package com.zhaoyi.maji.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 可折叠顶栏（对齐 Miuix 原生折叠行为）：
 * - 顶部未滚动时显示大标题（largeTitle），顶栏高；
 * - 向上滚动时大标题渐隐、收起为居中小标题，顶栏缩为常规高度。
 *
 * 折叠完全由 Miuix 的 scrollBehavior 驱动：页面的滚动列表需通过
 * nestedScroll(scrollBehavior.nestedScrollConnection) 绑定同一个 scrollBehavior 实例，
 * 大标题/小标题的显隐与栏高伸缩由库在 layout/draw 阶段自动完成，无需手动传进度。
 *
 * @param title          页面标题（同时作为大标题与折叠后的小标题）。
 * @param scrollBehavior 页面的 MiuixScrollBehavior，需与滚动列表共用同一实例。
 * @param navigationIcon 可选左侧图标（如返回）。默认无。
 * @param actions        可选右侧操作图标。
 */
@Composable
fun CollapseTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    barColor: Color = MiuixTheme.colorScheme.surface,
) {
    TopAppBar(
        title = title,
        largeTitle = title,
        color = barColor,
        scrollBehavior = scrollBehavior,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
    )
}

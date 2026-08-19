package com.zhaoyi.maji.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import com.zhaoyi.maji.ui.liquidglass.FloatingBottomBar
import com.zhaoyi.maji.ui.liquidglass.FloatingBottomBarItem
import com.zhaoyi.maji.ui.liquidglass.FloatingBarPosition
import com.zhaoyi.maji.ui.liquidglass.LiquidGlassFab
import com.zhaoyi.maji.ui.liquidglass.alignment
import com.zhaoyi.maji.ui.liquidglass.floatingBarPositionFromKey
import com.zhaoyi.maji.ui.ReportPage
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhaoyi.maji.Prefs
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.Backdrop
import androidx.compose.ui.graphics.RectangleShape
import androidx.activity.compose.BackHandler
import com.zhaoyi.maji.AppLaunchRequests
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 页面路由：复用 Miuix 示例 app 的 navigation3 模式，关于页做成独立的 Scene，
// 由 NavDisplay 负责带层级（背景页退 1/4 + 变暗）的推入/退出动画。
private sealed interface ScreenRoute : NavKey
private data object ScreenMain : ScreenRoute
private data object ScreenSettings : ScreenRoute
private data object ScreenAbout : ScreenRoute
private data object ScreenLicense : ScreenRoute
private data object ScreenPermissionCheck : ScreenRoute
private data object ScreenBackupSettings : ScreenRoute
private data object ScreenNotifySettings : ScreenRoute

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    initialTab: Int = 0,
    onThemeChange: (String) -> Unit = {},
    onAccentColorChange: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var showAddTransaction by remember { mutableStateOf(false) }
    var showAddPickup by remember { mutableStateOf(false) }
    // 点击「记一笔」通知后，需要打开的账单 id（交给 LedgerPage 的 LaunchedEffect 去弹编辑面板）
    var pendingEditTxnId by remember { mutableStateOf<String?>(null) }
    val prefs = remember { Prefs.get(context, Prefs.Category.GENERAL) }
    var navStyle by remember { mutableStateOf(prefs.getString("nav_style", "default") ?: "default") }
    var bottomBarPosition by remember { mutableStateOf(prefs.getString("bottom_bar_position", "center") ?: "center") }
    val barPosition = floatingBarPositionFromKey(bottomBarPosition)
    var enableBlur by remember { mutableStateOf(prefs.getBoolean("enable_blur", true)) }
    val onEnableBlurChange: (Boolean) -> Unit = { v ->
        enableBlur = v
        prefs.edit().putBoolean("enable_blur", v).apply()
    }
    var blurStyle by remember { mutableStateOf(prefs.getString("blur_style", "default") ?: "default") }
    val onBlurStyleChange: (String) -> Unit = { v ->
        blurStyle = v
        prefs.edit().putString("blur_style", v).apply()
    }
    var islandMode by remember { mutableStateOf(prefs.getString("island_mode", "xiaomi") ?: "xiaomi") }
    val onIslandModeChange: (String) -> Unit = { v ->
        islandMode = v
        prefs.edit().putString("island_mode", v).apply()
    }
    val homeBottomPadding = if (navStyle == "floating" || navStyle == "ios") 100.dp else 72.dp
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<Job?>(null) }
    var navSeq by remember { mutableIntStateOf(0) }

    // 选中态跟随 pager：手动滑动时实时跟随 currentPage（高亮跟手）；
    // 程序化翻页（switchTo）期间用 isNavigating 锁住，避免 pager 经过中间页时把选中态带偏，
    // 进而触发底栏 onSelected 把滚动重定向到中间页（表现为「一次只换一页、还经过中间页」）。
    // 与参考 app KernelSU-Style-UI-Kit 的 MainPagerState(selectedPage + isNavigating + syncPage) 一致。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (!isNavigating) selectedTab = page
        }
    }

    /**
     * 统一切换页面（对应参考 app 的 MainPagerState.animateToPage）：
     * 先在同步点把选中目标设为 index（稳定，不随 pager 滚动经过中间页），再滚过去。
     * 这样底栏的 selectedIndex 直接命中最终页，onSelected 不会被中间页触发，点击可直达目标页。
     */
    fun switchTo(index: Int) {
        if (index == selectedTab) return
        navJob?.cancel()
        selectedTab = index
        isNavigating = true
        val seq = ++navSeq
        navJob = scope.launch {
            try {
                pagerState.animateScrollToPage(index)
            } finally {
                if (seq == navSeq) {
                    isNavigating = false
                    selectedTab = pagerState.currentPage
                }
            }
        }
    }

    val genPrefs = remember { Prefs.get(context, Prefs.Category.GENERAL) }

    // ---- 通知点击：取件码 / 取餐码通知 → 切换到取件码页面（tab） ----
    LaunchedEffect(Unit) {
        AppLaunchRequests.requests.collect { req ->
                when (req) {
                    is AppLaunchRequests.OpenPickup -> switchTo(2)
                    is AppLaunchRequests.OpenEditTxn -> {
                        // 切到记账页并打开对应账单的编辑面板
                        switchTo(0)
                        pendingEditTxnId = req.txnId
                    }
                    is AppLaunchRequests.OpenReport -> switchTo(1)
                    is AppLaunchRequests.OpenNewTxn -> {
                        // 桌面小组件「+」→ 直接弹出「记一笔」新建面板
                        showAddTransaction = true
                    }
                }
        }
    }

    // ---- 多选状态（记账 / 取件码） ----
    var ledgerMultiSelect by remember { mutableStateOf(false) }
    var ledgerSelected = remember { mutableStateListOf<String>() }
    var pickupMultiSelect by remember { mutableStateOf(false) }
    var pickupSelected = remember { mutableStateListOf<String>() }
    val pickupCodes by viewModel.pickupCodes.collectAsState()

    // ---- 页面导航栈：直接用 Miuix 的 NavDisplay，关于页作为独立 Scene ----
    val backStack = remember { mutableStateListOf<ScreenRoute>(ScreenMain) }
    val popAbout: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val popSettings: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val popLicense: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val popPermissionCheck: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val popBackupSettings: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val popNotifySettings: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    NavDisplay(
        backStack = backStack,
        onBack = {
            when {
                ledgerMultiSelect -> { ledgerMultiSelect = false; ledgerSelected.clear() }
                pickupMultiSelect -> { pickupMultiSelect = false; pickupSelected.clear() }
                backStack.size > 1 && backStack.last() is ScreenSettings -> popSettings()
                backStack.size > 1 && backStack.last() is ScreenAbout -> popAbout()
                backStack.size > 1 && backStack.last() is ScreenLicense -> popLicense()
                backStack.size > 1 && backStack.last() is ScreenPermissionCheck -> popPermissionCheck()
                backStack.size > 1 && backStack.last() is ScreenBackupSettings -> popBackupSettings()
                backStack.size > 1 && backStack.last() is ScreenNotifySettings -> popNotifySettings()
                else -> (context as? android.app.Activity)?.finish()
            }
        },
        transitionEffects = NavDisplayTransitionEffects.Default,
    ) { route ->
        when (route) {
            ScreenMain -> NavEntry(route) {
                val surfaceColor = MiuixTheme.colorScheme.surface
                val homeBackdrop = rememberLayerBackdrop {
                    drawRect(surfaceColor)
                    drawContent()
                }
                val blurActive = enableBlur && isRuntimeShaderSupported()
                Scaffold(
                    bottomBar = {
                        AnimatedContent(
                            targetState = if (ledgerMultiSelect) "ledger"
                            else if (pickupMultiSelect) "pickup" else "tabs",
                            transitionSpec = {
                                fadeIn(tween(280)) + slideInVertically(initialOffsetY = { it }) togetherWith
                                    fadeOut(tween(280)) + slideOutVertically(targetOffsetY = { it })
                            }
                        ) { st ->
                            when (st) {
                                "ledger" -> ActionBar(
                                    navStyle = navStyle,
                                    homeBackdrop = homeBackdrop,
                                    blurActive = blurActive,
                                    position = barPosition,
                                    items = listOf(
                                        ActionSpec("取消", MiuixIcons.Close, { ledgerMultiSelect = false; ledgerSelected.clear() }),
                                        ActionSpec(
                                            "删除(${ledgerSelected.size})",
                                            MiuixIcons.Delete,
                                            {
                                                viewModel.deleteTransactions(ledgerSelected.toList())
                                                ledgerMultiSelect = false; ledgerSelected.clear()
                                            },
                                            enabled = ledgerSelected.isNotEmpty()
                                        )
                                    )
                                )
                                "pickup" -> ActionBar(
                                    navStyle = navStyle,
                                    homeBackdrop = homeBackdrop,
                                    blurActive = blurActive,
                                    position = barPosition,
                                    items = listOf(
                                        ActionSpec("取消", MiuixIcons.Close, { pickupMultiSelect = false; pickupSelected.clear() }),
                                        ActionSpec(
                                            "全选",
                                            MiuixIcons.SelectAll,
                                            {
                                                pickupSelected.clear()
                                                pickupSelected.addAll(pickupCodes.map { it.id })
                                            },
                                        ),
                                        ActionSpec(
                                            "删除",
                                            MiuixIcons.Delete,
                                            {
                                                viewModel.deletePickupCodes(pickupSelected.toList())
                                                pickupMultiSelect = false; pickupSelected.clear()
                                            },
                                            enabled = pickupSelected.isNotEmpty()
                                        )
                                    )
                                )
                                else -> {
                                    when (navStyle) {
                                        "floating" -> {
                                        val floatingBarColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
                                        val floatingBarShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
                                        val isDark = isSystemInDarkTheme()
                                        val floatingHighlight = remember(isDark) {
                                            if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
                                        }
                                        FloatingNavigationBar(
                                            modifier = if (blurActive) {
                                                Modifier.textureBlur(
                                                    backdrop = homeBackdrop,
                                                    shape = floatingBarShape,
                                                    blurRadius = 25f,
                                                    colors = BlurDefaults.blurColors(
                                                        blendColors = listOf(
                                                            BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.6f))
                                                        )
                                                    ),
                                                    highlight = floatingHighlight,
                                                )
                                            } else {
                                                Modifier
                                            },
                                            color = floatingBarColor,
                                            horizontalAlignment = barPosition.alignment,
                                        ) {
                                            FloatingNavigationBarItem(
                                                selected = selectedTab == 0,
                                                onClick = { switchTo(0) },
                                                icon = MiuixIcons.BankCards, label = "记账"
                                            )
                                            FloatingNavigationBarItem(
                                                selected = selectedTab == 1,
                                                onClick = { switchTo(1) },
                                                icon = MiuixIcons.Backup, label = "报表"
                                            )
                                            FloatingNavigationBarItem(
                                                selected = selectedTab == 2,
                                                onClick = { switchTo(2) },
                                                icon = MiuixIcons.Notes, label = "取件码"
                                            )
                                        }
                                        }
                                        "ios" -> {
                                            val iosBottom = Modifier.padding(
                                                bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues()
                                                    .calculateBottomPadding()
                                            )
                                            FloatingBottomBar(
                                                modifier = iosBottom,
                                                position = barPosition,
                                                selectedIndex = { selectedTab },
                                                onSelected = { index -> switchTo(index) },
                                                backdrop = homeBackdrop,
                                                tabsCount = 3,
                                                isBlurEnabled = blurActive,
                                            ) {
                                                FloatingBottomBarItem(
                                                    onClick = { switchTo(0) },
                                                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = MiuixIcons.BankCards,
                                                        contentDescription = null,
                                                        tint = MiuixTheme.colorScheme.onSurface
                                                    )
                                                Text(
                                                    text = "记账",
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    color = MiuixTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Visible
                                                )
                                            }
                                                FloatingBottomBarItem(
                                                    onClick = { switchTo(1) },
                                                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = MiuixIcons.Backup,
                                                        contentDescription = null,
                                                        tint = MiuixTheme.colorScheme.onSurface
                                                    )
                                                Text(
                                                    text = "报表",
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    color = MiuixTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Visible
                                                )
                                            }
                                                FloatingBottomBarItem(
                                                    onClick = { switchTo(2) },
                                                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = MiuixIcons.Notes,
                                                        contentDescription = null,
                                                        tint = MiuixTheme.colorScheme.onSurface
                                                    )
                                                Text(
                                                    text = "取件码",
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    color = MiuixTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Visible
                                                )
                                            }
                                        }
                                        }
                                        else -> {
                                        val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
                                        Box(
                                            modifier = Modifier
                                                .then(
                                                    if (blurActive) {
                                                        Modifier.textureBlur(
                                                            backdrop = homeBackdrop,
                                                            shape = RectangleShape,
                                                            blurRadius = 25f,
                                                            colors = BlurDefaults.blurColors(
                                                                blendColors = listOf(
                                                                    BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f))
                                                                )
                                                            )
                                                        )
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                                .background(barColor)
                                        ) {
                                            NavigationBar(color = barColor) {
                                                NavigationBarItem(
                                                selected = selectedTab == 0,
                                                onClick = { switchTo(0) },
                                                icon = MiuixIcons.BankCards, label = "记账"
                                            )
                                                NavigationBarItem(
                                                selected = selectedTab == 1,
                                                onClick = { switchTo(1) },
                                                icon = MiuixIcons.Backup, label = "报表"
                                            )
                                                NavigationBarItem(
                                                selected = selectedTab == 2,
                                                onClick = { switchTo(2) },
                                                icon = MiuixIcons.Notes, label = "取件码"
                                            )
                                            }
                                        }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    floatingActionButton = {
                        val isIos = navStyle == "ios"
                        @Composable
                        fun AddFab(onClick: () -> Unit) {
                            if (isIos) {
                                LiquidGlassFab(
                                    backdrop = homeBackdrop,
                                    isBlurEnabled = blurActive,
                                    onClick = onClick,
                                ) { Icon(MiuixIcons.Add, contentDescription = null, tint = Color.White) }
                            } else {
                                FloatingActionButton(onClick = onClick) {
                                    Icon(MiuixIcons.Add, contentDescription = null, tint = Color.White)
                                }
                            }
                        }
                        if (selectedTab == 0 && !ledgerMultiSelect) {
                            AddFab { showAddTransaction = true }
                        } else if (selectedTab == 2 && !pickupMultiSelect) {
                            AddFab { showAddPickup = true }
                        }
                    }
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().layerBackdrop(homeBackdrop)) {
                    // 多选状态下，安卓返回键先退出多选，而不是直接退出 App
                    BackHandler(enabled = ledgerMultiSelect || pickupMultiSelect) {
                        ledgerMultiSelect = false; ledgerSelected.clear()
                        pickupMultiSelect = false; pickupSelected.clear()
                    }
                    HorizontalPager(state = pagerState) { page ->
                        when (page) {
                            0 -> LedgerPage(
                                viewModel = viewModel,
                                bottomPadding = homeBottomPadding,
                                onSettingsClick = { backStack.add(ScreenSettings) },
                                multiSelect = ledgerMultiSelect,
                                selectedIds = ledgerSelected,
                                onEnterMultiSelect = {
                                    ledgerMultiSelect = true
                                    ledgerSelected.add(it)
                                },
                                onExitMultiSelect = {
                                    ledgerMultiSelect = false
                                    ledgerSelected.clear()
                                },
                                pendingEditTxnId = pendingEditTxnId,
                                onConsumePendingEdit = { pendingEditTxnId = null },
                                enableBlur = enableBlur,
                                blurStyle = blurStyle
                            )
                            1 -> ReportPage(
                                viewModel = viewModel,
                                bottomPadding = homeBottomPadding,
                                onSettingsClick = { backStack.add(ScreenSettings) },
                                enableBlur = enableBlur,
                                blurStyle = blurStyle
                            )
                            2 -> PickupCodePage(
                                viewModel = viewModel,
                                bottomPadding = homeBottomPadding,
                                onSettingsClick = { backStack.add(ScreenSettings) },
                                multiSelect = pickupMultiSelect,
                                selectedIds = pickupSelected,
                                onEnterMultiSelect = {
                                    pickupMultiSelect = true
                                    pickupSelected.add(it)
                                },
                                onExitMultiSelect = {
                                    pickupMultiSelect = false
                                    pickupSelected.clear()
                                },
                                enableBlur = enableBlur,
                                blurStyle = blurStyle
                            )
                        }
                    }

                    AddTransactionDialog(
                        show = showAddTransaction,
                        onDismiss = { showAddTransaction = false },
                        onAdd = { viewModel.addTransaction(it); showAddTransaction = false }
                    )
                    AddPickupCodeDialog(
                        show = showAddPickup,
                        onDismiss = { showAddPickup = false },
                        onSave = { viewModel.addPickupCode(it); showAddPickup = false }
                    )
                    }
                }
            }
            ScreenAbout -> NavEntry(route) {
                AboutPage(onBack = popAbout, enableBlur = enableBlur, onLicenseClick = { backStack.add(ScreenLicense) })
            }
            ScreenLicense -> NavEntry(route) {
                LicensePage(onBack = popLicense, enableBlur = enableBlur)
            }
            ScreenPermissionCheck -> NavEntry(route) {
                PermissionCheckPage(onBack = popPermissionCheck, enableBlur = enableBlur)
            }
            ScreenSettings -> NavEntry(route) {
                    SettingsPage(
                        navStyle = navStyle,
                        onNavStyleChange = {
                            navStyle = it
                            prefs.edit().putString("nav_style", it).apply()
                        },
                        bottomBarPosition = bottomBarPosition,
                        onBottomBarPositionChange = {
                            bottomBarPosition = it
                            prefs.edit().putString("bottom_bar_position", it).apply()
                        },
                    onAboutClick = { backStack.add(ScreenAbout) },
                    onPermissionCheckClick = { backStack.add(ScreenPermissionCheck) },
                    onThemeChange = onThemeChange,
                    onAccentColorChange = { accent ->
                        genPrefs.edit().putString("accent_color", accent).apply()
                        (context as? android.app.Activity)?.recreate()
                    },
                    onBack = popSettings,
                    refreshKey = 0,
                    enableBlur = enableBlur,
                    onEnableBlurChange = onEnableBlurChange,
                    blurStyle = blurStyle,
                    onBlurStyleChange = onBlurStyleChange,
                    islandMode = islandMode,
                    onIslandModeChange = onIslandModeChange,
                    onBackupSettingsClick = { backStack.add(ScreenBackupSettings) },
                    onNotifySettingsClick = { backStack.add(ScreenNotifySettings) },
                )
            }
            ScreenBackupSettings -> NavEntry(route) {
                BackupSettingsPage(
                    onBack = popBackupSettings,
                    enableBlur = enableBlur,
                    blurStyle = blurStyle,
                )
            }
            ScreenNotifySettings -> NavEntry(route) {
                NotifySettingsPage(
                    onBack = popNotifySettings,
                    enableBlur = enableBlur,
                    blurStyle = blurStyle,
                )
            }
        }
    }
}

private data class ActionSpec(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true
)

@Composable
private fun ActionItem(spec: ActionSpec, onSurface: Color, modifier: Modifier = Modifier) {
    val color = if (spec.enabled) onSurface else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    val clickable = if (spec.enabled) Modifier.clickable(onClick = spec.onClick) else Modifier
    Column(
        modifier = modifier.then(clickable).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = spec.icon,
            contentDescription = spec.label,
            tint = color,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = spec.label,
            color = color,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ActionBar(
    navStyle: String,
    homeBackdrop: Backdrop,
    blurActive: Boolean,
    position: FloatingBarPosition,
    items: List<ActionSpec>,
) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    when (navStyle) {
        "ios" -> {
            // 与常规 ios 底栏一致的液态玻璃（vibrancy + 折射 lens），承载操作项。
            // selectedIndex 传一个远小于 0 的值，让"选中指示药丸"移到屏幕外不可见──
            // 操作栏不需要选中态，只保留液态玻璃外观与点击响应。
            val iosBottom = Modifier.padding(
                bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
            )
            FloatingBottomBar(
                modifier = iosBottom,
                position = position,
                selectedIndex = { -999 },
                onSelected = {},
                backdrop = homeBackdrop,
                tabsCount = items.size,
                isBlurEnabled = blurActive,
            ) {
                items.forEach { spec ->
                    FloatingBottomBarItem(onClick = { if (spec.enabled) spec.onClick() }) {
                        Icon(
                            imageVector = spec.icon,
                            contentDescription = null,
                            tint = if (spec.enabled) onSurface else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = spec.label,
                            color = if (spec.enabled) onSurface else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            }
        }
        "floating" -> {
            val floatingBarColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
            val floatingBarShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
            val isDark = isSystemInDarkTheme()
            val floatingHighlight = remember(isDark) {
                if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
            }
            FloatingNavigationBar(
                modifier = if (blurActive) {
                    Modifier.textureBlur(
                        backdrop = homeBackdrop,
                        shape = floatingBarShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.6f))
                            )
                        ),
                        highlight = floatingHighlight,
                    )
                } else {
                    Modifier
                },
                color = floatingBarColor,
            ) {
                items.forEach { ActionItem(it, onSurface, Modifier) }
            }
        }
        else -> {
            val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
            Box(
                modifier = Modifier
                    .then(
                        if (blurActive) {
                            Modifier.textureBlur(
                                backdrop = homeBackdrop,
                                shape = RectangleShape,
                                blurRadius = 25f,
                                colors = BlurDefaults.blurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f))
                                    )
                                )
                            )
                        } else {
                            Modifier
                        }
                    )
                    .background(barColor)
            ) {
                NavigationBar(color = barColor) {
                    items.forEach { ActionItem(it, onSurface, Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * 首页右上角"设置"入口按钮（齿轮图标），放进页面折叠顶栏的 actions 槽里。
 * 图标固定、不随标题折叠，点击直接跳转设置页。
 */
@Composable
internal fun HomeTopActions(onSettingsClick: () -> Unit) {
    IconButton(onClick = onSettingsClick) {
        Icon(
            imageVector = MiuixIcons.Settings,
            contentDescription = "设置",
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

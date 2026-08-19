package com.zhaoyi.maji.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import com.zhaoyi.maji.MainActivity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import com.zhaoyi.maji.Prefs
import com.zhaoyi.maji.bill.BillPlatform
import com.zhaoyi.maji.island.CaptureHelper
import com.zhaoyi.maji.island.MiclawAccountLoginClient
import com.zhaoyi.maji.island.WhitelistBypass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

@Composable
fun SettingsPage(
    navStyle: String = "default",
    onNavStyleChange: (String) -> Unit = {},
    bottomBarPosition: String = "center",
    onBottomBarPositionChange: (String) -> Unit = {},
    onAboutClick: () -> Unit = {},
    onPermissionCheckClick: () -> Unit = {},
    onThemeChange: (String) -> Unit = {},
    onAccentColorChange: (String) -> Unit = {},
    refreshKey: Int = 0,
    onBack: () -> Unit = {},
    enableBlur: Boolean = true,
    onEnableBlurChange: (Boolean) -> Unit = {},
    blurStyle: String = "default",
    onBlurStyleChange: (String) -> Unit = {},
    islandMode: String = "xiaomi",
    onIslandModeChange: (String) -> Unit = {},
    onBackupSettingsClick: () -> Unit = {},
    onNotifySettingsClick: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val vm = viewModel<MainViewModel>()
    var themeMode by remember { mutableStateOf(Prefs.get(ctx, Prefs.Category.GENERAL).getString("theme_mode", "system") ?: "system") }
    val themeItems = listOf("跟随系统", "浅色", "深色")
    val themeKeys = listOf("system", "light", "dark")
    val themeIdx = themeKeys.indexOf(themeMode).coerceAtLeast(0)
    val navItems = listOf("常规", "悬浮", "液态玻璃")
    val navKeys = listOf("default", "floating", "ios")
    val navIdx = navKeys.indexOf(navStyle).coerceAtLeast(0)
    val posItems = listOf("居中", "靠左", "靠右")
    val posKeys = listOf("center", "start", "end")
    val posIdx = posKeys.indexOf(bottomBarPosition).coerceAtLeast(0)
    val accentColors = listOf(
        Color(0xFF3482FF), Color(0xFFFF5B29), Color(0xFFFFB21D), Color(0xFF36D167), Color(0xFF1ABC9C)
    )
    val accentKeys = listOf("blue", "red", "yellow", "green", "teal")
    val accentLabels = listOf("蓝色", "红色", "黄色", "绿色", "青色")
    val currentAccent = Prefs.get(ctx, Prefs.Category.GENERAL).getString("accent_color", "blue") ?: "blue"
    val accentIdx = accentKeys.indexOf(currentAccent).coerceAtLeast(0)
    val accentSpinnerItems = accentLabels.mapIndexed { i, label ->
        DropdownItem(
            icon = { Icon(RoundedRectanglePainter(accentColors[i]), label, Modifier.padding(end = 12.dp), accentColors[i]) },
            text = label
        )
    }

    // 导出 / 导入 多选项弹窗与密码弹窗状态（必须在 launcher 之前声明，lambda 才能捕获）
    var exportIndex by remember { mutableStateOf(-1) }
    var showExportPassword by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var showImportPassword by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // 导出：SAF 选保存位置
    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) vm.exportCsv(ctx, uri) }
    val zykExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) vm.exportEncryptedZip(ctx, uri, exportPassword) }
    // 导入：SAF 选加密 .zyk，选完进入输入密码弹窗
    val zykImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) { pendingImportUri = uri; showImportPassword = true } }

    // 账单导入：二选一（支付宝 CSV / 微信 XLSX），复用 GetContent 选文件
    var billImportIndex by remember { mutableStateOf(-1) }
    var pendingPlatform by remember { mutableStateOf<BillPlatform?>(null) }
    val billLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val plat = pendingPlatform
        if (uri != null && plat != null) vm.importBill(ctx, uri, plat)
        pendingPlatform = null
    }

    val scope = rememberCoroutineScope()

    // 账单导入结果提示
    val importResult by vm.importResult.collectAsState()
    LaunchedEffect(importResult) {
        importResult?.let {
            val msg = it.error ?: "已导入 ${it.inserted} 条，跳过 ${it.skipped} 条"
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            vm.clearImportResult()
        }
    }

    // 快捷键 / API 配置状态
    val recPrefs = ctx.getSharedPreferences("recognition", android.content.Context.MODE_PRIVATE)
    var showApiConfig by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(recPrefs.getString("api_key", "") ?: "") }
    var apiUrl by remember { mutableStateOf(recPrefs.getString("api_url", "https://api.xiaomimimo.com/v1/chat/completions") ?: "") }
    var modelName by remember { mutableStateOf(recPrefs.getString("model", "mimo-v2.5") ?: "") }
    var miclawToken by remember { mutableStateOf(com.zhaoyi.maji.island.MiclawSessionStore.load(ctx)?.serviceToken ?: "") }

    // MiMo 登录状态
    var showMimoLogin by remember { mutableStateOf(false) }
    var mimoAccount by remember { mutableStateOf("") }
    var mimoPassword by remember { mutableStateOf("") }
    var mimoCaptcha by remember { mutableStateOf("") }
    var mimoCaptchaBytes by remember { mutableStateOf<ByteArray?>(null) }
    var mimoTwoFactorFlag by remember { mutableStateOf(0) }
    var mimoTwoFactorCode by remember { mutableStateOf("") }
    var mimoLoading by remember { mutableStateOf(false) }
    val mimoClient = remember { com.zhaoyi.maji.island.MiclawAccountLoginClient() }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    // 页面级 backdrop：顶栏作为兄弟节点消费它，内容区用 layerBackdrop 捕获。
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
                    title = "设置",
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
                bottom = innerPadding.calculateBottomPadding() + 16.dp
            )
        ) {
            item(key = "display") {
                SmallTitle("显示")
                Card(modifier = Modifier.padding(bottom = 12.dp)) {
                    WindowDropdownPreference(
                        items = themeItems, selectedIndex = themeIdx, title = "主题",
                        onSelectedIndexChange = { themeMode = themeKeys[it]; onThemeChange(themeKeys[it]) }
                    )
                    WindowDropdownPreference(
                        items = navItems, selectedIndex = navIdx, title = "底栏样式",
                        onSelectedIndexChange = { onNavStyleChange(navKeys[it]) }
                    )
                    WindowDropdownPreference(
                        items = posItems, selectedIndex = posIdx, title = "底栏位置",
                        onSelectedIndexChange = { onBottomBarPositionChange(posKeys[it]) }
                    )
                    IconValueSpinnerPreference(
                        title = "主题色",
                        summary = "选择应用主题色",
                        items = accentSpinnerItems,
                        selectedIndex = accentIdx,
                        onSelectedIndexChange = { onAccentColorChange(accentKeys[it]) },
                    )
                    if (isRuntimeShaderSupported()) {
                        SwitchPreference(
                            title = "毛玻璃效果",
                            checked = enableBlur,
                            onCheckedChange = onEnableBlurChange,
                        )
                        // 参考 Miuix 示例「Click to expand a Switch」：开关打开后用
                        // AnimatedVisibility 滑出隐藏项；这里把隐藏的 Switch 换成
                        // 模糊样式 DropdownPref（默认 / 渐进）。
                        AnimatedVisibility(
                            visible = enableBlur,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            WindowDropdownPreference(
                                title = "模糊样式",
                                items = listOf("默认", "渐进"),
                                selectedIndex = if (blurStyle == "progressive") 1 else 0,
                                onSelectedIndexChange = { idx ->
                                    onBlurStyleChange(if (idx == 1) "progressive" else "default")
                                },
                            )
                        }
                    }
                }
            }

            item(key = "island") {
                SmallTitle("超级岛")
                Card(modifier = Modifier.padding(bottom = 12.dp)) {
                    val islandItems = listOf("小米超级岛", "Live Update (Android 16)")
                    val islandKeys = listOf("xiaomi", "live_update")
                    val islandIdx = islandKeys.indexOf(islandMode).coerceAtLeast(0)
                    WindowDropdownPreference(
                        title = "岛样式",
                        items = islandItems,
                        selectedIndex = islandIdx,
                        onSelectedIndexChange = { onIslandModeChange(islandKeys[it]) },
                    )
                    // 仅小米超级岛模式需要「断网绕过白名单」（依赖小米推送白名单机制）
                    AnimatedVisibility(
                        visible = islandMode == "xiaomi",
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        var bypassEnabled by remember {
                            mutableStateOf(
                                Prefs.get(ctx, Prefs.Category.GENERAL).getBoolean(WhitelistBypass.PREF_KEY, false),
                            )
                        }
                        SwitchPreference(
                            title = "断网绕过白名单",
                            summary = "需要 Shizuku 授权",
                            checked = bypassEnabled,
                            onCheckedChange = {
                                bypassEnabled = it
                                Prefs.get(ctx, Prefs.Category.GENERAL).edit()
                                    .putBoolean(WhitelistBypass.PREF_KEY, it).apply()
                                if (it && !CaptureHelper.shizukuReady()) {
                                    CaptureHelper.requestShizuku()
                                }
                            },
                        )
                    }
                }
            }

            item(key = "background") {
                SmallTitle("后台")
                Card(modifier = Modifier.padding(bottom = 12.dp)) {
                    var hideFromRecents by remember {
                        mutableStateOf(
                            Prefs.get(ctx, Prefs.Category.GENERAL)
                                .getBoolean(MainActivity.HIDE_FROM_RECENTS, false),
                        )
                    }
                    SwitchPreference(
                        title = "隐藏后台",
                        summary = "在多任务最近列表隐藏本应用",
                        checked = hideFromRecents,
                        onCheckedChange = {
                            hideFromRecents = it
                            Prefs.get(ctx, Prefs.Category.GENERAL).edit()
                                .putBoolean(MainActivity.HIDE_FROM_RECENTS, it).apply()
                            MainActivity.applyHideFromRecents(ctx, it)
                        },
                    )
                }
            }

            item(key = "data") {
                SmallTitle("数据")
                Card(modifier = Modifier.padding(bottom = 12.dp)) {
                    WindowDropdownPreference(
                        title = "导入账单",
                        summary = "从支付宝 / 微信 / 通用CSV / 加密zyk 导入",
                        items = listOf("支付宝账单", "微信账单", "通用CSV", "加密zyk"),
                        selectedIndex = billImportIndex,
                        onSelectedIndexChange = { index ->
                            when (index) {
                                0 -> pendingPlatform = BillPlatform.ALIPAY
                                1 -> pendingPlatform = BillPlatform.WECHAT
                                2 -> pendingPlatform = BillPlatform.GENERIC
                                3 -> { zykImportLauncher.launch("*/*") }
                                else -> {}
                            }
                            if (index < 3) billLauncher.launch("*/*")
                            billImportIndex = -1
                        },
                    )
                    WindowDropdownPreference(
                        title = "导出数据",
                        summary = "导出为通用CSV 或 加密备份",
                        items = listOf("导出通用CSV（未加密）", "导出加密格式（仅本APP可用）"),
                        selectedIndex = exportIndex,
                        onSelectedIndexChange = { index ->
                            when (index) {
                                0 -> csvExportLauncher.launch("maji_transactions.csv")
                                1 -> showExportPassword = true
                                else -> {}
                            }
                            exportIndex = -1
                        },
                    )
                }
            }

            item(key = "automation") {
                SmallTitle("自动化")
                Card(modifier = Modifier.padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = "自动备份",
                        summary = "本地 / WebDAV · 手动或定时",
                        onClick = onBackupSettingsClick
                    )
                    ArrowPreference(
                        title = "通认识别白名单",
                        summary = "命中关键词的通知自动提取取件码 / 自动记账",
                        onClick = onNotifySettingsClick
                    )
                }
            }

            item(key = "about") {
                SmallTitle("其他")
                Card(modifier = Modifier.padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = "在线模型配置",
                        summary = "API Key · 模型 · MiMo登录",
                        onClick = { showApiConfig = true }
                    )
                    ArrowPreference(
                        title = "权限检查",
                        summary = "后台运行 · 自启动 · 通知 · Shizuku · 无障碍",
                        onClick = onPermissionCheckClick
                    )
                }
            }

            item(key = "about2") {
                SmallTitle("其他")
                Card(modifier = Modifier.padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = "关于",
                        onClick = onAboutClick
                    )
                }
            }
        }
        }
    } // close Scaffold content

        // API 配置弹窗
        WindowDialog(
            show = showApiConfig,
            title = "在线模型配置",
            summary = "配置大模型 API Key 和地址",
            onDismissRequest = { showApiConfig = false },
            content = {
                Column {
                    TextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        label = "API 地址",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = "模型名称",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "API Key",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = miclawToken,
                        onValueChange = { miclawToken = it },
                        label = "MiMo serviceToken（选填）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        text = "小米账号登录 MiMo",
                        onClick = {
                            showApiConfig = false
                            mimoAccount = ""; mimoPassword = ""; mimoCaptcha = ""
                            mimoCaptchaBytes = null; mimoTwoFactorFlag = 0; mimoTwoFactorCode = ""
                            showMimoLogin = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            text = "取消",
                            onClick = { showApiConfig = false },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "保存",
                            onClick = {
                                recPrefs.edit().apply {
                                    putString("api_url", apiUrl)
                                    putString("api_key", apiKey)
                                    putString("model", modelName)
                                }.apply()
                                if (miclawToken.isNotBlank()) {
                                    val existing = com.zhaoyi.maji.island.MiclawSessionStore.load(ctx)
                                    if (existing == null || existing.serviceToken != miclawToken) {
                                        com.zhaoyi.maji.island.MiclawSessionStore.save(ctx,
                                            com.zhaoyi.maji.island.MiclawSession(
                                                serviceToken = miclawToken,
                                                passToken = existing?.passToken ?: "",
                                                cUserId = existing?.cUserId ?: "",
                                                userId = existing?.userId ?: "",
                                            ))
                                    }
                                }
                                showApiConfig = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        )

    // 导出加密：设置密码
    PasswordDialog(
        show = showExportPassword,
        title = "设置密码",
            warning = "您设置的密码不会保存在任何一个地方，您必须记住这个密码，否则导出的账单数据将无法再次导入",
            confirmText = "下一步",
            password = exportPassword,
            onPasswordChange = { exportPassword = it },
            onConfirm = {
                showExportPassword = false
                zykExportLauncher.launch("maji_backup.zyk")
            },
            onDismiss = { showExportPassword = false },
        )

    // 导入加密：输入密码
    PasswordDialog(
        show = showImportPassword,
        title = "输入密码",
            confirmText = "导入",
            password = importPassword,
            onPasswordChange = { importPassword = it },
            onConfirm = {
                showImportPassword = false
                pendingImportUri?.let { vm.importEncryptedZip(ctx, it, importPassword) }
                pendingImportUri = null
            },
            onDismiss = { showImportPassword = false; pendingImportUri = null },
        )

    // MiMo 登录弹窗
    WindowDialog(
        show = showMimoLogin,
            title = "小米账号登录 MiMo",
            summary = "使用小米账号密码登录，换取 MiMo 模型的 serviceToken",
            onDismissRequest = { showMimoLogin = false; mimoClient.reset() },
            content = {
                Column {
                    TextField(
                        value = mimoAccount,
                        onValueChange = { mimoAccount = it },
                        label = "小米账号（手机号/邮箱/ID）",
                        singleLine = true,
                        enabled = !mimoLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = mimoPassword,
                        onValueChange = { mimoPassword = it },
                        label = "密码",
                        singleLine = true,
                        enabled = !mimoLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (mimoCaptchaBytes != null) {
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = mimoCaptcha,
                            onValueChange = { mimoCaptcha = it },
                            label = "验证码",
                            singleLine = true,
                            enabled = !mimoLoading,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (mimoTwoFactorFlag != 0) {
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = mimoTwoFactorCode,
                            onValueChange = { mimoTwoFactorCode = it },
                            label = if (mimoTwoFactorFlag == 4) "短信验证码" else "邮箱验证码",
                            singleLine = true,
                            enabled = !mimoLoading,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (mimoTwoFactorFlag != 0) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            text = "发送验证码",
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { mimoClient.sendTicket(mimoTwoFactorFlag) }
                                    Toast.makeText(ctx, "验证码已发送", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            text = "取消",
                            onClick = { showMimoLogin = false; mimoClient.reset() },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = if (mimoLoading) "登录中…" else if (mimoTwoFactorFlag != 0) "验证" else "登录",
                            onClick = {
                                mimoLoading = true
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        when {
                                            mimoTwoFactorFlag != 0 -> mimoClient.verifyTicket(mimoTwoFactorFlag, mimoTwoFactorCode)
                                            else -> mimoClient.login(mimoAccount, mimoPassword, mimoCaptcha)
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        when (outcome) {
                                            is MiclawAccountLoginClient.Outcome.Authenticated -> {
                                                miclawToken = outcome.session.serviceToken
                                                com.zhaoyi.maji.island.MiclawSessionStore.save(ctx, outcome.session)
                                                Toast.makeText(ctx, "MiMo 登录成功", Toast.LENGTH_SHORT).show()
                                                showMimoLogin = false
                                                // 登录成功后立即暖连接，避免首次识别冷连接失败
                                                scope.launch(Dispatchers.IO) {
                                                    com.zhaoyi.maji.island.RustBridge.warmUpMiclaw(
                                                        outcome.session.serviceToken,
                                                        outcome.session.cUserId,
                                                    )
                                                }
                                            }
                                            is MiclawAccountLoginClient.Outcome.CaptchaRequired -> {
                                                mimoCaptchaBytes = outcome.imageBytes
                                                mimoTwoFactorFlag = 0
                                            }
                                            is MiclawAccountLoginClient.Outcome.TwoFactorRequired -> {
                                                mimoTwoFactorFlag = outcome.options.first()
                                                mimoCaptchaBytes = null
                                            }
                                            is MiclawAccountLoginClient.Outcome.Failed -> {
                                                Toast.makeText(ctx, outcome.message, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        mimoLoading = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            enabled = !mimoLoading,
                        )
                    }
                }
            }
        )

}

// ---- 导出 / 导入 弹窗辅助组件 ----

/** 输入密码的弹窗：标题 + 可选警告语（summary）+ 密码输入框 + 取消/确认 */
@Composable
private fun PasswordDialog(
    show: Boolean,
    title: String,
    warning: String? = null,
    confirmText: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = title,
        summary = warning,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            TextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "密码",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    enabled = password.isNotBlank(),
                )
            }
        }
    }
}

private class RoundedRectanglePainter(
    private val color: Color,
    private val cornerRadius: Dp = 6.dp,
) : Painter() {
    override val intrinsicSize = Size.Unspecified

    override fun DrawScope.onDraw() {
        drawRoundRect(
            color = color,
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
        )
    }
}

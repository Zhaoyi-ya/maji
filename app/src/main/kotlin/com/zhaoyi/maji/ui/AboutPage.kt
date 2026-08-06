package com.zhaoyi.maji.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhaoyi.maji.R
import com.zhaoyi.maji.ui.effect.BgEffectBackground
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

@Composable
fun AboutPage(onBack: () -> Unit, enableBlur: Boolean = true, onLicenseClick: () -> Unit) {
    val ctx = LocalContext.current
    val pkgInfo = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (e: Exception) { null }
    }
    val versionName = pkgInfo?.versionName ?: "unknown"
    val versionCode = pkgInfo?.let {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            it.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION") it.versionCode.toString()
        }
    } ?: "unknown"
    val buildValue = "v$versionName ($versionCode)"

    val isDark = isSystemInDarkTheme()
    val blurSupported = isRuntimeShaderSupported()
    // 全局毛玻璃开关（设置页持久化），与设备运行时着色器支持共同决定模糊是否生效。
    val blurOn = enableBlur && blurSupported
    val surface = MiuixTheme.colorScheme.surface

    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    val density = LocalDensity.current

    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "spacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }

    // 对齐 Miuix 示例：深色用 Overlay_Thin_Light、浅色用 Pured_Regular_Light，
    // 远高于原先 0.70f 不透明表面，卡片因此呈现高透毛玻璃质感。
    val cardBlend = if (isDark) {
        BlurDefaults.blurColors(
            blendColors = listOf(
                BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
                BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
            )
        )
    } else {
        BlurDefaults.blurColors(
            blendColors = listOf(
                BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
                BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight),
            )
        )
    }
    val barBlend = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(surface.copy(0.45f)))
    )
    val logoBlend = remember(isDark) {
        if (isDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    val collapsed by remember { derivedStateOf { scrollProgress == 1f } }
    val blurActive by remember { derivedStateOf { blurOn && collapsed } }

    var headerHeightDp by remember { mutableStateOf(300.dp) }

    Scaffold(
        topBar = {
            val barColor = when {
                blurActive -> Color.Transparent
                collapsed -> surface
                else -> Color.Transparent
            }
            val titleColor = MiuixTheme.colorScheme.onSurface.copy(
                alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
            )
            Box(
                modifier = if (blurActive) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = barBlend,
                    )
                } else {
                    Modifier
                },
            ) {
                TopAppBar(
                    title = "关于",
                    largeTitle = "",
                    color = barColor,
                    titleColor = titleColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回") }
                    },
                )
            }
        },
    ) { innerPadding ->
                BgEffectBackground(
                    dynamicBackground = blurOn,
                    modifier = Modifier.fillMaxSize(),
                    bgModifier = if (blurOn) Modifier.layerBackdrop(backdrop) else Modifier,
                    isFullSize = true,
                    isOs3Effect = true,
                    isDarkTheme = isDark,
                ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 52.dp)
                        .onGloballyPositioned { coordinates ->
                            headerHeightDp = with(density) { coordinates.size.height.toDp() }
                        }
                        .graphicsLayer {
                            alpha = 1f - (scrollProgress / 0.35f).coerceIn(0f, 1f)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White),
                    ) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier.size(74.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "码记",
                        color = MiuixTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 35.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (blurOn) {
                                    Modifier.textureBlur(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = 150f,
                                        colors = BlurDefaults.blurColors(blendColors = logoBlend),
                                        contentBlendMode = ComposeBlendMode.DstIn,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "v$versionName",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                LazyColumn(
                    state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                ) {
                    item(key = "spacer") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(headerHeightDp + 126.dp),
                        )
                    }
                    item(key = "cards") {
                        val clipboard = LocalClipboardManager.current
                        val valueStyle = MiuixTheme.textStyles.body2.fontSize
                        val valueColor = MiuixTheme.colorScheme.onSurfaceVariantActions
                        Card(
                            modifier = Modifier.fillMaxWidth().then(
                                if (blurOn) {
                                    Modifier.textureBlur(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = 60f,
                                        colors = cardBlend,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                            colors = if (blurOn) {
                                CardDefaults.defaultColors(Color.Transparent, Color.Transparent)
                            } else {
                                CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, Color.Transparent)
                            },
                        ) {
                            ArrowPreference(
                                title = "包名",
                                endActions = {
                                    Text("com.zhaoyi.maji", fontSize = valueStyle, color = valueColor)
                                },
                                onClick = { clipboard.setText(AnnotatedString("com.zhaoyi.maji")) },
                            )
                            ArrowPreference(
                                title = "版本",
                                endActions = {
                                    Text(versionName, fontSize = valueStyle, color = valueColor)
                                },
                                onClick = { clipboard.setText(AnnotatedString(versionName)) },
                            )
                            ArrowPreference(
                                title = "构建",
                                endActions = {
                                    Text(buildValue, fontSize = valueStyle, color = valueColor)
                                },
                                onClick = { clipboard.setText(AnnotatedString(buildValue)) },
                            )
                            ArrowPreference(
                                title = "第三方开源许可",
                                summary = "所用开源项目与许可",
                                onClick = onLicenseClick,
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

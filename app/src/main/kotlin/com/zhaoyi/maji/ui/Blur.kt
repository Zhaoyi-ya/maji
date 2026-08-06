package com.zhaoyi.maji.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 玻璃质感容器：在 [blurEnabled] 且 [backdrop] 可用时，用 Miuix 的 textureBlur
 * 把背后内容做毛玻璃处理；否则原样绘制内容。
 *
 * 直接复刻 Miuix 示例 app（utils/PageUtils.kt 的 BlurredBar），不自己造控件。
 * 配合根布局的 `Modifier.layerBackdrop(backdrop)` 使用：root 捕获内容，
 * 这里的 Box 把内容模糊后绘制在顶栏/底栏位置。
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (blurEnabled && backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
                    ),
                ),
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}

/**
 * 顶栏模糊容器：默认走 Miuix textureBlur（[BlurredBar]），渐进（progressive）走 Haze
 * 渐变模糊（[hazeChild] 消费 [hazeState]）。
 *
 * [useProgressive] 为真时，内容层需先用 `Modifier.haze(hazeState)` 捕获（见 [BlurContentBox]），
 * 本容器的 Box 用 `hazeChild` 把背后内容做**从上到下递减强度**的渐变模糊；折叠顶栏
 * CollapseTopBar 作为子节点绘制在内（barColor 应为透明）。这与 Miuix 折叠顶栏完全解耦——
 * CollapseTopBar / scrollBehavior 一行不变，模糊机制整体替换。
 *
 * 注意：Miuix 的 layerBackdrop 与 Haze 是两套互斥机制，此处为**替换**而非叠加，
 * 不会形成自引用层（小米 MiBackgroundBlurBlend SIGSEGV 的根因）。
 */
@Composable
fun BlurTopBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean,
    useProgressive: Boolean,
    hazeState: HazeState,
    content: @Composable () -> Unit,
) {
    if (useProgressive) {
        Box(
            Modifier.hazeEffect(
                state = hazeState,
                style = HazeDefaults.style(
                    backgroundColor = MiuixTheme.colorScheme.surface,
                    blurRadius = 12.dp,
                ),
            ) {
                // 从上到下递减强度的渐变模糊（progressive blur）：顶栏顶部最强、
                // 向下逐渐消失。这是 Haze 1.7.2 的标准用法——progressive 通过
                // block lambda 设置在 HazeEffectScope 上，而非 HazeStyle 里。
                progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
            }
        ) { content() }
    } else {
        BlurredBar(backdrop = backdrop, blurEnabled = blurActive) { content() }
    }
}

/**
 * 内容容器：默认用 Miuix layerBackdrop 捕获内容供顶栏模糊消费；
 * 渐进用 Haze 的 `haze()` 捕获内容（供 [BlurTopBar] 的 hazeChild 消费）。
 * 顶栏是兄弟节点，绝不放进被捕获的层里（对齐各页安全约束）。
 */
@Composable
fun BlurContentBox(
    backdrop: LayerBackdrop?,
    useProgressive: Boolean,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val boxModifier = if (useProgressive) {
        Modifier.hazeSource(state = hazeState)
    } else {
        if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
    }
    Box(modifier = modifier.then(boxModifier)) { content() }
}

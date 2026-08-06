// Adapted from KernelSU-Style-UI-Kit / compose-miuix-ui liquid glass primitives (Apache 2.0).
// 液态玻璃风格添加按钮：复用与 iOS 液态玻璃底栏相同的 vibrancy + blur + lens 折射、
// 按压果冻挤压（DampedDragAnimation）与高光，仅在 isBlurEnabled 时启用玻璃折射。
// 位置仍走 Scaffold 的 floatingActionButton 槽（右下角），只换材质不换位置。

package com.zhaoyi.maji.ui.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 液态玻璃添加按钮。
 *
 * - [isBlurEnabled] 为 true 时启用玻璃折射（vibrancy + blur + lens），false 时退化为纯色圆形。
 * - 按下：果冻挤压（scaleX/scaleY 弹簧回弹）+ 折射 depth 增强 + 内阴影 + 高光增强；松手回弹。
 * - 位置不随拖动改变（它是操作钮，只挤压不变位）。
 */
@Composable
fun LiquidGlassFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop,
    isBlurEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val isInDark = isSystemInDarkTheme()
    val accentColor = MiuixTheme.colorScheme.primary
    // 玻璃表面：以主题强调色做半透明着色，保证白色 + 号在明暗两种主题下都清晰。
    val containerColor = accentColor.copy(alpha = if (isInDark) 0.6f else 0.78f)
    val shape = CircleShape

    val animationScope = rememberCoroutineScope()
    // 仅用于按压/松手的弹簧挤压，canDrag 恒为 false 表示不变位。
    val fabAnim = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 0.88f,
            canDrag = { false },
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> },
        )
    }

    val fabSpecular = remember {
        Highlight(
            width = 1.dp,
            alpha = 1f,
            style = BloomStroke(
                color = Color.White.copy(alpha = 0.12f),
                innerBlurRadius = 2.dp,
                primaryLight = LightSource(
                    position = LightPosition(0.5f, -0.3f, -0.05f),
                    color = Color.White,
                    intensity = 1f,
                ),
                secondaryLight = LightSource(
                    position = LightPosition(0.5f, 0.8f, -0.5f),
                    color = Color.White,
                    intensity = 0.4f,
                ),
                dualPeak = true,
            ),
        )
    }

    // 跟随手指的折射/高光：按住后在按钮范围内拖动，lens 折射中心与高光跟随手指流动。
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope)
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onPress = {
                        fabAnim.press()
                        tryAwaitRelease()
                        fabAnim.release()
                    },
                )
            }
            .then(if (isBlurEnabled) interactiveHighlight.gestureModifier else Modifier)
            .then(
                if (isBlurEnabled) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            vibrancy()
                            blur(4.dp.toPx(), 4.dp.toPx())
                            val progress = fabAnim.pressProgress
                            lens(
                                refractionHeight = 8.dp.toPx() + 16.dp.toPx() * progress,
                                refractionAmount = 8.dp.toPx() + 16.dp.toPx() * progress,
                                depthEffect = true,
                                chromaticAberration = 0.5f * progress,
                            )
                        },
                        highlight = { fabSpecular.copy(alpha = 0.6f + 0.4f * fabAnim.pressProgress) },
                        layerBlock = {
                            // 点按果冻挤压（基础缩小）+ 拖动液态跟随形变（参考 kyant LiquidButton）：
                            // translation 用 tanh 饱和跟随手指，scale 用拖动角度分解，朝任意方向（含对角线）连续变形。
                            val width = size.width
                            val height = size.height

                            val progress = fabAnim.pressProgress
                            val baseScale = lerp(1f, 0.88f, progress)

                            val maxOffset = size.minDimension
                            val initialDerivative = 0.05f
                            val offset = interactiveHighlight.offset
                            translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                            translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                            val maxDragScale = 4f.dp.toPx() / size.height
                            val offsetAngle = atan2(offset.y, offset.x)
                            scaleX = baseScale +
                                    maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                            (width / height).fastCoerceAtMost(1f)
                            scaleY = baseScale +
                                    maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                            (height / width).fastCoerceAtMost(1f)
                        },
                        onDrawSurface = { drawCircle(containerColor) },
                    ).innerShadow(shape) {
                        InnerShadow(
                            radius = 8.dp * fabAnim.pressProgress,
                            color = Color.Black.copy(0.15f),
                            alpha = fabAnim.pressProgress,
                        )
                    }
                } else {
                    Modifier.background(containerColor, shape)
                }
            )
            .then(if (isBlurEnabled) interactiveHighlight.modifier else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

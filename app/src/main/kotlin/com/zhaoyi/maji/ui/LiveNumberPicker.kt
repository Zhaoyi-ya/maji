// 基于 Miuix 0.9.3 NumberPicker 源码复制，额外增加 onScrolling 实时回调：
// Miuix 原版只在 onDragStopped（甩动结束后）才回调 onValueChange，拖动过程中
// 外部无法得知当前指向的值；本版本在 effectiveIndex 变化时（拖动中 + 惯性滑动中）
// 同步回调 onScrolling，便于在弹窗里实时刷新"总览"，但 onScrolling 不会写回 value，
// 只有停稳后的 onValueChange 才更新真正的选中值，避免与内部拖拽 offset 打架。

package com.zhaoyi.maji.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

@Composable
fun LiveNumberPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    range: IntRange = 0..10,
    label: (Int) -> String = { it.toString() },
    visibleItemCount: Int = 5,
    wrapAround: Boolean = false,
    colors: NumberPickerColors = NumberPickerDefaults.colors(),
    textStyle: TextStyle = MiuixTheme.textStyles.title1,
    itemHeight: Dp = NumberPickerDefaults.ItemHeight,
    onScrolling: ((Int) -> Unit)? = null,
) {
    require(visibleItemCount % 2 == 1 && visibleItemCount >= 3) {
        "visibleItemCount must be odd and at least 3, but was $visibleItemCount"
    }
    require(range.first <= range.last) {
        "range must not be empty"
    }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnScrolling by rememberUpdatedState(onScrolling)
    val itemCount = range.last - range.first + 1
    val coercedValue = value.coerceIn(range)
    val externalIndex = coercedValue - range.first
    val halfVisibleCount = visibleItemCount / 2
    val hapticFeedback = LocalHapticFeedback.current

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val flingAnimatable = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isUserScrolling by remember { mutableStateOf(false) }
    var itemHeightPx by remember { mutableIntStateOf(0) }

    // 内部索引才是绘制用的唯一真相源。
    // 原实现直接用外部 value 推导索引，落定时先 onValueChange 再 snapTo(0)，
    // 而外部 state 要等下一次重组才回传，于是有一帧画面回到旧索引 —— 视觉上就是"松手后跳回去"。
    var currentIndex by remember { mutableIntStateOf(externalIndex) }

    // 外部 value 被改动（受控刷新 / 打开弹窗重置 / 被夹紧）时同步进来
    LaunchedEffect(externalIndex) {
        if (externalIndex != currentIndex) {
            currentIndex = externalIndex
            if (!isDragging) {
                dragOffset = 0f
                flingAnimatable.snapTo(0f)
            }
        }
    }

    // range 变化（如月份切换导致天数变化）时把内部索引夹回合法区间
    LaunchedEffect(itemCount) {
        if (!wrapAround && currentIndex > itemCount - 1) {
            currentIndex = itemCount - 1
        }
    }

    val totalOffset by remember {
        derivedStateOf { dragOffset + flingAnimatable.value }
    }

    // 用 rememberUpdatedState 让 derivedStateOf 读到最新的 itemCount/wrapAround，
    // 从而这个 State 对象永不重建 —— 否则下面 LaunchedEffect(Unit) 里的 snapshotFlow
    // 会一直观察首次 composition 那个陈旧 State，onScrolling 回调出的值会偏移。
    val latestItemCount by rememberUpdatedState(itemCount)
    val latestWrapAround by rememberUpdatedState(wrapAround)
    val effectiveIndex by remember {
        derivedStateOf {
            val rawIndex = currentIndex + totalOffset.fastRoundToInt()
            if (latestWrapAround) {
                ((rawIndex % latestItemCount) + latestItemCount) % latestItemCount
            } else {
                rawIndex.coerceIn(0, latestItemCount - 1)
            }
        }
    }

    var lastHapticIndex by remember { mutableIntStateOf(externalIndex) }
    LaunchedEffect(Unit) {
        snapshotFlow { effectiveIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index != lastHapticIndex) {
                    if (isUserScrolling) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    lastHapticIndex = index
                }
                // 实时回调：拖动中 / 惯性滑动中每跨过一个条目就通知外部，而不仅仅停稳后
                currentOnScrolling?.invoke(range.first + index)
            }
    }

    val totalHeight = itemHeight * visibleItemCount

    val draggableState = rememberDraggableState { delta ->
        if (itemHeightPx > 0) {
            val newOffset = dragOffset - delta / itemHeightPx
            dragOffset = if (wrapAround) {
                newOffset
            } else {
                newOffset.coerceIn(
                    -(currentIndex.toFloat()),
                    (itemCount - 1 - currentIndex).toFloat(),
                )
            }
        }
    }

    val displayValue = label(range.first + currentIndex.coerceIn(0, itemCount - 1))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .clipToBounds()
            .semantics {
                contentDescription = "$displayValue, ${range.first} - ${range.last}"
            }
            .onSizeChanged { size ->
                itemHeightPx = size.height / visibleItemCount
            }
            .then(
                if (enabled) {
                    Modifier.draggable(
                        orientation = Orientation.Vertical,
                        state = draggableState,
                        onDragStarted = {
                            flingAnimatable.stop()
                            dragOffset += flingAnimatable.value
                            flingAnimatable.snapTo(0f)
                            isDragging = true
                            isUserScrolling = true
                        },
                        onDragStopped = { velocity ->
                            isDragging = false
                            if (itemHeightPx > 0) {
                                val startIndex = currentIndex
                                val currentDragOffset = dragOffset
                                dragOffset = 0f
                                flingAnimatable.snapTo(currentDragOffset)

                                val velocityInItems = -velocity / itemHeightPx
                                val decay = exponentialDecay<Float>(frictionMultiplier = 2f)
                                if (!wrapAround) {
                                    val min = -(startIndex.toFloat())
                                    val max = (itemCount - 1 - startIndex).toFloat()
                                    flingAnimatable.updateBounds(min, max)
                                }
                                flingAnimatable.animateDecay(velocityInItems, decay)
                                flingAnimatable.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
                                val snappedTarget = flingAnimatable.value.fastRoundToInt().toFloat()
                                flingAnimatable.animateTo(
                                    targetValue = snappedTarget,
                                    animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
                                )
                                val offsetInt = flingAnimatable.value.fastRoundToInt()
                                val newIndex = if (wrapAround) {
                                    ((startIndex + offsetInt) % itemCount + itemCount) % itemCount
                                } else {
                                    (startIndex + offsetInt).coerceIn(0, itemCount - 1)
                                }
                                // 关键顺序：先把内部索引落到新位置，再把动画偏移归零。
                                // 两个 state 在同一帧内一起生效，画面直接停在新值，
                                // 不再依赖外部 value 回传，也就没有"先跳回旧值再跳过来"的回弹。
                                currentIndex = newIndex
                                flingAnimatable.snapTo(0f)
                                isUserScrolling = false

                                val newValue = range.first + newIndex
                                currentOnScrolling?.invoke(newValue)
                                if (newValue != coercedValue) {
                                    currentOnValueChange(newValue)
                                }
                            }
                        },
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (itemHeightPx > 0) {
            val currentTotalOffset = totalOffset
            val centerItemOffset = currentTotalOffset - currentTotalOffset.fastRoundToInt()
            val roundedOffset = currentTotalOffset.fastRoundToInt()
            val selectedColor = colors.selectedTextColor(enabled)
            val unselectedColor = colors.unselectedTextColor(enabled)
            val resolvedTextStyle = if (textStyle.fontWeight == null) textStyle.copy(fontWeight = FontWeight.SemiBold) else textStyle

            for (i in -halfVisibleCount - 1..halfVisibleCount + 1) {
                val rawItemIndex = currentIndex + i + roundedOffset
                val itemIndex = if (wrapAround) {
                    ((rawItemIndex % itemCount) + itemCount) % itemCount
                } else {
                    if (rawItemIndex !in 0..<itemCount) continue
                    rawItemIndex
                }

                val distanceFromCenter = i.toFloat() - centerItemOffset
                val normalizedDistance = (abs(distanceFromCenter) / (halfVisibleCount + 0.5f)).coerceIn(0f, 1f)

                val alpha = (1f - normalizedDistance) * (1f - normalizedDistance * 0.5f)
                val scale = 1f - 0.2f * normalizedDistance
                val yOffset = distanceFromCenter * itemHeightPx

                val textColor = Color(
                    red = lerp(selectedColor.red, unselectedColor.red, normalizedDistance),
                    green = lerp(selectedColor.green, unselectedColor.green, normalizedDistance),
                    blue = lerp(selectedColor.blue, unselectedColor.blue, normalizedDistance),
                    alpha = lerp(selectedColor.alpha, unselectedColor.alpha, normalizedDistance),
                )

                Text(
                    text = label(range.first + itemIndex),
                    modifier = Modifier
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                            translationY = yOffset
                        },
                    style = resolvedTextStyle,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

object NumberPickerDefaults {

    val ItemHeight = 45.dp

    @Composable
    fun colors(
        selectedTextColor: Color = MiuixTheme.colorScheme.onSurface,
        unselectedTextColor: Color = MiuixTheme.colorScheme.onSurfaceSecondary,
        disabledSelectedTextColor: Color = MiuixTheme.colorScheme.disabledOnSecondary,
        disabledUnselectedTextColor: Color = MiuixTheme.colorScheme.disabledOnSecondary,
    ): NumberPickerColors = remember(
        selectedTextColor,
        unselectedTextColor,
        disabledSelectedTextColor,
        unselectedTextColor,
    ) {
        NumberPickerColors(
            selectedTextColor = selectedTextColor,
            unselectedTextColor = unselectedTextColor,
            disabledSelectedTextColor = disabledSelectedTextColor,
            disabledUnselectedTextColor = disabledUnselectedTextColor,
        )
    }
}

@Immutable
data class NumberPickerColors(
    private val selectedTextColor: Color,
    private val unselectedTextColor: Color,
    private val disabledSelectedTextColor: Color,
    private val disabledUnselectedTextColor: Color,
) {

    @Stable
    internal fun selectedTextColor(enabled: Boolean): Color = if (enabled) selectedTextColor else disabledSelectedTextColor

    @Stable
    internal fun unselectedTextColor(enabled: Boolean): Color = if (enabled) unselectedTextColor else disabledUnselectedTextColor
}

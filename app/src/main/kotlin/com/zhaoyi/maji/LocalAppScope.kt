package com.zhaoyi.maji

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.CoroutineScope

/**
 * Application-wide CoroutineScope exposed to the Compose tree. Bound in
 * `MainActivity.onCreate` to `MaJiApp.appScope` so that all UI-side
 * background work (search debounce, screenshot capture, AI callsâ€? shares
 * one lifetime tied to the process, not to any single composable.
 *
 * Composables retrieve the scope via `LocalAppScope.current` instead of
 * calling `rememberCoroutineScope()` for fire-and-forget work that should
 * survive recomposition but die with the process.
 */
val LocalAppScope = compositionLocalOf<CoroutineScope> {
    error("LocalAppScope not provided. Wrap your composable in MainActivity's CompositionLocalProvider.")
}

package io.github.xororz.localdream.ui.design

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFocusManager

internal val LocalImeVisibleOverride = staticCompositionLocalOf<Boolean?> { null }

/** Each window's focus owner observes the IME transition, including fields in dialogs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClearFocusOnImeDismiss(visibleOverride: Boolean? = null) {
    val visible = visibleOverride ?: LocalImeVisibleOverride.current ?: WindowInsets.isImeVisible
    val focus = LocalFocusManager.current
    var wasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (wasVisible && !visible) focus.clearFocus(force = true)
        wasVisible = visible
    }
}

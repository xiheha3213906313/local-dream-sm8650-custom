package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.ui.design.ChoiceControl
import io.github.xororz.localdream.ui.design.Text
import kotlin.math.roundToInt

internal val CommonAspectRatios = listOf("9:16" to 9.0 / 16, "2:3" to 2.0 / 3, "3:4" to .75, "1:1" to 1.0, "4:3" to 4.0 / 3, "3:2" to 1.5, "16:9" to 16.0 / 9)

@Composable
internal fun AspectRatioPicker(source: Rect, width: Dp, selected: Int, onSelected: (Int) -> Unit) {
    val density = LocalDensity.current
    val state = rememberLazyListState()
    LaunchedEffect(selected, state.layoutInfo.viewportSize.width) {
        val offset = -(state.layoutInfo.viewportSize.width - with(density) { 64.dp.roundToPx() }) / 2
        state.animateScrollToItem(selected, offset)
    }
    Surface(
        Modifier.offset { IntOffset(with(density) { 16.dp.roundToPx() }, (source.top - with(density) { 112.dp.toPx() }).roundToInt().coerceAtLeast(0)) }
            .width(width - 32.dp).testTag("aspect-ratio-picker"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(CommonAspectRatios[selected].first, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(CommonAspectRatios) { index, ratio ->
                    ChoiceControl(ratio.first, { onSelected(index) }, checked = index == selected)
                }
            }
        }
    }
}

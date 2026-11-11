package io.github.xororz.localdream.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlinx.coroutines.delay

/** Keys keep image, URI and encoded payload together while the list moves beneath the pointer. */
@Composable
internal fun <T> ReorderableReferenceRow(
    items: List<T>,
    itemKey: (T) -> String,
    enabled: Boolean,
    onMove: (Int, Int) -> Unit,
    hasLeading: Boolean = false,
    leading: @Composable () -> Unit = {},
    itemContent: @Composable (T, Int, Modifier) -> Unit,
) {
    val state = rememberLazyListState()
    val latestItems by rememberUpdatedState(items)
    val latestMove by rememberUpdatedState(onMove)
    val latestKey by rememberUpdatedState(itemKey)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestHasLeading by rememberUpdatedState(hasLeading)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var dragged by remember { mutableStateOf<String?>(null) }
    var startOffset by remember { mutableFloatStateOf(0f) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var scrollDirection by remember { mutableIntStateOf(0) }
    var draggedWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled) { if (!enabled) dragged = null }
    fun reorder() {
        val key = dragged ?: return
        val center = startOffset + dragDelta + draggedWidth / 2
        val target = state.layoutInfo.visibleItemsInfo.filter { it.key.toString().startsWith("ref:") }
            .minByOrNull { abs(it.offset + it.size / 2 - center) } ?: return
        val from = latestItems.indexOfFirst { "ref:${latestKey(it)}" == key }
        val to = latestItems.indexOfFirst { "ref:${latestKey(it)}" == target.key }
        val current = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        if (current?.index != from + if (latestHasLeading) 1 else 0) return
        if (from >= 0 && to >= 0 && from != to) {
            // Reordering the first visible key must not drag the viewport along with it.
            state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            latestMove(from, to)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    fun updateScrollDirection() {
        val edge = with(density) { 36.dp.toPx() }
        val center = startOffset + dragDelta + draggedWidth / 2
        scrollDirection = when {
            center < state.layoutInfo.viewportStartOffset + edge -> -1
            center > state.layoutInfo.viewportEndOffset - edge -> 1
            else -> 0
        }
    }
    LaunchedEffect(dragged, scrollDirection) {
        if (dragged != null && scrollDirection != 0) {
            val speed = with(density) { 8.dp.toPx() }
            while (true) {
                if (state.scrollBy(scrollDirection * speed) == 0f) break
                reorder()
                delay(16)
            }
        }
    }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().testTag("reference-image-strip").pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    if (latestEnabled) {
                        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { point.x >= it.offset && point.x <= it.offset + it.size && it.key.toString().startsWith("ref:") }
                        if (info != null) {
                            dragged = info.key.toString()
                            startOffset = info.offset.toFloat()
                            dragDelta = 0f
                            draggedWidth = info.size
                            scrollDirection = 0
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
                onDrag = { change, delta ->
                    if (dragged != null) {
                        dragDelta += delta.x
                        updateScrollDirection()
                        reorder()
                        change.consume()
                    }
                },
                onDragEnd = { dragged = null },
                onDragCancel = { dragged = null },
            )
        },
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasLeading) item(key = "base-and-mask") { leading() }
        itemsIndexed(items, key = { _, value -> "ref:${itemKey(value)}" }) { index, value ->
            val key = "ref:${itemKey(value)}"
            val dragging = dragged == key
            val modifier = if (dragging) Modifier else Modifier.animateItem(placementSpec = tween(160))
            Box(
                modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                    if (dragging) {
                        val position = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: startOffset.toInt()
                        translationX = startOffset + dragDelta - position
                        scaleX = 1.04f
                        scaleY = 1.04f
                        shadowElevation = with(density) { 6.dp.toPx() }
                    }
                },
            ) { itemContent(value, index, Modifier) }
        }
    }
}

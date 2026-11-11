package io.github.xororz.localdream.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.ui.design.*
import kotlin.math.roundToInt

/** One surface in the workspace window: its edges travel from and back to the tapped tile. */
@Composable
internal fun ExpandedParameter(
    label: String,
    value: String,
    source: Rect,
    workspaceWidth: Dp,
    workspaceHeight: Dp,
    preferredHeight: Dp,
    open: Boolean,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        progress.animateTo(if (open) 1f else 0f, tween(320, easing = FastOutSlowInEasing))
        if (!open) onClosed()
    }
    BackHandler { onDismiss() }
    val density = LocalDensity.current
    val targetWidth = (workspaceWidth - 32.dp).coerceAtLeast(160.dp)
    // A small card, with internal scrolling only on short screens or with the IME open.
    var measuredHeight by remember { mutableStateOf<Dp?>(null) }
    val targetHeight by animateDpAsState((measuredHeight ?: preferredHeight).coerceAtMost(workspaceHeight - 16.dp).coerceAtLeast(120.dp), tween(220), label = "parameter-content-height")
    val targetLeft = with(density) { 16.dp.toPx() }
    val targetTop = with(density) {
        (source.top.toDp() - targetHeight + source.height.toDp()).coerceIn(8.dp, (workspaceHeight - targetHeight - 8.dp).coerceAtLeast(8.dp)).toPx()
    }
    val fraction = progress.value
    fun between(a: Float, b: Float) = a + (b - a) * fraction
    val width = with(density) { between(source.width, targetWidth.toPx()).toDp() }
    val height = with(density) { between(source.height, targetHeight.toPx()).toDp() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f * fraction)).clickable(onClick = onDismiss).testTag("parameter-editor-scrim"))
    Surface(
        modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(between(source.left, targetLeft).roundToInt(), between(source.top, targetTop).roundToInt()) }
            .size(width, height).testTag("parameter-editor-card"),
        shape = RoundedCornerShape(24.dp),
        color = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.surfaceContainerHigh, fraction),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = fraction)),
        shadowElevation = 12.dp * fraction,
    ) {
        // Measure the editor at its final size while the surface clips to the animated edges.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            if (fraction < .65f) {
                ParameterSummary(label, value, Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - (fraction / .65f) })
            }
            Column(
                Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(targetWidth, targetHeight)
                    .graphicsLayer { alpha = ((fraction - .35f) / .65f).coerceIn(0f, 1f) }
                    .clickable {} // The card consumes taps; the scrim is its sibling.
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(Modifier.fillMaxWidth().onSizeChanged { measuredHeight = with(density) { it.height.toDp() } }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.ui_back_to_prompts)) }
                    }
                    content()
                }
            }
        }
    }
}

/** A non-interactive copy above the touch point; the original tile keeps the pointer stream. */
@Composable
internal fun LiftedParameter(label: String, value: String, source: Rect) {
    val density = LocalDensity.current
    Surface(
        Modifier.offset { androidx.compose.ui.unit.IntOffset(source.left.roundToInt(), (source.top - with(density) { 44.dp.toPx() }).roundToInt().coerceAtLeast(0)) }
            .size(with(density) { source.width.toDp() }, with(density) { source.height.toDp() })
            .testTag("parameter-drag-preview"),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        shadowElevation = 12.dp,
    ) { ParameterSummary(label, value, Modifier.fillMaxSize()) }
}

@Composable
private fun ParameterSummary(label: String, value: String, modifier: Modifier) {
    Row(modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ParameterChoice(label: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { selected = checked },
        shape = RoundedCornerShape(14.dp),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(if (checked) 2.dp else 1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (checked) Icon(Icons.Default.Check, null, Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ParameterNumberInput(label: String, value: String, onValueChange: (String) -> Unit, integer: Boolean, onDone: () -> Unit) {
    // The general Miuix text-field adapter shares its card background; use an explicit outlined
    // container here so editable values remain identifiable in both themes and at large fonts.
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag("parameter-number-input"),
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        placeholder = { Text(stringResource(R.string.ui_enter_number), style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(20.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

@Composable
internal fun SeedEditor(seed: String, returnedSeed: Long?, onChange: (String) -> Unit, onUseLast: () -> Unit, onDone: () -> Unit) {
    var entry by rememberSaveable(seed) { mutableStateOf(seed) }
    fun commitSeed() {
        if (seed.isNotBlank() && entry.isBlank()) onChange("0")
        onDone()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ParameterChoice(stringResource(R.string.ui_seed_random_mode), seed.isBlank(), { onChange("") }, Modifier.testTag("seed-random-mode"))
            ParameterChoice(stringResource(R.string.ui_seed_fixed_mode), seed.isNotBlank(), { onChange(seed.ifBlank { returnedSeed?.toString() ?: "0" }) }, Modifier.testTag("seed-fixed-mode"))
        }
        if (seed.isBlank()) {
            Text(stringResource(R.string.ui_seed_random_help), style = MaterialTheme.typography.bodyMedium)
        } else {
            ParameterNumberInput(stringResource(R.string.ui_seed), entry, { next ->
                if (next.isEmpty() || (next.all(Char::isDigit) && next.toLongOrNull() != null)) {
                    entry = next
                    if (next.isNotEmpty()) onChange(next)
                }
            }, true, ::commitSeed)
        }
        if (returnedSeed != null) {
            ParameterChoice(stringResource(R.string.ui_use_last_seed), false, onUseLast, Modifier.fillMaxWidth())
            Text(stringResource(R.string.ui_last_seed_value, returnedSeed.toString()), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = ::commitSeed, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.confirm)) }
    }
}

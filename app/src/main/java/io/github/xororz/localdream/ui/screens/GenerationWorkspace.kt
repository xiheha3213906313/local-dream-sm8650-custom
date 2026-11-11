package io.github.xororz.localdream.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Resolution
import io.github.xororz.localdream.ui.design.*
import kotlin.math.roundToInt

/** Fixed viewport: icon image actions, independent prompts and collapsible parameter controls. */
@Composable
internal fun GenerationWorkspace(
    isSdxl: Boolean,
    isDit: Boolean = false,
    runOnCpu: Boolean,
    useImg2img: Boolean,
    isRunning: Boolean,
    aspectRatio: String,
    availableResolutions: List<Resolution>,
    currentWidth: Int,
    currentHeight: Int,
    scheduler: String,
    steps: Float,
    cfg: Float,
    useOpenCL: Boolean,
    batchCounts: Int,
    denoiseStrength: Float,
    seed: String,
    returnedSeed: Long?,
    onAspectRatioSelected: (String) -> Unit,
    onCustomAspectRatioClick: () -> Unit,
    onResolutionSelected: (Resolution) -> Unit,
    onDitWidthChange: (Float) -> Unit = {},
    onDitHeightChange: (Float) -> Unit = {},
    onSchedulerChange: (String) -> Unit,
    onStepsChange: (Float) -> Unit,
    onCfgChange: (Float) -> Unit,
    onSizeChange: (Float) -> Unit,
    onCpuSelected: () -> Unit,
    onGpuSelected: () -> Unit,
    onBatchCountsChange: (Float) -> Unit,
    onDenoiseStrengthChange: (Float) -> Unit,
    onSeedChange: (String) -> Unit,
    onUseLastSeed: () -> Unit,
    positive: @Composable () -> Unit,
    negative: @Composable () -> Unit,
    inputs: (@Composable (compact: Boolean) -> Unit)? = null,
    status: String? = null,
    promptFocused: Boolean = false,
    positiveCount: String = "",
    negativeCount: String = "",
) {
    var active by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<String?>(null) }
    val anchors = remember { mutableStateMapOf<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var parametersExpanded by rememberSaveable { mutableStateOf(true) }
    var negativeExpanded by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboardVisible = keyboardIsVisible()
    fun finishEdit() {
        focus.clearFocus()
        editorOpen = false
    }
    BackHandler(enabled = active != null) { finishEdit() }
    val fields = mutableListOf<WorkspaceParameter>()
    fun field(id: String, label: String, value: String, enabled: Boolean = true) {
        fields += WorkspaceParameter(id, label, value, enabled)
    }
    field("width", stringResource(R.string.ui_width), currentWidth.toString(), !isRunning && (isDit || (runOnCpu && !isSdxl) || (!isSdxl && availableResolutions.isNotEmpty())))
    field("height", stringResource(R.string.ui_height), currentHeight.toString(), !isRunning && (isDit || (!runOnCpu && !isSdxl && availableResolutions.isNotEmpty())))
    field("steps", stringResource(R.string.ui_steps), steps.roundToInt().toString())
    field("cfg", "CFG", "%.1f".format(cfg))
    field("scheduler", stringResource(R.string.ui_sampler_short), scheduler.replace("dpm_sde", "DPM SDE").replace("dpm", "DPM").replace("_karras", " K"))
    field("seed", stringResource(R.string.ui_seed), if (seed.isBlank()) stringResource(R.string.ui_random) else seed.takeLast(4).let { if (seed.length > 4) "…$it" else it })
    field("batch", stringResource(R.string.ui_batch), batchCounts.toString())
    if (useImg2img) field("denoise", stringResource(R.string.ui_denoise_short), "%.2f".format(denoiseStrength))
    if (runOnCpu && !isDit) field("runtime", stringResource(R.string.ui_runtime_short), if (useOpenCL) "GPU" else "CPU")
    if (isSdxl && useImg2img) field("aspect", stringResource(R.string.ui_aspect_short), aspectRatio, !isRunning)

    fun adjust(id: String, ticks: Int) {
        when (id) {
            "width" -> when {
                isDit -> onDitWidthChange((currentWidth + ticks * DIT_SIZE_STEP).toFloat().coerceIn(DIT_MIN_SIZE.toFloat(), DIT_MAX_SIZE.toFloat()))
                runOnCpu -> onSizeChange((currentWidth + ticks * 64).toFloat().coerceIn(128f, 512f))
                else -> availableResolutions.takeIf { it.isNotEmpty() }?.let { list -> onResolutionSelected(list[(list.indexOfFirst { it.width == currentWidth && it.height == currentHeight }.coerceAtLeast(0) + ticks).coerceIn(list.indices)]) }
            }

            "height" -> if (isDit) {
                onDitHeightChange((currentHeight + ticks * DIT_SIZE_STEP).toFloat().coerceIn(DIT_MIN_SIZE.toFloat(), DIT_MAX_SIZE.toFloat()))
            } else {
                availableResolutions.takeIf { it.isNotEmpty() }?.let { list -> onResolutionSelected(list[(list.indexOfFirst { it.width == currentWidth && it.height == currentHeight }.coerceAtLeast(0) + ticks).coerceIn(list.indices)]) }
            }

            "steps" -> onStepsChange((steps + ticks).coerceIn(1f, 50f))

            "cfg" -> onCfgChange((cfg + ticks * .5f).coerceIn(1f, 30f))

            "batch" -> onBatchCountsChange((batchCounts + ticks).toFloat().coerceIn(1f, 10f))

            "denoise" -> onDenoiseStrengthChange((denoiseStrength + ticks * .01f).coerceIn(0f, 1f))

            "seed" -> {
                val start = (seed.toLongOrNull() ?: returnedSeed ?: 0L).coerceIn(0L, Long.MAX_VALUE - ticks.coerceAtLeast(0))
                onSeedChange((start + ticks).coerceAtLeast(0L).toString())
            }

            "runtime" -> {
                val selectGpu = if (ticks == 0) useOpenCL else ticks > 0
                if (selectGpu) onGpuSelected() else onCpuSelected()
            }

            "scheduler" -> {
                val choices = listOf("dpm", "dpm_sde", "euler_a", "euler", "lcm")
                val id = choices[(choices.indexOf(scheduler.removeSuffix("_karras")).coerceAtLeast(0) + ticks).coerceIn(choices.indices)]
                onSchedulerChange(if (scheduler.endsWith("_karras") && id != "lcm") "${id}_karras" else id)
            }

            "aspect" -> {
                val choices = listOf("1:1", "3:4", "4:3")
                onAspectRatioSelected(choices[(choices.indexOf(aspectRatio).coerceAtLeast(0) + ticks).coerceIn(choices.indices)])
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val compact = maxHeight < 420.dp || keyboardVisible
        val showParameters = parametersExpanded && !compact && !promptFocused
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp).testTag("generation-workspace"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (inputs != null) Box(Modifier.fillMaxWidth().testTag("workspace-image-inputs")) { inputs(compact) }
            PromptRegion(stringResource(R.string.ui_positive_short), positiveCount, Modifier.weight(1f, fill = false).heightIn(max = 192.dp), positive)
            // Independent prompt section directly below the positive prompt, outside the parameters.
            Column(Modifier.fillMaxWidth().testTag("negative-prompt-section"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("negative-prompt-toggle").clickable(role = Role.Button) {
                        focus.clearFocus()
                        negativeExpanded = !negativeExpanded
                        if (negativeExpanded) parametersExpanded = false
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.ui_negative_short), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    if (negativeCount.isNotBlank()) Text(negativeCount, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Icon(if (negativeExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
                if (negativeExpanded) Box(Modifier.fillMaxWidth().height(72.dp).testTag("negative-prompt-editor")) { negative() }
            }
            Column(Modifier.fillMaxWidth().testTag("parameter-panel"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Card(onClick = {
                    focus.clearFocus()
                    parametersExpanded = !showParameters
                    if (parametersExpanded) negativeExpanded = false
                }, modifier = Modifier.fillMaxWidth().testTag("parameter-panel-toggle")) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ui_parameters_short), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        if (showParameters) Text(stringResource(R.string.ui_drag_to_adjust), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        Icon(if (showParameters) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.padding(start = 8.dp))
                    }
                }
                if (showParameters) {
                    fields.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { field ->
                                ParameterCell(
                                    field,
                                    Modifier.weight(1f).onGloballyPositioned { coordinates ->
                                        val top = coordinates.positionInWindow() - origin
                                        anchors[field.id] = Rect(top, androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                                    }.graphicsLayer { alpha = if (active == field.id || dragging == field.id) 0f else 1f },
                                    onAdjust = { ticks -> adjust(field.id, ticks) },
                                    onDragging = { dragging = if (it) field.id else null },
                                ) {
                                    focus.clearFocus()
                                    active = field.id
                                    editorOpen = true
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val selected = fields.firstOrNull { it.id == active }
        val source = anchors[active]
        if (selected != null && source != null) {
            ExpandedParameter(
                selected.label, selected.value, source, maxWidth, maxHeight,
                if (active == "seed" || active == "scheduler") 390.dp else 300.dp,
                editorOpen, ::finishEdit, { active = null },
            ) {
                when (active) {
                    "width" -> if (isDit || runOnCpu) {
                        NumberEditor(stringResource(R.string.ui_width), currentWidth.toFloat(), if (isDit) DIT_MIN_SIZE.toFloat()..DIT_MAX_SIZE.toFloat() else 128f..512f, if (isDit) DIT_SIZE_STEPS else 5, true, if (isDit) onDitWidthChange else onSizeChange, ::finishEdit)
                    } else {
                        ResolutionEditor(availableResolutions, onResolutionSelected, ::finishEdit)
                    }

                    "height" -> if (isDit) {
                        NumberEditor(stringResource(R.string.ui_height), currentHeight.toFloat(), DIT_MIN_SIZE.toFloat()..DIT_MAX_SIZE.toFloat(), DIT_SIZE_STEPS, true, onDitHeightChange, ::finishEdit)
                    } else {
                        ResolutionEditor(availableResolutions, onResolutionSelected, ::finishEdit)
                    }

                    "steps" -> NumberEditor(stringResource(R.string.ui_steps), steps, 1f..50f, 48, true, onStepsChange, ::finishEdit)

                    "cfg" -> NumberEditor("CFG", cfg, 1f..30f, 57, false, onCfgChange, ::finishEdit)

                    "batch" -> NumberEditor(stringResource(R.string.ui_batch), batchCounts.toFloat(), 1f..10f, 8, true, onBatchCountsChange, ::finishEdit)

                    "denoise" -> NumberEditor(stringResource(R.string.ui_denoise_short), denoiseStrength, 0f..1f, 99, false, onDenoiseStrengthChange, ::finishEdit)

                    "seed" -> SeedEditor(seed, returnedSeed, onSeedChange, onUseLastSeed, ::finishEdit)

                    "scheduler" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val karras = scheduler.endsWith("_karras")
                            listOf("dpm" to "DPM 2M", "dpm_sde" to "DPM SDE", "euler_a" to "Euler A", "euler" to "Euler", "lcm" to "LCM").forEach { (id, label) ->
                                ParameterChoice(label, scheduler.removeSuffix("_karras") == id, onClick = {
                                    onSchedulerChange(if (karras && id != "lcm") "${id}_karras" else id)
                                })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Karras", style = MaterialTheme.typography.labelLarge)
                            val base = scheduler.removeSuffix("_karras")
                            Switch(scheduler.endsWith("_karras"), { onSchedulerChange(if (it) "${base}_karras" else base) }, enabled = base != "lcm")
                        }
                    }

                    "runtime" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ParameterChoice("CPU", !useOpenCL, onClick = {
                            onCpuSelected()
                            finishEdit()
                        })
                        ParameterChoice("GPU", useOpenCL, onClick = {
                            onGpuSelected()
                            finishEdit()
                        })
                    }

                    "aspect" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("1:1", "3:4", "4:3").forEach { ratio ->
                            ParameterChoice(ratio, aspectRatio == ratio, onClick = {
                                onAspectRatioSelected(ratio)
                                finishEdit()
                            })
                        }
                        ParameterChoice(stringResource(R.string.ui_custom), false, onClick = {
                            onCustomAspectRatioClick()
                            finishEdit()
                        })
                    }
                }

                if (active !in listOf("width", "height", "steps", "cfg", "batch", "denoise", "seed")) {
                    Button(onClick = ::finishEdit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
        val lifted = fields.firstOrNull { it.id == dragging }
        val liftSource = anchors[dragging]
        if (lifted != null && liftSource != null) LiftedParameter(lifted.label, lifted.value, liftSource)
    }
}

@Composable
private fun PromptRegion(title: String, count: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (count.isNotBlank()) Text(count, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().weight(1f).testTag("positive-prompt-editor"), contentAlignment = Alignment.TopStart) { content() }
    }
}

private data class WorkspaceParameter(val id: String, val label: String, val value: String, val enabled: Boolean)

@Composable
private fun ParameterCell(field: WorkspaceParameter, modifier: Modifier, onAdjust: (Int) -> Unit, onDragging: (Boolean) -> Unit, onClick: () -> Unit) {
    val latestAdjust by rememberUpdatedState(onAdjust)
    val latestClick by rememberUpdatedState(onClick)
    val latestDragging by rememberUpdatedState(onDragging)
    val haptic = LocalHapticFeedback.current
    val tickPixels = with(LocalDensity.current) { 12.dp.toPx() }
    val gesture = if (field.enabled) {
        Modifier.pointerInput(field.id, tickPixels) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val release = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { true to waitForUpOrCancellation() }
                if (release != null) {
                    val up = release.second
                    if (up != null && (up.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                        up.consume()
                        latestClick()
                    }
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    latestDragging(true)
                    // Keep a single starting value for the gesture, even if several pointer
                    // events arrive before Compose has drawn the next frame.
                    val adjustFromStart = latestAdjust
                    var distance = 0f
                    var previousTicks = 0
                    try {
                        horizontalDrag(down.id) { change ->
                            distance += change.positionChange().x
                            val ticks = (distance / tickPixels).toInt()
                            if (ticks != previousTicks) {
                                adjustFromStart(ticks)
                                previousTicks = ticks
                            }
                            change.consume()
                        }
                    } finally {
                        latestDragging(false)
                    }
                }
            }
        }
    } else {
        Modifier
    }
    Card(
        modifier.then(gesture).testTag("parameter-${field.id}").semantics(mergeDescendants = true) {
            role = Role.Button
            if (field.enabled) {
                onClick {
                    latestClick()
                    true
                }
            } else {
                disabled()
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(field.value, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun NumberEditor(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, integer: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit) {
    var entry by rememberSaveable(label) { mutableStateOf(if (integer) value.roundToInt().toString() else "%.2f".format(java.util.Locale.ROOT, value)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ParameterNumberInput(label, entry, { next ->
            entry = next
            next.toFloatOrNull()?.takeIf { it.isFinite() }?.let { onChange(it.coerceIn(range)) }
        }, integer, onDone)
        Slider(value.coerceIn(range), { next ->
            entry = if (integer) next.roundToInt().toString() else "%.2f".format(java.util.Locale.ROOT, next)
            onChange(next)
        }, valueRange = range, steps = steps)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.confirm)) }
    }
}

@Composable
private fun ResolutionEditor(resolutions: List<Resolution>, onSelected: (Resolution) -> Unit, onDone: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        resolutions.forEach { r ->
            ParameterChoice("${r.width} × ${r.height}", false, onClick = {
                onSelected(r)
                onDone()
            })
        }
    }
}

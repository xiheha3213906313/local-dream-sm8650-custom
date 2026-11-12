package io.github.xororz.localdream.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.DitResolution
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
    aspectLocked: Boolean = false,
    onAspectLockChange: (Boolean) -> Unit = {},
    lockedRatio: Double? = null,
    onAspectPresetSelected: (Double) -> Unit = {},
    onAspectPresetFinished: () -> Unit = {},
    onResolutionAdjusting: (Boolean) -> Unit = {},
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
    positiveFocused: Boolean = false,
    keyboardVisibleOverride: Boolean? = null,
    positiveCount: String = "",
    negativeCount: String = "",
) {
    var active by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var dragPreview by remember { mutableStateOf<String?>(null) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var promptClosingRequested by remember { mutableStateOf(false) }
    var positiveExpanded by rememberSaveable { mutableStateOf(false) }
    var positiveBounds by remember { mutableStateOf(Rect.Zero) }
    var closingRequested by remember { mutableStateOf(false) }
    var ratioPicker by remember { mutableStateOf(false) }
    var ratioIndex by remember { mutableIntStateOf(3) }
    var dragging by remember { mutableStateOf<String?>(null) }
    val anchors = remember { mutableStateMapOf<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var parametersExpanded by rememberSaveable { mutableStateOf(true) }
    var negativeExpanded by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboardVisible = keyboardVisibleOverride ?: keyboardIsVisible()
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val ratioTick = with(density) { 32.dp.toPx() }
    val promptExpansion by animateFloatAsState(if (positiveExpanded) 1f else 0f, tween(220, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "positive-prompt-expansion")
    LaunchedEffect(positiveFocused) { if (positiveFocused) negativeExpanded = false }
    LaunchedEffect(closingRequested, keyboardVisible) {
        if (closingRequested && !keyboardVisible) {
            // Let the restored grid take part in layout before the card returns to it.
            withFrameNanos { }
            editorOpen = false
        }
    }
    LaunchedEffect(promptClosingRequested, keyboardVisible) {
        if (promptClosingRequested && !keyboardVisible) {
            withFrameNanos { }
            positiveExpanded = false
            promptClosingRequested = false
        }
    }
    fun collapsePrompt() {
        focus.clearFocus()
        keyboard?.hide()
        promptClosingRequested = true
    }
    fun finishEdit() {
        focus.clearFocus()
        keyboard?.hide()
        parametersExpanded = true
        closingRequested = true
    }
    BackHandler(enabled = active != null) { finishEdit() }
    BackHandler(enabled = positiveExpanded && active == null && !keyboardVisible) { collapsePrompt() }
    val linkedDescription = stringResource(R.string.ui_linked_adjust)
    fun ticks(value: Float, min: Float, max: Float, step: Float = 1f): IntRange = kotlin.math.floor((min - value) / step + .0001f).toInt().coerceAtMost(0)..kotlin.math.ceil((max - value) / step - .0001f).toInt().coerceAtLeast(0)
    fun dimensionTicks(value: Int, width: Boolean): IntRange {
        val ratio = lockedRatio?.takeIf { aspectLocked && it.isFinite() && it > 0 }
            ?: return ticks(value.toFloat(), DIT_MIN_SIZE.toFloat(), DIT_MAX_SIZE.toFloat(), DIT_SIZE_STEP.toFloat())
        val low = DitResolution.lockedSize(DIT_MIN_SIZE.toFloat(), ratio, width)
        val high = DitResolution.lockedSize(DIT_MAX_SIZE.toFloat(), ratio, width)
        return ticks(value.toFloat(), (if (width) low.first else low.second).toFloat(), (if (width) high.first else high.second).toFloat(), DIT_SIZE_STEP.toFloat())
    }
    fun adjustmentRange(id: String): IntRange {
        val resolutionIndex = availableResolutions.indexOfFirst { it.width == currentWidth && it.height == currentHeight }.coerceAtLeast(0)
        val resolutionRange = -resolutionIndex..(availableResolutions.lastIndex - resolutionIndex).coerceAtLeast(0)
        return when (id) {
            "width" -> when {
                isDit -> dimensionTicks(currentWidth, true)
                runOnCpu -> ticks(currentWidth.toFloat(), 128f, 512f, 64f)
                else -> resolutionRange
            }

            "height" -> if (isDit) dimensionTicks(currentHeight, false) else resolutionRange

            "steps" -> ticks(steps, 1f, 50f)

            "cfg" -> ticks(cfg, 1f, 30f, .5f)

            "batch" -> ticks(batchCounts.toFloat(), 1f, 10f)

            "denoise" -> ticks(denoiseStrength, 0f, 1f, .01f)

            "runtime" -> if (useOpenCL) -1..0 else 0..1

            "scheduler" -> listOf("dpm", "dpm_sde", "euler_a", "euler", "lcm").indexOf(scheduler.removeSuffix("_karras")).coerceAtLeast(0).let { -it..4 - it }

            "aspect" -> listOf("1:1", "3:4", "4:3").indexOf(aspectRatio).coerceAtLeast(0).let { -it..2 - it }

            "seed" -> {
                val start = (seed.toLongOrNull() ?: returnedSeed ?: 0L).coerceAtLeast(0L)
                -start.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()..(Long.MAX_VALUE - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }

            else -> 0..0
        }
    }
    val fields = mutableListOf<WorkspaceParameter>()
    fun field(id: String, label: String, value: String, enabled: Boolean = true) {
        fields += WorkspaceParameter(id, label, value, enabled, adjustmentRange(id))
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
        val showParameters = parametersExpanded && ((!compact && !promptFocused) || (active != null && !keyboardVisible))
        val fullPrompt = with(density) { Rect(16.dp.toPx(), 6.dp.toPx(), maxWidth.toPx() - 16.dp.toPx(), (maxHeight.toPx() - 6.dp.toPx()).coerceAtLeast(7.dp.toPx())) }
        Column(Modifier.fillMaxSize().zIndex(if (promptExpansion > 0f && active == null) 2f else 0f).padding(horizontal = 16.dp, vertical = 6.dp).testTag("generation-workspace"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (inputs != null) {
                AnimatedVisibility(
                    visible = !keyboardVisible || active != null,
                    enter = expandVertically(tween(160)) + fadeIn(tween(120)),
                    exit = shrinkVertically(tween(120)) + fadeOut(tween(90)),
                ) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).offset(y = (-2).dp).testTag("workspace-image-inputs")) { inputs(compact) }
                }
            }
            Box(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 192.dp).fillMaxHeight().zIndex(if (promptExpansion > 0f) 1f else 0f).onGloballyPositioned {
                    positiveBounds = Rect(it.positionInWindow() - origin, Size(it.size.width.toFloat(), it.size.height.toFloat()))
                }.testTag("positive-prompt-slot"),
            ) {
                // Keep the editor in normal layout. The surrounding animated sections
                // resize it and move the next heading in the same layout pass.
                Box(
                    Modifier.layout { measurable, constraints ->
                        val fraction = promptExpansion
                        val width = (constraints.maxWidth + (fullPrompt.width - constraints.maxWidth) * fraction).roundToInt().coerceAtLeast(1)
                        val height = (constraints.maxHeight + (fullPrompt.height - constraints.maxHeight) * fraction).roundToInt().coerceAtLeast(1)
                        val editor = measurable.measure(Constraints.fixed(width, height))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            editor.place(((fullPrompt.left - positiveBounds.left) * fraction).roundToInt(), ((fullPrompt.top - positiveBounds.top) * fraction).roundToInt())
                        }
                    }.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                        .testTag("positive-prompt-editor").semantics { if (positiveCount.isNotBlank()) stateDescription = positiveCount },
                ) {
                    positive()
                    IconButton(onClick = {
                        negativeExpanded = false
                        if (positiveExpanded) collapsePrompt() else positiveExpanded = true
                    }, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(36.dp).testTag("positive-prompt-expand")) {
                        Icon(if (positiveExpanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull, stringResource(if (positiveExpanded) R.string.ui_prompt_collapse else R.string.ui_prompt_expand), Modifier.size(20.dp))
                    }
                }
            }
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
                AnimatedVisibility(
                    visible = negativeExpanded,
                    enter = expandVertically(tween(180)) + fadeIn(tween(130)),
                    exit = shrinkVertically(tween(140)) + fadeOut(tween(100)),
                ) { Box(Modifier.fillMaxWidth().height(72.dp).testTag("negative-prompt-editor")) { negative() } }
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
                AnimatedVisibility(
                    visible = showParameters,
                    enter = expandVertically(tween(160)) + fadeIn(tween(130)),
                    exit = shrinkVertically(tween(120)) + fadeOut(tween(90)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        fields.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (row.first().id == "width" && isDit) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                row.forEachIndexed { index, field ->
                                    val linked = aspectLocked && ((dragging == "width" && field.id == "height") || (dragging == "height" && field.id == "width"))
                                    val linkedAlpha by animateFloatAsState(if (linked) 1f else 0f, tween(160), label = "linked-outline-${field.id}")
                                    ParameterCell(
                                        field,
                                        Modifier.weight(1f).border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = linkedAlpha)), RoundedCornerShape(24.dp)).then(
                                            if (linked) {
                                                Modifier.semantics { stateDescription = linkedDescription }
                                            } else {
                                                Modifier
                                            },
                                        ).onGloballyPositioned { coordinates ->
                                            val top = coordinates.positionInWindow() - origin
                                            anchors[field.id] = Rect(top, androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                                        }.graphicsLayer { alpha = if (active == field.id || dragPreview == field.id) 0f else 1f },
                                        onAdjust = { ticks -> adjust(field.id, ticks) },
                                        onDragging = {
                                            dragging = if (it) field.id else null
                                            if (field.id == "width" || field.id == "height") onResolutionAdjusting(it)
                                            if (it) {
                                                dragPreview = field.id
                                                dragDistance = 0f
                                            }
                                        },
                                        onDistance = { dragDistance = it },
                                    ) {
                                        focus.clearFocus()
                                        active = field.id
                                        closingRequested = false
                                        editorOpen = true
                                    }
                                    if (row.first().id == "width" && index == 0 && isDit) {
                                        val chooseRatio: (Int) -> Unit = { value ->
                                            val next = value.coerceIn(CommonAspectRatios.indices)
                                            if (next != ratioIndex) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                ratioIndex = next
                                                onAspectPresetSelected(CommonAspectRatios[next].second)
                                            }
                                        }
                                        var initialRatio by remember { mutableIntStateOf(3) }
                                        Box(
                                            Modifier.size(36.dp).onGloballyPositioned { anchors["aspect-lock"] = Rect(it.positionInWindow() - origin, Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                                                .scrubGesture("aspect-lock", !isRunning, { onAspectLockChange(!aspectLocked) }, {
                                                    ratioIndex = CommonAspectRatios.indices.minBy { kotlin.math.abs(CommonAspectRatios[it].second - (lockedRatio ?: currentWidth.toDouble() / currentHeight)) }
                                                    initialRatio = ratioIndex
                                                    ratioPicker = true
                                                    onResolutionAdjusting(true)
                                                    onAspectPresetSelected(CommonAspectRatios[ratioIndex].second)
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }, { distance -> chooseRatio(initialRatio + (distance / ratioTick).toInt()) }, {
                                                    if (ratioPicker) {
                                                        ratioPicker = false
                                                        onResolutionAdjusting(false)
                                                        onAspectPresetFinished()
                                                    }
                                                }, { -initialRatio * ratioTick..(CommonAspectRatios.lastIndex - initialRatio) * ratioTick })
                                                .testTag("aspect-ratio-lock").semantics {
                                                    role = Role.Checkbox
                                                    toggleableState = if (aspectLocked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                                                    if (!isRunning) {
                                                        onClick {
                                                            onAspectLockChange(!aspectLocked)
                                                            true
                                                        }
                                                        onLongClick {
                                                            ratioPicker = true
                                                            true
                                                        }
                                                    } else {
                                                        disabled()
                                                    }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) { Icon(if (aspectLocked) Icons.Default.Lock else Icons.Default.LockOpen, stringResource(if (aspectLocked) R.string.ui_unlock_aspect else R.string.ui_lock_aspect), Modifier.size(20.dp)) }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
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
                editorOpen, ::finishEdit, {
                    active = null
                    closingRequested = false
                },
            ) {
                when (active) {
                    "width" -> if (isDit || runOnCpu) {
                        NumberEditor(stringResource(R.string.ui_width), currentWidth.toFloat(), if (isDit) DIT_MIN_SIZE.toFloat()..DIT_MAX_SIZE.toFloat() else 128f..512f, if (isDit) DIT_SIZE_STEPS else 5, true, if (isDit) onDitWidthChange else onSizeChange, ::finishEdit, commitOnDone = true)
                    } else {
                        ResolutionEditor(availableResolutions, onResolutionSelected, ::finishEdit)
                    }

                    "height" -> if (isDit) {
                        NumberEditor(stringResource(R.string.ui_height), currentHeight.toFloat(), DIT_MIN_SIZE.toFloat()..DIT_MAX_SIZE.toFloat(), DIT_SIZE_STEPS, true, onDitHeightChange, ::finishEdit, commitOnDone = true)
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
        val lifted = fields.firstOrNull { it.id == dragPreview }
        val liftSource = anchors[dragPreview]
        if (lifted != null && liftSource != null) LiftedParameter(lifted.label, lifted.value, liftSource, dragging == dragPreview, dragDistance) { dragPreview = null }
        if (ratioPicker) {
            anchors["aspect-lock"]?.let { source ->
                AspectRatioPicker(source, maxWidth, ratioIndex) { index ->
                    ratioIndex = index
                    onAspectPresetSelected(CommonAspectRatios[index].second)
                    ratioPicker = false
                    onResolutionAdjusting(false)
                    onAspectPresetFinished()
                }
            }
        }
    }
}

private data class WorkspaceParameter(val id: String, val label: String, val value: String, val enabled: Boolean, val adjustmentRange: IntRange)

@Composable
private fun ParameterCell(field: WorkspaceParameter, modifier: Modifier, onAdjust: (Int) -> Unit, onDragging: (Boolean) -> Unit, onDistance: (Float) -> Unit, onClick: () -> Unit) {
    val latestClick by rememberUpdatedState(onClick)
    val haptic = LocalHapticFeedback.current
    val tickPixels = with(LocalDensity.current) { 12.dp.toPx() }
    var adjustFromStart by remember { mutableStateOf<(Int) -> Unit>({}) }
    var previousTicks by remember { mutableIntStateOf(0) }
    var tickBounds by remember { mutableStateOf(0..0) }
    val gesture = Modifier.scrubGesture(field.id, field.enabled, onClick, {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onDragging(true)
        adjustFromStart = onAdjust
        tickBounds = field.adjustmentRange
        previousTicks = 0
    }, { distance ->
        onDistance(distance)
        val ticks = -(distance / tickPixels).toInt()
        if (ticks != previousTicks) {
            adjustFromStart(ticks)
            previousTicks = ticks
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }, { onDragging(false) }, { -tickBounds.last.toFloat() * tickPixels..-tickBounds.first.toFloat() * tickPixels })
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
private fun NumberEditor(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, integer: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit, commitOnDone: Boolean = false) {
    var entry by rememberSaveable(label) { mutableStateOf(if (integer) value.roundToInt().toString() else "%.2f".format(java.util.Locale.ROOT, value)) }
    var draft by rememberSaveable(label) { mutableFloatStateOf(value) }
    val displayedValue = if (commitOnDone) draft else value
    fun update(next: Float) {
        if (commitOnDone) draft = next else onChange(next)
    }
    fun confirm() {
        if (commitOnDone) onChange(draft)
        onDone()
    }
    val haptic = LocalHapticFeedback.current
    fun tick(next: Float) = ((next.coerceIn(range) - range.start) / (range.endInclusive - range.start) * (steps + 1)).roundToInt()
    var lastTick by remember(label) { mutableIntStateOf(tick(displayedValue)) }
    LaunchedEffect(displayedValue) { lastTick = tick(displayedValue) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ParameterNumberInput(label, entry, { next ->
            entry = next
            next.toFloatOrNull()?.takeIf { it.isFinite() }?.let { update(it.coerceIn(range)) }
        }, integer, ::confirm)
        Slider(displayedValue.coerceIn(range), { next ->
            val nextTick = tick(next)
            if (nextTick != lastTick) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lastTick = nextTick
            }
            entry = if (integer) next.roundToInt().toString() else "%.2f".format(java.util.Locale.ROOT, next)
            update(next)
        }, valueRange = range, steps = steps)
        Button(onClick = ::confirm, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.confirm)) }
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

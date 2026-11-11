package io.github.xororz.localdream.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Resolution
import io.github.xororz.localdream.ui.design.*
import kotlin.math.roundToInt

/** Fixed viewport: prompt/editor above a compact parameter grid. No page scroll or parameter route. */
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
    onImportFromClipboard: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    modelName: String,
    description: String,
    positive: @Composable () -> Unit,
    negative: @Composable () -> Unit,
    inputs: (@Composable () -> Unit)? = null,
    preview: (@Composable () -> Unit)? = null,
    status: String? = null,
    positiveCount: String = "",
    negativeCount: String = "",
) {
    var active by rememberSaveable { mutableStateOf<String?>(null) }
    var parametersExpanded by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun finishEdit() {
        focus.clearFocus()
        active = null
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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp).testTag("generation-workspace"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The fixed upper region shows both prompts, or the selected parameter/image editor.
        Box(Modifier.fillMaxWidth().weight(1f).testTag("workspace-editor"), contentAlignment = Alignment.TopCenter) {
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

                "seed" -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val editorHeight = maxHeight
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            seed,
                            onSeedChange,
                            Modifier.fillMaxWidth().semantics { contentDescription = "Seed" },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            trailingIcon = { IconButton(onClick = ::finishEdit) { Icon(Icons.Default.Check, stringResource(R.string.confirm)) } },
                        )
                        if (editorHeight >= 140.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onSeedChange("") }) { Text(stringResource(R.string.ui_random)) }
                                if (returnedSeed != null) TextButton(onClick = onUseLastSeed) { Text(stringResource(R.string.ui_use_last_seed)) }
                            }
                        }
                    }
                }

                "scheduler" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val karras = scheduler.endsWith("_karras")
                        listOf("dpm" to "DPM 2M", "dpm_sde" to "DPM SDE", "euler_a" to "Euler A", "euler" to "Euler", "lcm" to "LCM").forEach { (id, label) ->
                            TextButton(onClick = {
                                onSchedulerChange(if (karras && id != "lcm") "${id}_karras" else id)
                                finishEdit()
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(label) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Karras", style = MaterialTheme.typography.labelLarge)
                        val base = scheduler.removeSuffix("_karras")
                        Switch(scheduler.endsWith("_karras"), { onSchedulerChange(if (it) "${base}_karras" else base) }, enabled = base != "lcm")
                    }
                }

                "runtime" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onCpuSelected()
                        finishEdit()
                    }) { Text("CPU") }
                    Button(onClick = {
                        onGpuSelected()
                        finishEdit()
                    }) { Text("GPU") }
                }

                "aspect" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1:1", "3:4", "4:3").forEach { ratio ->
                        TextButton(onClick = {
                            onAspectRatioSelected(ratio)
                            finishEdit()
                        }) { Text(ratio) }
                    }
                    TextButton(onClick = {
                        onCustomAspectRatioClick()
                        finishEdit()
                    }) { Text(stringResource(R.string.ui_custom)) }
                }

                "images" -> inputs?.invoke()

                "preview" -> preview?.invoke()

                else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromptRegion(stringResource(R.string.ui_positive_short), positiveCount, Modifier.weight(1f), positive)
                    PromptRegion(stringResource(R.string.ui_negative_short), negativeCount, Modifier.weight(1f), negative)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                focus.clearFocus()
                active = null
                parametersExpanded = !parametersExpanded
            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.ui_parameters_short))
                Icon(if (parametersExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.padding(start = 8.dp))
            }
            if (active != null) IconButton(onClick = ::finishEdit) { Icon(Icons.Default.Close, stringResource(R.string.ui_back_to_prompts)) }
            if (inputs != null) {
                IconButton(onClick = {
                    focus.clearFocus()
                    active = if (active == "images") null else "images"
                }) { Icon(Icons.Default.Image, stringResource(R.string.ui_inputs)) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (preview != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_preview_short)) }, onClick = {
                            menu = false
                            active = "preview"
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.import_from_clipboard)) }, onClick = {
                        menu = false
                        onImportFromClipboard()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = {
                        menu = false
                        onShare()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.reset)) }, onClick = {
                        menu = false
                        onReset()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ui_model_details)) }, onClick = {
                        menu = false
                        details = true
                    })
                }
            }
        }
        if (parametersExpanded) {
            fields.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { field ->
                        ParameterCell(field, selected = active == field.id, modifier = Modifier.weight(1f)) {
                            focus.clearFocus()
                            active = if (active == field.id) null else field.id
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(modelName, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = { Text(description, maxLines = 10, overflow = TextOverflow.Ellipsis) }, confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.confirm)) } })
}

@Composable
private fun PromptRegion(title: String, count: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (count.isNotBlank()) Text(count, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopStart) { content() }
    }
}

private data class WorkspaceParameter(val id: String, val label: String, val value: String, val enabled: Boolean)

@Composable
private fun ParameterCell(field: WorkspaceParameter, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = field.enabled, modifier = modifier.testTag("parameter-${field.id}"), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(field.value, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun NumberEditor(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, integer: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit) {
    var entry by rememberSaveable(label) { mutableStateOf(if (integer) value.roundToInt().toString() else "%.2f".format(java.util.Locale.ROOT, value)) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val editorHeight = maxHeight
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editorHeight >= 176.dp) Text(label, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                entry,
                { next ->
                    entry = next
                    next.toFloatOrNull()?.takeIf { it.isFinite() }?.let { onChange(it.coerceIn(range)) }
                },
                Modifier.fillMaxWidth().semantics { contentDescription = label },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
                trailingIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.Check, stringResource(R.string.confirm)) } },
            )
            if (editorHeight >= 148.dp) {
                Slider(value.coerceIn(range), { next ->
                    entry = if (integer) next.roundToInt().toString() else "%.2f".format(java.util.Locale.ROOT, next)
                    onChange(next)
                }, valueRange = range, steps = steps)
            }
        }
    }
}

@Composable
private fun ResolutionEditor(resolutions: List<Resolution>, onSelected: (Resolution) -> Unit, onDone: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        resolutions.forEach { r ->
            TextButton(onClick = {
                onSelected(r)
                onDone()
            }) { Text("${r.width} × ${r.height}") }
        }
    }
}

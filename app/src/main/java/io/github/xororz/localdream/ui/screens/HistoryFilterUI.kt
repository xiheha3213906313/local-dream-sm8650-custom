package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.DeviceFilter
import io.github.xororz.localdream.data.FavoriteFilter
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.HistoryFilter
import io.github.xororz.localdream.ui.design.Button
import io.github.xororz.localdream.ui.design.Checkbox
import io.github.xororz.localdream.ui.design.ChoiceControl
import io.github.xororz.localdream.ui.design.OutlinedButton
import io.github.xororz.localdream.ui.design.OutlinedTextField
import io.github.xororz.localdream.ui.design.Text
import io.github.xororz.localdream.ui.design.TextButton
import io.github.xororz.localdream.utils.schedulerDisplayName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryFilterBar(
    filter: HistoryFilter,
    currentModelId: String?,
    onShowFilterSheet: () -> Unit,
    onSetCurrentModelOnly: () -> Unit,
    onSetAllModels: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (currentModelId != null) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceControl(stringResource(R.string.history_filter_current_model_only), onSetCurrentModelOnly, Modifier.testTag("history-scope-current"), checked = filter.modelIds == setOf(currentModelId), showCheck = false)
                ChoiceControl(stringResource(R.string.history_filter_all_models), onSetAllModels, Modifier.testTag("history-scope-all"), checked = filter.modelIds == null, showCheck = false)
            }
        }
        ChoiceControl(stringResource(R.string.history_view_filter), onShowFilterSheet, Modifier.testTag("history-filter-button"), checked = filter.hasAdvancedFilters(), showCheck = false, icon = Icons.Default.FilterList)
    }
}

internal fun HistoryFilter.hasAdvancedFilters(): Boolean = modes != null ||
    from != null ||
    to != null ||
    sizes != null ||
    schedulers != null ||
    devices != null ||
    !promptSubstring.isNullOrBlank() ||
    !favorites.isNullOrEmpty() ||
    !descending

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryFilterSheet(
    initialFilter: HistoryFilter,
    knownModelIds: List<String>,
    knownSchedulers: List<String>,
    knownSizes: List<String>,
    onApply: (HistoryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf(initialFilter) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showSizePicker by remember { mutableStateOf(false) }
    var showSchedulerPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section(stringResource(R.string.history_filter_modes)) {
                ChipRow {
                    val modeOptions = listOf(
                        GenerationMode.TXT2IMG to "txt2img",
                        GenerationMode.IMG2IMG to "img2img",
                        GenerationMode.INPAINT to "inpaint",
                        GenerationMode.EDIT to "edit",
                        GenerationMode.ULTRAFIX to "ultrafix",
                    )
                    modeOptions.forEach { (mode, label) ->
                        val selected = draft.modes?.contains(mode) == true
                        ToneFilterChip(
                            selected = selected,
                            onClick = {
                                val current = draft.modes ?: emptySet()
                                val next = if (selected) current - mode else current + mode
                                draft = draft.copy(modes = next.ifEmpty { null })
                            },
                            label = label,
                        )
                    }
                }
            }

            Section(stringResource(R.string.history_filter_favorites)) {
                ChipRow {
                    val favoriteOptions = listOf(
                        FavoriteFilter.FAVORITE to
                            stringResource(R.string.history_filter_favorite),
                        FavoriteFilter.NOT_FAVORITE to
                            stringResource(R.string.history_filter_not_favorite),
                    )
                    favoriteOptions.forEach { (option, label) ->
                        val selected = draft.favorites?.contains(option) == true
                        ToneFilterChip(
                            selected = selected,
                            onClick = {
                                val current = draft.favorites ?: emptySet()
                                val next = if (selected) current - option else current + option
                                draft = draft.copy(favorites = next.ifEmpty { null })
                            },
                            label = label,
                        )
                    }
                }
            }

            if (knownModelIds.isNotEmpty() || knownSizes.isNotEmpty() || knownSchedulers.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (knownModelIds.isNotEmpty()) {
                        Section(
                            title = stringResource(R.string.history_filter_models),
                            modifier = Modifier,
                        ) {
                            ToneAssistChip(
                                onClick = { showModelPicker = true },
                                active = draft.modelIds != null,
                                label = summaryLabel(draft.modelIds, knownModelIds.size),
                            )
                        }
                    }
                    if (knownSizes.isNotEmpty()) {
                        Section(
                            title = stringResource(R.string.history_filter_size),
                            modifier = Modifier,
                        ) {
                            ToneAssistChip(
                                onClick = { showSizePicker = true },
                                active = draft.sizes != null,
                                label = summaryLabel(draft.sizes, knownSizes.size),
                            )
                        }
                    }
                    if (knownSchedulers.isNotEmpty()) {
                        Section(
                            title = stringResource(R.string.history_filter_scheduler),
                            modifier = Modifier,
                        ) {
                            ToneAssistChip(
                                onClick = { showSchedulerPicker = true },
                                active = draft.schedulers != null,
                                label = summaryLabel(draft.schedulers, knownSchedulers.size),
                            )
                        }
                    }
                }
            }

            Section(stringResource(R.string.history_filter_time_range)) {
                val df = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
                val label = if (draft.from != null || draft.to != null) {
                    val a = draft.from?.let { df.format(Date(it)) } ?: "—"
                    val b = draft.to?.let { df.format(Date(it)) } ?: "—"
                    "$a — $b"
                } else {
                    stringResource(R.string.history_filter_time_any)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToneAssistChip(
                        onClick = { showDatePicker = true },
                        active = draft.from != null || draft.to != null,
                        label = label,
                    )
                    if (draft.from != null || draft.to != null) {
                        TextButton(
                            onClick = { draft = draft.copy(from = null, to = null) },
                            contentPadding = PaddingValues(
                                horizontal = 8.dp,
                            ),
                        ) {
                            Text(stringResource(R.string.history_filter_reset))
                        }
                    }
                }
            }

            Section(stringResource(R.string.history_filter_device)) {
                ChipRow {
                    listOf(
                        DeviceFilter.NPU to "NPU",
                        DeviceFilter.CPU to "CPU",
                        DeviceFilter.GPU to "GPU",
                    ).forEach { (device, label) ->
                        val selected = draft.devices?.contains(device) == true
                        ToneFilterChip(
                            selected = selected,
                            onClick = {
                                val current = draft.devices ?: emptySet()
                                val next = if (selected) current - device else current + device
                                draft = draft.copy(devices = next.ifEmpty { null })
                            },
                            label = label,
                        )
                    }
                }
            }

            Section(stringResource(R.string.history_filter_sort)) {
                ChipRow {
                    ToneFilterChip(draft.descending, { draft = draft.copy(descending = true) }, stringResource(R.string.history_filter_sort_desc))
                    ToneFilterChip(!draft.descending, { draft = draft.copy(descending = false) }, stringResource(R.string.history_filter_sort_asc))
                }
            }

            Section(stringResource(R.string.history_filter_prompt_search)) {
                OutlinedTextField(
                    value = draft.promptSubstring ?: "",
                    onValueChange = { draft = draft.copy(promptSubstring = it.ifBlank { null }) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        // Reset preserves modelIds: the top-level chips own that field.
                        draft = HistoryFilter(modelIds = draft.modelIds)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.history_filter_reset))
                }
                Button(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onApply(draft)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.history_filter_apply))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDatePicker) {
        DateRangeDialog(
            initialFrom = draft.from,
            initialTo = draft.to,
            onDismiss = { showDatePicker = false },
            onConfirm = { from, to ->
                draft = draft.copy(from = from, to = to)
                showDatePicker = false
            },
        )
    }

    if (showModelPicker) {
        MultiSelectDialog(
            title = stringResource(R.string.history_filter_models),
            allItems = knownModelIds,
            initiallySelected = draft.modelIds ?: knownModelIds.toSet(),
            onDismiss = { showModelPicker = false },
            onConfirm = { selected ->
                draft = draft.copy(
                    modelIds = if (selected.size == knownModelIds.size) null else selected.ifEmpty { null },
                )
                showModelPicker = false
            },
        )
    }

    if (showSizePicker) {
        MultiSelectDialog(
            title = stringResource(R.string.history_filter_size),
            allItems = knownSizes,
            initiallySelected = draft.sizes ?: knownSizes.toSet(),
            onDismiss = { showSizePicker = false },
            onConfirm = { selected ->
                draft = draft.copy(
                    sizes = if (selected.size == knownSizes.size) null else selected.ifEmpty { null },
                )
                showSizePicker = false
            },
        )
    }

    if (showSchedulerPicker) {
        MultiSelectDialog(
            title = stringResource(R.string.history_filter_scheduler),
            allItems = knownSchedulers,
            initiallySelected = draft.schedulers ?: knownSchedulers.toSet(),
            itemLabel = { schedulerDisplayName(it) },
            onDismiss = { showSchedulerPicker = false },
            onConfirm = { selected ->
                draft = draft.copy(
                    schedulers = if (selected.size == knownSchedulers.size) null else selected.ifEmpty { null },
                )
                showSchedulerPicker = false
            },
        )
    }
}

@Composable
private fun summaryLabel(selected: Set<String>?, total: Int): String {
    val count = selected?.size
    return when {
        selected == null || count == 0 -> stringResource(R.string.history_filter_all)
        else -> "$count / $total"
    }
}

@Composable
private fun MultiSelectDialog(
    title: String,
    allItems: List<String>,
    initiallySelected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    itemLabel: (String) -> String = { it },
) {
    var selected by remember { mutableStateOf(initiallySelected) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.heightIn(max = 560.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val allSelected = selected.size == allItems.size && allItems.isNotEmpty()
                    TextButton(
                        onClick = {
                            selected = if (allSelected) emptySet() else allItems.toSet()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(
                            stringResource(
                                if (allSelected) {
                                    R.string.history_filter_reset
                                } else {
                                    R.string.history_filter_all
                                },
                            ),
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(allItems, key = { it }) { item ->
                        val isChecked = item in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (isChecked) selected - item else selected + item
                                }
                                .padding(start = 12.dp, end = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = {
                                    selected = if (isChecked) selected - item else selected + item
                                },
                            )
                            Text(
                                text = itemLabel(item),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = { onConfirm(selected) }) {
                        Text(stringResource(R.string.history_filter_apply))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    initialFrom: Long?,
    initialTo: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long?, Long?) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFrom,
        initialSelectedEndDateMillis = initialTo,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val toEod = state.selectedEndDateMillis?.plus(86_400_000L - 1)
                onConfirm(state.selectedStartDateMillis, toEod)
            }) {
                Text(stringResource(R.string.history_filter_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    ) {
        DateRangePicker(
            state = state,
            title = {
                DateRangePickerDefaults.DateRangePickerTitle(
                    displayMode = state.displayMode,
                    modifier = Modifier.padding(
                        start = 64.dp,
                        end = 12.dp,
                        top = 16.dp,
                        bottom = 12.dp,
                    ),
                )
            },
            dateFormatter = DatePickerDefaults.dateFormatter(selectedDateSkeleton = "yMd"),
        )
    }
}

@Suppress("ModifierParameter") // Intentional non-Modifier default: groups in a Row override it.
@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun ToneFilterChip(selected: Boolean, onClick: () -> Unit, label: String) {
    ChoiceControl(label, onClick, checked = selected)
}

@Composable
private fun ToneAssistChip(onClick: () -> Unit, active: Boolean = false, label: String) {
    ChoiceControl(label, onClick, checked = active, showCheck = false)
}

package io.github.xororz.localdream.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.*
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.ui.design.*
import io.github.xororz.localdream.ui.design.Text
import kotlinx.coroutines.*

internal data class ImportFileSummary(val name: String, val size: String)
private data class SelectedDitFile(val uri: Uri, val name: String, val size: Long)

internal fun importKindLabel(kind: DitImportKind): String = when (kind) {
    DitImportKind.KLEIN -> "FLUX.2 Klein / FLUX.3"
    DitImportKind.ZIMAGE -> "Z-Image Turbo"
    DitImportKind.QWEN21 -> "Qwen Image 2.1"
    DitImportKind.KREA2 -> "Krea 2 Turbo"
}

@Composable
internal fun importComponentLabel(component: DitImportComponent): String = stringResource(
    when (component) {
        DitImportComponent.DIFFUSION -> R.string.dit_import_diffusion
        DitImportComponent.TEXT_ENCODER -> R.string.dit_import_text_encoder
        DitImportComponent.VAE -> R.string.dit_import_vae
        DitImportComponent.TOKENIZER -> R.string.dit_import_tokenizer
        DitImportComponent.VISION -> R.string.dit_import_vision
    },
)

/** A real navigation destination: family → files → name. No dialog-sized form. */
@Composable
fun DitImportScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableStateOf(DitImportKind.KLEIN) }
    var name by rememberSaveable { mutableStateOf("") }
    val files = rememberSaveable(
        saver = mapSaver<SnapshotStateMap<DitImportComponent, SelectedDitFile>>(
            save = { state -> state.entries.associate { (role, file) -> role.name to arrayListOf(file.uri.toString(), file.name, file.size.toString()) } },
            restore = { saved ->
                mutableStateMapOf<DitImportComponent, SelectedDitFile>().apply {
                    saved.forEach { (role, value) ->
                        val parts = value as List<*>
                        put(DitImportComponent.valueOf(role), SelectedDitFile(Uri.parse(parts[0] as String), parts[1] as String, (parts[2] as String).toLong()))
                    }
                }
            },
        ),
    ) { mutableStateMapOf<DitImportComponent, SelectedDitFile>() }
    var picking by rememberSaveable { mutableStateOf<DitImportComponent?>(null) }
    var importing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var copied by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(-1L) }
    var error by remember { mutableStateOf<String?>(null) }
    val required = DitModelImport.requiredComponents(kind)
    val id = runCatching { DitModelImport.modelId(name) }.getOrNull()
    val root = ModelStorage.modelsDir(context)
    val reserved = id != null && ModelRepository.isReservedModelId(id, ModelStorage.ignoresCase(context))
    val duplicate = id != null && root.listFiles().orEmpty().any { it.name.equals(id, ModelStorage.ignoresCase(context)) }
    val canImport = id != null && !reserved && !duplicate && required.all { it in files }
    val back = {
        when {
            importing -> job?.cancel()

            step > 0 -> {
                step--
                error = null
            }

            else -> navController.popBackStackIfResumed()
        }
        Unit
    }
    BackHandler(onBack = back)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val component = picking
        if (uri != null && component != null) {
            scope.launch {
                try {
                    val selected = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        var displayName = uri.lastPathSegment.orEmpty()
                        var size = -1L
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (nameColumn >= 0) displayName = cursor.getString(nameColumn).orEmpty()
                                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it > 0 } ?: -1L
                            }
                        }
                        SelectedDitFile(uri, displayName, size)
                    }
                    files[component] = selected
                    error = null
                    if (name.isBlank() && component == DitImportComponent.DIFFUSION) {
                        name = selected.name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}_.-]"), "_").take(100)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message
                }
            }
        }
    }
    fun startImport() {
        job = scope.launch {
            if (!ModelStorage.beginDitImport()) {
                error = context.getString(R.string.dit_import_storage_busy)
                return@launch
            }
            importing = true
            error = null
            try {
                val sources = files.mapValues { (_, selected) ->
                    DitImportSource(selected.size) {
                        context.contentResolver.openInputStream(selected.uri) ?: throw IllegalStateException("Cannot open ${selected.name}")
                    }
                }
                withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    DitModelImport.importModel(
                        root,
                        name,
                        kind,
                        sources,
                        ignoreCase = ModelStorage.ignoresCase(context),
                        isReserved = { ModelRepository.isReservedModelId(it, ModelStorage.ignoresCase(context)) },
                        checkCancelled = { coroutine.ensureActive() },
                        onProgress = { done, size ->
                            copied = done
                            total = size
                        },
                    )
                }
                ModelRepository.getInstance(context).refreshAllModels()
                navController.popBackStackIfResumed()
            } catch (e: CancellationException) {
                error = context.getString(R.string.cancel)
            } catch (e: Exception) {
                error = context.getString(R.string.dit_import_failed, e.message.orEmpty())
            } finally {
                ModelStorage.endDitImport()
                importing = false
            }
        }
    }
    DreamPage(
        title = stringResource(R.string.ui_import_title),
        onBack = back,
        bottomBar = {
            ActionDock {
                if (importing) {
                    if (total > 0) {
                        LinearProgressIndicator(progress = { (copied.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(stringResource(R.string.dit_import_copying, Formatter.formatFileSize(context, copied)))
                    TextButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
                } else {
                    Button(
                        onClick = {
                            if (step < 2) {
                                step++
                                error = null
                            } else {
                                startImport()
                            }
                        },
                        enabled = when (step) {
                            1 -> required.all { it in files }
                            2 -> canImport
                            else -> true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(if (step < 2) R.string.ui_next else R.string.add_model)) }
                }
            }
        },
    ) { padding ->
        PageColumn(padding) {
            Text(stringResource(R.string.ui_import_step, step + 1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when (step) {
                0 -> ImportFamilyContent(kind, onSelect = {
                    if (it != kind) {
                        files.clear()
                        name = ""
                        kind = it
                    }
                })

                1 -> ImportFilesContent(kind, files.mapValues { (_, file) -> ImportFileSummary(file.name, if (file.size > 0) Formatter.formatFileSize(context, file.size) else "") }) {
                    picking = it
                    picker.launch(arrayOf("*/*"))
                }

                else -> {
                    PageIntro(stringResource(R.string.ui_import_details), stringResource(R.string.ui_import_name_hint))
                    Section {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !importing,
                            label = { Text(stringResource(R.string.custom_model_name)) },
                            singleLine = true,
                            isError = reserved || duplicate || (name.isNotBlank() && id == null),
                            supportingText = {
                                when {
                                    reserved -> Text(stringResource(R.string.custom_model_id_reserved))
                                    duplicate -> Text(stringResource(R.string.rename_name_exists))
                                }
                            },
                        )
                        Text(importKindLabel(kind), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.ui_import_ready), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        required.forEach { component ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(importComponentLabel(component), style = MaterialTheme.typography.labelLarge)
                                Text(files[component]?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            error?.let { Section { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
internal fun ImportFamilyContent(selected: DitImportKind, onSelect: (DitImportKind) -> Unit) {
    PageIntro(stringResource(R.string.ui_import_family), stringResource(R.string.ui_import_family_hint))
    DitImportKind.entries.forEach { option ->
        Card(onClick = { onSelect(option) }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(importKindLabel(option), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                RadioButton(selected = selected == option, onClick = { onSelect(option) })
            }
        }
    }
}

@Composable
internal fun ImportFilesContent(kind: DitImportKind, files: Map<DitImportComponent, ImportFileSummary>, onPick: (DitImportComponent) -> Unit) {
    PageIntro(stringResource(R.string.ui_import_files), importKindLabel(kind))
    if (kind.needsVision) Text(stringResource(R.string.ui_import_hint_vision), color = MaterialTheme.colorScheme.primary)
    DitModelImport.requiredComponents(kind).forEach { component ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(importComponentLabel(component), style = MaterialTheme.typography.titleMedium)
            val file = files[component]
            if (file != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge)
                        if (file.size.isNotBlank()) Text(file.size, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedButton(onClick = { onPick(component) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (file == null) R.string.ui_file_empty else R.string.ui_file_replace))
            }
        }
    }
}

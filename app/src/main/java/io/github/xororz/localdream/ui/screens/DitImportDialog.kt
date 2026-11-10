package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.DitEngine
import io.github.xororz.localdream.data.DitImportComponent
import io.github.xororz.localdream.data.DitImportKind
import io.github.xororz.localdream.data.DitImportSource
import io.github.xororz.localdream.data.DitModelImport
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.data.ModelStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SelectedDitFile(val uri: Uri, val name: String, val size: Long)

@Composable
private fun componentLabel(component: DitImportComponent): String = stringResource(
    when (component) {
        DitImportComponent.DIFFUSION -> R.string.dit_import_diffusion
        DitImportComponent.TEXT_ENCODER -> R.string.dit_import_text_encoder
        DitImportComponent.VAE -> R.string.dit_import_vae
        DitImportComponent.TOKENIZER -> R.string.dit_import_tokenizer
        DitImportComponent.VISION -> R.string.dit_import_vision
    },
)

private fun kindLabel(kind: DitImportKind): String = when (kind) {
    DitImportKind.KLEIN -> "FLUX.2 Klein / FLUX.3"
    DitImportKind.ZIMAGE -> "Z-Image Turbo"
    DitImportKind.QWEN21 -> "Qwen Image 2.1"
    DitImportKind.KREA2 -> "Krea 2 Turbo"
}

/** Select roles explicitly so downloaded weights keep their original names on the phone. */
@Composable
fun DitImportDialog(context: Context, onDismiss: () -> Unit, onImported: () -> Unit) {
    val scope = rememberCoroutineScope()
    val kinds = remember { DitImportKind.entries.toList() }
    var kind by remember { mutableStateOf(kinds.first()) }
    var name by remember { mutableStateOf("") }
    val files = remember { mutableStateMapOf<DitImportComponent, SelectedDitFile>() }
    var picking by remember { mutableStateOf<DitImportComponent?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var copied by remember { mutableLongStateOf(0) }
    var total by remember { mutableLongStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    val required = DitModelImport.requiredComponents(kind)
    val id = runCatching { DitModelImport.modelId(name) }.getOrNull()
    val reserved = id != null && ModelRepository.isReservedModelId(id, ModelStorage.ignoresCase(context))
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val component = picking
        if (uri != null && component != null) {
            scope.launch {
                try {
                    val selected = withContext(Dispatchers.IO) {
                        var displayName = uri.lastPathSegment.orEmpty()
                        var size = -1L
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (nameColumn >= 0) displayName = cursor.getString(nameColumn).orEmpty()
                                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it > 0 } ?: -1
                            }
                        }
                        SelectedDitFile(uri, displayName, size)
                    }
                    files[component] = selected
                    error = null
                    if (name.isBlank() && component == DitImportComponent.DIFFUSION) {
                        name = selected.name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}_.-]"), "_")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (importing) job?.cancel() else onDismiss() },
        title = { Text(stringResource(R.string.dit_import_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.dit_import_hint))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !importing,
                    singleLine = true,
                    label = { Text(stringResource(R.string.custom_model_name)) },
                    isError = reserved || (name.isNotBlank() && id == null),
                    supportingText = if (reserved) {
                        { Text(stringResource(R.string.custom_model_id_reserved)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    OutlinedButton(onClick = { expanded = true }, enabled = !importing && kinds.size > 1) {
                        Text(kindLabel(kind))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        kinds.forEach { option ->
                            DropdownMenuItem(text = { Text(kindLabel(option)) }, onClick = {
                                kind = option
                                expanded = false
                                error = null
                            })
                        }
                    }
                }
                required.forEach { component ->
                    OutlinedButton(
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            picking = component
                            picker.launch(arrayOf("*/*"))
                        },
                    ) {
                        Column {
                            Text(componentLabel(component))
                            files[component]?.let { Text(it.name, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (importing) {
                    if (total > 0) {
                        LinearProgressIndicator(progress = { (copied.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(stringResource(R.string.dit_import_copying, Formatter.formatFileSize(context, copied)))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !importing && id != null && !reserved && required.all { it in files },
                onClick = {
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
                                    root = ModelStorage.modelsDir(context),
                                    name = name,
                                    kind = kind,
                                    sources = sources,
                                    ignoreCase = ModelStorage.ignoresCase(context),
                                    isReserved = { ModelRepository.isReservedModelId(it, ModelStorage.ignoresCase(context)) },
                                    checkCancelled = { coroutine.ensureActive() },
                                    onProgress = { done, size ->
                                        copied = done
                                        total = size
                                    },
                                )
                            }
                            onImported()
                            onDismiss()
                        } catch (e: CancellationException) {
                            onDismiss()
                            throw e
                        } catch (e: Exception) {
                            error = context.getString(R.string.dit_import_failed, e.message.orEmpty())
                        } finally {
                            ModelStorage.endDitImport()
                            importing = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.add_model)) }
        },
        dismissButton = {
            TextButton(onClick = { if (importing) job?.cancel() else onDismiss() }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

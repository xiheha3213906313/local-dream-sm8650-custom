package io.github.xororz.localdream.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.data.ModelStorage
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.ui.design.*
import kotlinx.coroutines.launch

/** Checkpoint conversion and QNN archive import have their own navigation destination. */
@Composable
fun LegacyImportScreen(navController: NavController, archive: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var uriText by rememberSaveable { mutableStateOf<String?>(null) }
    var fileName by rememberSaveable { mutableStateOf("") }
    var clipSkip by rememberSaveable { mutableIntStateOf(1) }
    var loras by remember { mutableStateOf<List<LoRAFile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val modelId = name.replace(" ", "")
    val root = ModelStorage.modelsDir(context)
    val invalid = modelId.isBlank() || modelId in listOf(".", "..") || modelId.any { it == '/' || it == '\\' || it.isISOControl() }
    val reserved = ModelRepository.isReservedModelId(modelId, ModelStorage.ignoresCase(context))
    val exists = root.listFiles().orEmpty().any { it.name.equals(modelId, ModelStorage.ignoresCase(context)) }
    val back = {
        if (!busy) navController.popBackStackIfResumed()
        Unit
    }
    BackHandler(onBack = back)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            uriText = selected.toString()
            fileName = getFileNameFromUri(context, selected) ?: selected.lastPathSegment.orEmpty()
            if (name.isBlank()) name = fileName.substringBeforeLast('.').substringBefore("_qnn")
        }
    }
    val loraPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (loras.none { it.uri == selected }) loras = loras + LoRAFile(selected)
        }
    }
    fun startImport() {
        if (busy) return
        scope.launch {
            if (!ModelStorage.beginDitImport()) {
                error = context.getString(R.string.dit_import_storage_busy)
                return@launch
            }
            busy = true
            error = null
            var success = false
            try {
                val update: (String) -> Unit = { progress = it }
                val failure: (String) -> Unit = { error = it }
                if (archive) {
                    extractNpuModel(context, name, Uri.parse(uriText!!), update, { _, _, _ -> }, {}, { success = true }, failure)
                } else {
                    convertCustomModel(context, name, Uri.parse(uriText!!), clipSkip, loras, update, {}, { success = true }, failure)
                }
                if (success) {
                    ModelRepository.getInstance(context).refreshAllModels()
                    navController.popBackStackIfResumed()
                }
            } finally {
                busy = false
                ModelStorage.endDitImport()
            }
        }
    }
    DreamPage(
        title = stringResource(R.string.ui_import_title),
        onBack = back,
        bottomBar = {
            ActionDock {
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(progress, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                } else {
                    Button(onClick = ::startImport, enabled = !invalid && !reserved && !exists && uriText != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_model)) }
                }
            }
        },
    ) { padding ->
        PageColumn(padding) {
            PageIntro(if (archive) "QNN" else "MNN", stringResource(if (archive) R.string.custom_npu_model_hint else R.string.custom_model_hint))
            Section(title = stringResource(R.string.ui_import_files)) {
                if (fileName.isNotBlank()) Text(fileName)
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (uriText == null) R.string.ui_file_empty else R.string.ui_file_replace)) }
            }
            Section(title = stringResource(R.string.ui_import_details)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.custom_model_name)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), isError = name.isNotBlank() && (invalid || reserved || exists))
                if (reserved) Text(stringResource(R.string.custom_model_id_reserved), color = MaterialTheme.colorScheme.error)
                if (exists) Text(stringResource(R.string.rename_name_exists), color = MaterialTheme.colorScheme.error)
                if (!archive) {
                    Text(stringResource(R.string.clip_skip_hint), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2).forEach { value -> ChoiceControl("Clip Skip $value", { clipSkip = value }, checked = clipSkip == value, enabled = !busy) }
                    }
                }
            }
            if (!archive) {
                Section(title = stringResource(R.string.lora_files_optional)) {
                    OutlinedButton(onClick = { loraPicker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_lora_file)) }
                    loras.forEachIndexed { index, lora ->
                        Text(getFileNameFromUri(context, lora.uri) ?: lora.uri.lastPathSegment.orEmpty())
                        Text("${stringResource(R.string.lora_weight)} · ${"%.2f".format(lora.weight)}")
                        Slider(lora.weight, { weight -> loras = loras.mapIndexed { i, f -> if (i == index) f.copy(weight = weight) else f } }, enabled = !busy, valueRange = 0f..2f, steps = 39)
                        TextButton(onClick = { loras = loras.filterIndexed { i, _ -> i != index } }, enabled = !busy) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

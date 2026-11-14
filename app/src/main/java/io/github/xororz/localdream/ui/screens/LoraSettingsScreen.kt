package io.github.xororz.localdream.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.data.LoraRepository
import io.github.xororz.localdream.data.LoraSelection
import io.github.xororz.localdream.ui.design.*
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun LoraSettingsScreen(modelId: String, modelName: String, initial: List<LoraSelection>, requiresPackage: Boolean, onClose: () -> Unit, onApplied: (List<LoraSelection>) -> Unit) {
    val context = LocalContext.current
    val repository = remember { LoraRepository.get(context) }
    val assets by repository.assets.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var selected by remember { mutableStateOf(initial) }
    var replaceIndex by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun choose(id: String, name: String) {
        if (selected.any { it.id == id }) {
            Toast.makeText(context, "这个 LoRA 已在组合中", Toast.LENGTH_SHORT).show()
        } else if (replaceIndex != null) {
            selected = selected.mapIndexed { index, old -> if (index == replaceIndex) LoraSelection(id, name) else old }
            replaceIndex = null
        } else if (selected.size < LoraRepository.MAX_COUNT) {
            selected = selected + LoraSelection(id, name)
        } else {
            error = "最多配置 3 个 LoRA，请先移除或选择替换"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                error = null
                try {
                    val asset = repository.importFile(uri)
                    if (selected.size < LoraRepository.MAX_COUNT || replaceIndex != null) choose(asset.id, asset.name)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "导入失败"
                } finally {
                    busy = false
                }
            }
        }
    }
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                error = null
                try {
                    io.github.xororz.localdream.data.LoraRuntime.importPackage(context, modelId, uri)
                    Toast.makeText(context, "LoRA 兼容包已安装", Toast.LENGTH_SHORT).show()
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    error = failure.message ?: "无法安装 LoRA 兼容包"
                } finally {
                    busy = false
                }
            }
        }
    }
    BackHandler { if (!busy) onClose() }
    Scaffold(
        topBar = { DreamToolbar("LoRA", onBack = { if (!busy) onClose() }) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                Button(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            repository.validate(selected)
                            repository.save(modelId, selected)
                            onApplied(selected)
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            error = failure.message ?: "无法保存 LoRA 设置"
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在处理…" else "应用") }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(modelName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("当前组合 ${selected.size} / 3", style = MaterialTheme.typography.labelMedium)
            }
            items(selected, key = { "slot-${it.id}" }) { entry ->
                var weightText by remember(entry.id) { mutableStateOf("%.2f".format(java.util.Locale.ROOT, entry.weight)) }
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Switch(entry.enabled, { enabled -> selected = selected.map { if (it.id == entry.id) it.copy(enabled = enabled) else it } }, enabled = !busy)
                            IconButton(onClick = {
                                selected = selected.filterNot { it.id == entry.id }
                                replaceIndex = null
                            }, enabled = !busy) { Icon(Icons.Default.Close, "移除") }
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { replaceIndex = selected.indexOf(entry) }, enabled = !busy) { Text("替换") }
                            ParameterNumberInput("强度", weightText, { next ->
                                weightText = next
                                next.toFloatOrNull()?.takeIf { it.isFinite() && it in -2f..2f }?.let { value ->
                                    selected = selected.map { if (it.id == entry.id) it.copy(weight = value) else it }
                                }
                            }, false, {})
                        }
                        Slider(entry.weight, { next ->
                            weightText = "%.2f".format(java.util.Locale.ROOT, next)
                            if ((next * 20).roundToInt() != (entry.weight * 20).roundToInt()) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selected = selected.map { if (it.id == entry.id) it.copy(weight = next) else it }
                        }, valueRange = -2f..2f, steps = 79, enabled = !busy)
                    }
                }
            }
            item {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入 LoRA") }
                if (requiresPackage) OutlinedButton(onClick = { packagePicker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入 LoRA 兼容包") }
                Text(if (replaceIndex != null) "选择文件以替换第 ${replaceIndex!! + 1} 个 LoRA" else "文件库", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
            }
            if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            items(assets, key = { "asset-${it.id}" }) { asset ->
                Card(Modifier.fillMaxWidth().clickable(enabled = !busy) { choose(asset.id, asset.name) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(asset.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (selected.any { it.id == asset.id }) "已添加" else "选择", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

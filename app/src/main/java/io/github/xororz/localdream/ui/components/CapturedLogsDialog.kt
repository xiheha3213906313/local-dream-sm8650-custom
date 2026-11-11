package io.github.xororz.localdream.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.ui.design.Text
import io.github.xororz.localdream.ui.design.TextButton
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CapturedLogsDialog(logs: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember(logs) { mutableStateOf(false) }
    var status by remember(logs) { mutableStateOf<String?>(null) }
    var error by remember(logs) { mutableStateOf(false) }
    val savedMessage = stringResource(R.string.log_export_saved)
    val failedMessage = stringResource(R.string.log_save_failed)
    val cancelledMessage = stringResource(R.string.log_export_cancelled)
    val copiedMessage = stringResource(R.string.log_copied)
    val copyFailedMessage = stringResource(R.string.log_copy_failed)
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) {
            saving = false
            error = false
            status = cancelledMessage
        } else {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: throw IOException("Could not open the selected document")
                        output.use { it.write(logs.toByteArray(Charsets.UTF_8)) }
                    }
                    error = false
                    status = savedMessage
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = true
                    status = "$failedMessage: ${e.localizedMessage ?: e.javaClass.simpleName}"
                } finally {
                    saving = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.captured_logs_title)) },
        text = {
            Column {
                if (logs.isBlank()) {
                    Text(stringResource(R.string.no_logs_captured))
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)
                            .padding(8.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = logs,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Local Dream log", logs))
                                error = false
                                status = copiedMessage
                            } catch (_: Exception) {
                                error = true
                                status = copyFailedMessage
                            }
                        },
                    ) { Text(stringResource(R.string.log_copy)) }
                }
                if (saving || status != null) {
                    Text(
                        text = if (saving) stringResource(R.string.log_export_saving) else status.orEmpty(),
                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && logs.isNotBlank(),
                onClick = {
                    saving = true
                    error = false
                    status = null
                    try {
                        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                        saveLog.launch("local_dream_log_$timestamp.txt")
                    } catch (e: Exception) {
                        saving = false
                        error = true
                        status = "$failedMessage: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

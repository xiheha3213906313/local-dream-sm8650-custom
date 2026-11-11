package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.ui.design.*

/** The icon and popup share an anchor; none of these actions resize the editor. */
@Composable
internal fun GenerationToolbarMenu(
    modelName: String,
    description: String,
    onImport: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    preview: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    Box(Modifier.testTag("generation-menu-anchor")) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("generation-menu-button")) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.testTag("generation-menu-popup")) {
            if (preview != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_preview_short)) }, onClick = {
                    expanded = false
                    showPreview = true
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.import_from_clipboard)) }, onClick = {
                expanded = false
                onImport()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = {
                expanded = false
                onShare()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.reset)) }, onClick = {
                expanded = false
                onReset()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_model_details)) }, onClick = {
                expanded = false
                details = true
            })
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(modelName) }, text = { Text(description, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton({ details = false }) { Text(stringResource(R.string.confirm)) } })
    if (showPreview && preview != null) AlertDialog(onDismissRequest = { showPreview = false }, text = { preview() }, confirmButton = { TextButton({ showPreview = false }) { Text(stringResource(R.string.confirm)) } })
}

@Composable
internal fun ImageInputActions(onBase: () -> Unit, onReference: (() -> Unit)?, baseEnabled: Boolean = true, referenceEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageInputIcon(androidx.compose.material.icons.Icons.Outlined.AddPhotoAlternate, stringResource(R.string.ui_base_short), "image-input-base", baseEnabled, onBase)
        if (onReference != null) ImageInputIcon(androidx.compose.material.icons.Icons.Outlined.Collections, stringResource(R.string.ui_reference_short), "image-input-reference", referenceEnabled, onReference)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ImageInputIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall) } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag)) { Icon(icon, label, Modifier.size(24.dp)) }
    }
}

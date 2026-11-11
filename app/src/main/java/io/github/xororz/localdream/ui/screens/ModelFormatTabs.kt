package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.ui.design.Card
import io.github.xororz.localdream.ui.design.Text

@Composable
internal fun ModelFormatTabs(selected: Int, onSelected: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("model-format-tabs"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("MNN", "QNN", "DiT").forEachIndexed { index, label ->
            Card(onClick = { onSelected(index) }, modifier = Modifier.weight(1f).testTag("model-format-$index"), colors = CardDefaults.cardColors(containerColor = if (selected == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

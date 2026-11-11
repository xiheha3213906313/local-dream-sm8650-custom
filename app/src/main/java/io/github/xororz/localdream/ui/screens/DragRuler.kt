package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor

/** Moving marks taper towards the ends, retaining half of their normal height at the edge. */
@Composable
internal fun DragRuler(distance: Float, modifier: Modifier = Modifier) {
    val normal = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(36.dp).testTag("drag-ruler")) {
        val spacing = 8.dp.toPx()
        val whole = floor(distance / spacing).toInt()
        val phase = distance - whole * spacing
        val count = (size.width / spacing).toInt() + 2
        for (i in -count..count) {
            val x = size.width / 2 + i * spacing + phase
            if (x !in 0f..size.width) continue
            val taper = .5f + .5f * (1f - abs(x - size.width / 2) / (size.width / 2)).coerceIn(0f, 1f)
            val bar = (if ((i - whole) % 5 == 0) 22.dp.toPx() else 14.dp.toPx()) * taper
            drawLine(if (abs(x - size.width / 2) < spacing / 2) accent else normal, Offset(x, (size.height - bar) / 2), Offset(x, (size.height + bar) / 2), 1.5.dp.toPx(), StrokeCap.Round)
        }
    }
}

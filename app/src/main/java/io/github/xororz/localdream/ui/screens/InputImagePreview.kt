package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.xororz.localdream.R
import io.github.xororz.localdream.ui.components.OverlayIconButton
import io.github.xororz.localdream.ui.components.ZoomableImageOverlay
import io.github.xororz.localdream.ui.design.Card

@Composable
internal fun PreviewableInputCard(bitmap: Bitmap?, label: String, onPreview: (Bitmap) -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(onClick = { bitmap?.let(onPreview) }, enabled = bitmap != null, modifier = modifier.semantics { contentDescription = label }, content = content)
}

@Composable
internal fun InputImagePreview(bitmap: Bitmap, onDismiss: () -> Unit) {
    Box(Modifier.testTag("input-image-preview")) {
        ZoomableImageOverlay(bitmap, onDismiss, showScaleIndicator = true, topEndContent = {
            OverlayIconButton(Icons.Default.Close, stringResource(R.string.close), onDismiss)
        })
    }
}

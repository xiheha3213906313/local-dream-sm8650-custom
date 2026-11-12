package io.github.xororz.localdream.ui.screens

import android.graphics.Rect as AndroidRect
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.ui.design.ChoiceControl
import io.github.xororz.localdream.ui.design.IconButton
import io.github.xororz.localdream.ui.design.Scaffold
import io.github.xororz.localdream.ui.design.Text
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun Rect.pixelRect() = AndroidRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
private fun AndroidRect.frameRect() = Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CropImageScreen(
    imageUri: Uri,
    policy: CropOutputPolicy,
    initialCrop: AndroidRect?,
    onCropComplete: (CropSelection) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var preview by remember(imageUri) { mutableStateOf<CropPreview?>(null) }
    var error by remember(imageUri) { mutableStateOf<String?>(null) }
    var ratio by remember(imageUri) { mutableStateOf<Double?>(policy.current.ratio) }
    var selection by remember(imageUri) { mutableStateOf<Rect?>(null) }
    val msgError = stringResource(R.string.error_with_detail)
    LaunchedEffect(imageUri) {
        try {
            val loaded = withContext(Dispatchers.IO) { loadCropPreview(context, imageUri) }
            val area = initialCrop ?: AndroidRect(loaded.sourceWidth / 10, loaded.sourceHeight / 10, loaded.sourceWidth * 9 / 10, loaded.sourceHeight * 9 / 10)
            selection = fitImageCrop(area, loaded.sourceWidth, loaded.sourceHeight, policy.current.ratio).frameRect()
            preview = loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = msgError.format(e.message.orEmpty())
        }
    }
    val source = preview
    val frame = selection ?: Rect.Zero
    val frameRatio = if (frame.height > 0) frame.width.toDouble() / frame.height else policy.current.ratio
    val output = remember(policy, ratio, if (ratio == null) frameRatio else 0.0) { policy.choose(ratio ?: frameRatio, exact = ratio != null) }
    fun chooseRatio(value: Double?) {
        if (ratio == value) return
        // Free mode only unlocks the existing frame; it never creates a second frame.
        if (value != null && source != null && selection != null) {
            val size = policy.choose(value, exact = true)
            selection = fitImageCrop(frame.pixelRect(), source.sourceWidth, source.sourceHeight, size.ratio).frameRect()
        }
        ratio = value
    }
    BackHandler(onBack = onCancel)
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crop_image), maxLines = 1) },
                navigationIcon = { IconButton(onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(android.R.string.cancel)) } },
                actions = {
                    IconButton(onClick = {
                        source?.let { loaded ->
                            val snapped = fitImageCrop(frame.pixelRect(), loaded.sourceWidth, loaded.sourceHeight, output.ratio)
                            onCropComplete(CropSelection(imageUri, loaded.sourceWidth, loaded.sourceHeight, snapped, output))
                        }
                    }, enabled = source != null && frame.width > 0 && error == null) { Icon(Icons.Default.Check, stringResource(R.string.confirm)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { ChoiceControl(stringResource(R.string.ui_crop_free), { chooseRatio(null) }, checked = ratio == null, enabled = source != null, height = 36.dp) }
                    items(CommonAspectRatios) { (label, value) ->
                        ChoiceControl(label, { chooseRatio(value) }, checked = ratio?.let { abs(ln(it / value)) < .001 } == true, enabled = source != null && policy.supports(value), height = 36.dp)
                    }
                }
                Text("${output.width} × ${output.height}", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (source != null) {
                    CropFrameCanvas(
                        preview = source,
                        frame = frame,
                        fixedRatio = ratio != null,
                        onFrameChange = { selection = it },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 36.dp),
                    )
                } else if (error == null) {
                    ContainedLoadingIndicator()
                }
                Text(error ?: stringResource(R.string.crop_hint), Modifier.align(Alignment.BottomCenter).padding(16.dp), color = if (error == null) Color.White.copy(alpha = .75f) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

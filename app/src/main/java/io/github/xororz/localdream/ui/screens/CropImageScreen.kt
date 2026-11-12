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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.ui.design.ChoiceControl
import io.github.xororz.localdream.ui.design.IconButton
import io.github.xororz.localdream.ui.design.Scaffold
import io.github.xororz.localdream.ui.design.Text
import io.moyuru.cropify.Cropify
import io.moyuru.cropify.CropifyOption
import io.moyuru.cropify.CropifySize
import io.moyuru.cropify.CropifyState
import io.moyuru.cropify.rememberCropifyState
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The pinned crop library exposes its geometry only as internal mutable state. */
private class CropFrameAccess(private val state: CropifyState) {
    @Suppress("UNCHECKED_CAST")
    private fun geometry(name: String) = state.javaClass.getDeclaredField(name + "\$delegate").apply { isAccessible = true }.get(state) as MutableState<Rect>
    private val frameState = geometry("frameRect")
    private val imageState = geometry("imageRect")
    var frame: Rect
        get() = frameState.value
        set(value) {
            frameState.value = value
        }
    val image get() = imageState.value

    fun sourceRect(preview: CropPreview): AndroidRect? {
        if (image.width <= 0 || image.height <= 0 || frame.width <= 0 || frame.height <= 0) return null
        val left = ((frame.left - image.left) * preview.sourceWidth / image.width).roundToInt().coerceIn(0, preview.sourceWidth - 1)
        val top = ((frame.top - image.top) * preview.sourceHeight / image.height).roundToInt().coerceIn(0, preview.sourceHeight - 1)
        val right = ((frame.right - image.left) * preview.sourceWidth / image.width).roundToInt().coerceIn(left + 1, preview.sourceWidth)
        val bottom = ((frame.bottom - image.top) * preview.sourceHeight / image.height).roundToInt().coerceIn(top + 1, preview.sourceHeight)
        return AndroidRect(left, top, right, bottom)
    }

    fun restore(rect: AndroidRect, preview: CropPreview) {
        frame = Rect(
            image.left + rect.left.toFloat() * image.width / preview.sourceWidth,
            image.top + rect.top.toFloat() * image.height / preview.sourceHeight,
            image.left + rect.right.toFloat() * image.width / preview.sourceWidth,
            image.top + rect.bottom.toFloat() * image.height / preview.sourceHeight,
        )
    }
}

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
    val cropState = rememberCropifyState()
    val access = remember(cropState) { CropFrameAccess(cropState) }
    var preview by remember(imageUri) { mutableStateOf<CropPreview?>(null) }
    var error by remember(imageUri) { mutableStateOf<String?>(null) }
    var ratio by remember(imageUri) { mutableStateOf<Double?>(policy.current.ratio) }
    var anchor by remember(imageUri) { mutableStateOf(initialCrop?.let(::AndroidRect)) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val msgError = stringResource(R.string.error_with_detail)
    LaunchedEffect(imageUri) {
        try {
            preview = withContext(Dispatchers.IO) { loadCropPreview(context, imageUri) }
        } catch (e: Exception) {
            error = msgError.format(e.message.orEmpty())
        }
    }
    val source = preview
    val frame = access.frame
    val frameRatio = if (frame.height > 0) frame.width.toDouble() / frame.height else policy.current.ratio
    val output = remember(policy, ratio, if (ratio == null) frameRatio else 0.0) { policy.choose(ratio ?: frameRatio, exact = ratio != null) }
    fun chooseRatio(value: Double?) {
        source?.let { access.sourceRect(it) }?.let { anchor = it }
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
                            access.sourceRect(loaded)?.let { selected ->
                                val snapped = fitImageCrop(selected, loaded.sourceWidth, loaded.sourceHeight, output.ratio)
                                onCropComplete(CropSelection(imageUri, loaded.sourceWidth, loaded.sourceHeight, snapped, output))
                            }
                        }
                    }, enabled = source != null && frame.width > 0 && error == null) { Icon(Icons.Default.Check, stringResource(R.string.confirm)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { ChoiceControl(stringResource(R.string.ui_crop_free), { chooseRatio(null) }, checked = ratio == null, height = 36.dp) }
                    items(CommonAspectRatios) { (label, value) ->
                        ChoiceControl(label, { chooseRatio(value) }, checked = ratio?.let { abs(ln(it / value)) < .001 } == true, enabled = policy.supports(value), height = 36.dp)
                    }
                }
                Text("${output.width} × ${output.height}", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { viewport = it }, contentAlignment = Alignment.Center) {
                if (source != null) {
                    val bitmap = remember(source.bitmap) { source.bitmap.asImageBitmap() }
                    Cropify(
                        bitmap = bitmap,
                        state = cropState,
                        onImageCropped = {},
                        option = CropifyOption(
                            frameSize = ratio?.let { CropifySize.FixedAspectRatio((1.0 / output.ratio).toFloat()) } ?: CropifySize.PercentageSize(.8f),
                            frameColor = MaterialTheme.colorScheme.primary,
                            gridColor = MaterialTheme.colorScheme.primary.copy(alpha = .6f),
                        ),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 36.dp),
                    )
                    // Run after Cropify's layout effect, which initially centres its frame.
                    LaunchedEffect(bitmap, ratio, viewport) {
                        snapshotFlow { access.image }.first { it.width > 0 && it.height > 0 }
                        withFrameNanos { }
                        val area = anchor ?: AndroidRect(source.sourceWidth / 10, source.sourceHeight / 10, source.sourceWidth * 9 / 10, source.sourceHeight * 9 / 10)
                        access.restore(if (ratio == null) area else fitImageCrop(area, source.sourceWidth, source.sourceHeight, output.ratio), source)
                    }
                } else if (error == null) {
                    ContainedLoadingIndicator()
                }
                Text(error ?: stringResource(R.string.crop_hint), Modifier.align(Alignment.BottomCenter).padding(16.dp), color = if (error == null) Color.White.copy(alpha = .75f) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

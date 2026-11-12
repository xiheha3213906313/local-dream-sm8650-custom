package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import io.github.xororz.localdream.data.DitResolution
import io.github.xororz.localdream.data.Resolution
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class CropOutputSize(val width: Int, val height: Int) {
    val ratio get() = width.toDouble() / height
}

internal class CropOutputPolicy(
    private val sizes: List<CropOutputSize>,
    val current: CropOutputSize,
    private val fixedCanvas: Boolean,
) {
    fun supports(ratio: Double): Boolean = fixedCanvas || sizes.any { abs(ln(it.ratio / ratio)) < .015 }

    fun choose(ratio: Double, exact: Boolean = false): CropOutputSize {
        val desired = ratio.coerceIn(.25, 4.0)
        if (fixedCanvas) {
            val side = max(current.width, current.height)
            return if (desired >= 1) {
                CropOutputSize(side, ((side / desired).roundToInt() / 8 * 8).coerceAtLeast(8))
            } else {
                CropOutputSize(((side * desired).roundToInt() / 8 * 8).coerceAtLeast(8), side)
            }
        }
        val area = current.width.toDouble() * current.height
        if (exact) {
            val closest = sizes.minOf { abs(ln(it.ratio / desired)) }
            return sizes.filter { abs(ln(it.ratio / desired)) <= closest + 1e-7 }
                .minBy { abs(ln(it.width.toDouble() * it.height / area)) }
        }
        return sizes.minBy {
            5 * abs(ln(it.ratio / desired)) + .22 * abs(ln(it.width.toDouble() * it.height / area))
        }
    }

    companion object {
        fun forModel(dit: Boolean, cpu: Boolean, fixedCanvas: Boolean, resolutions: List<Resolution>, width: Int, height: Int): CropOutputPolicy {
            val sizes = when {
                dit -> (DitResolution.MIN_SIZE..DitResolution.MAX_SIZE step DitResolution.SIZE_STEP).flatMap { w ->
                    (DitResolution.MIN_SIZE..DitResolution.MAX_SIZE step DitResolution.SIZE_STEP).map { h -> CropOutputSize(w, h) }
                }

                resolutions.isNotEmpty() && !fixedCanvas -> resolutions.map { CropOutputSize(it.width, it.height) }

                cpu && !fixedCanvas -> (128..512 step 64).map { CropOutputSize(it, it) }

                else -> listOf(CropOutputSize(width, height))
            }
            return CropOutputPolicy(sizes, CropOutputSize(width, height), fixedCanvas)
        }
    }
}

internal data class CropSelection(val uri: Uri, val sourceWidth: Int, val sourceHeight: Int, val rect: Rect, val output: CropOutputSize)
internal data class CropPreview(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int)

/** Coordinates consistently refer to the EXIF-oriented source, including inpaint exports. */
internal fun loadCropPreview(context: Context, uri: Uri): CropPreview {
    var sourceWidth = 0
    var sourceHeight = 0
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        sourceWidth = info.size.width
        sourceHeight = info.size.height
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val scale = min(1.0, 1536.0 / max(sourceWidth, sourceHeight))
        decoder.setTargetSize((sourceWidth * scale).roundToInt().coerceAtLeast(1), (sourceHeight * scale).roundToInt().coerceAtLeast(1))
    }
    return CropPreview(bitmap, sourceWidth, sourceHeight)
}

/** Preserve the chosen centre and area, clamping as a pair rather than recentering the image. */
internal fun fitImageCrop(anchor: Rect, imageWidth: Int, imageHeight: Int, ratio: Double): Rect {
    val left = anchor.left.coerceIn(0, imageWidth - 1)
    val top = anchor.top.coerceIn(0, imageHeight - 1)
    val right = anchor.right.coerceIn(left + 1, imageWidth)
    val bottom = anchor.bottom.coerceIn(top + 1, imageHeight)
    if (abs((right - left).toDouble() / (bottom - top) - ratio) < 1e-5) return Rect(left, top, right, bottom)
    var w = sqrt((right - left).toDouble() * (bottom - top) * ratio)
    var h = w / ratio
    val scale = min(1.0, min(imageWidth / w, imageHeight / h))
    w *= scale
    h *= scale
    val iw = w.roundToInt().coerceIn(1, imageWidth)
    val ih = h.roundToInt().coerceIn(1, imageHeight)
    val x = ((left + right) / 2.0 - iw / 2.0).roundToInt().coerceIn(0, imageWidth - iw)
    val y = ((top + bottom) / 2.0 - ih / 2.0).roundToInt().coerceIn(0, imageHeight - ih)
    return Rect(x, y, x + iw, y + ih)
}

internal fun decodeImageCrop(context: Context, selection: CropSelection): Bitmap {
    val rect = selection.rect
    val out = selection.output
    val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, selection.uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val scale = min(1.0, max(out.width.toDouble() / rect.width(), out.height.toDouble() / rect.height()))
        val w = (info.size.width * scale).roundToInt().coerceAtLeast(1)
        val h = (info.size.height * scale).roundToInt().coerceAtLeast(1)
        decoder.setTargetSize(w, h)
        val sx = w.toDouble() / selection.sourceWidth
        val sy = h.toDouble() / selection.sourceHeight
        val left = floor(rect.left * sx).toInt().coerceIn(0, w - 1)
        val top = floor(rect.top * sy).toInt().coerceIn(0, h - 1)
        decoder.crop = Rect(left, top, ceil(rect.right * sx).toInt().coerceIn(left + 1, w), ceil(rect.bottom * sy).toInt().coerceIn(top + 1, h))
    }
    if (decoded.width == out.width && decoded.height == out.height) return decoded
    val scaled = Bitmap.createScaledBitmap(decoded, out.width, out.height, true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

/** Move painted pixels with their source coordinates when the crop or output size changes. */
internal fun remapCropLayer(layer: Bitmap?, oldCrop: Rect?, newCrop: Rect, width: Int, height: Int, mask: Boolean = false): Bitmap? {
    if (layer == null || oldCrop == null || layer.isRecycled) return null
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    if (mask) canvas.drawColor(android.graphics.Color.BLACK)
    val sx = oldCrop.width().toFloat() * width / (newCrop.width().toFloat() * layer.width)
    val sy = oldCrop.height().toFloat() * height / (newCrop.height().toFloat() * layer.height)
    val tx = (oldCrop.left - newCrop.left).toFloat() * width / newCrop.width()
    val ty = (oldCrop.top - newCrop.top).toFloat() * height / newCrop.height()
    val matrix = Matrix().apply { setValues(floatArrayOf(sx, 0f, tx, 0f, sy, ty, 0f, 0f, 1f)) }
    canvas.drawBitmap(layer, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    return result
}

package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class CropCorner(val x: Int, val y: Int) { TopLeft(-1, -1), TopRight(1, -1), BottomLeft(-1, 1), BottomRight(1, 1) }

/** One source-space frame drives painting and gestures; ratio changes never reset it. */
@Composable
internal fun CropFrameCanvas(preview: CropPreview, frame: Rect, fixedRatio: Boolean, onFrameChange: (Rect) -> Unit, modifier: Modifier = Modifier) {
    val bitmap = remember(preview.bitmap) { preview.bitmap.asImageBitmap() }
    val currentFrame by rememberUpdatedState(frame)
    val changeFrame by rememberUpdatedState(onFrameChange)
    val primary = MaterialTheme.colorScheme.primary
    val tolerance = with(LocalDensity.current) { 24.dp.toPx() }
    val width = preview.sourceWidth.toFloat()
    val height = preview.sourceHeight.toFloat()
    fun imageBounds(viewport: Size): Rect {
        val scale = min(viewport.width / bitmap.width, viewport.height / bitmap.height)
        val imageSize = Size(bitmap.width * scale, bitmap.height * scale)
        return Rect(Offset((viewport.width - imageSize.width) / 2, (viewport.height - imageSize.height) / 2), imageSize)
    }
    Canvas(
        modifier.pointerInput(preview, fixedRatio) {
            var corner: CropCorner? = null
            var dragging = false
            var gestureFrame = Rect.Zero
            detectDragGestures(
                onDragStart = { position ->
                    val image = imageBounds(Size(size.width.toFloat(), size.height.toFloat()))
                    val sx = (image.width / width).coerceAtLeast(.0001f)
                    val sy = (image.height / height).coerceAtLeast(.0001f)
                    val source = Offset((position.x - image.left) / sx, (position.y - image.top) / sy)
                    val area = constrainCropFrame(currentFrame, width, height)
                    gestureFrame = area
                    corner = CropCorner.entries.minByOrNull {
                        val point = Offset(if (it.x < 0) area.left else area.right, if (it.y < 0) area.top else area.bottom)
                        Offset((point.x - source.x) * sx, (point.y - source.y) * sy).getDistance()
                    }?.takeIf {
                        val point = Offset(if (it.x < 0) area.left else area.right, if (it.y < 0) area.top else area.bottom)
                        Offset((point.x - source.x) * sx, (point.y - source.y) * sy).getDistance() <= tolerance
                    }
                    dragging = corner != null || area.contains(source)
                },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            ) { change, delta ->
                if (dragging) {
                    val image = imageBounds(Size(size.width.toFloat(), size.height.toFloat()))
                    val sx = (image.width / width).coerceAtLeast(.0001f)
                    val sy = (image.height / height).coerceAtLeast(.0001f)
                    val sourceDelta = Offset(delta.x / sx, delta.y / sy)
                    gestureFrame = constrainCropFrame(moveCropFrame(gestureFrame, sourceDelta, corner, fixedRatio, width, height, tolerance * 2 / min(sx, sy)), width, height)
                    changeFrame(gestureFrame)
                    change.consume()
                }
            }
        },
    ) {
        val image = imageBounds(size)
        val bounded = constrainCropFrame(frame, width, height)
        val sx = image.width / width
        val sy = image.height / height
        val area = Rect(image.left + bounded.left * sx, image.top + bounded.top * sy, image.left + bounded.right * sx, image.top + bounded.bottom * sy)
        drawImage(bitmap, dstOffset = IntOffset(image.left.roundToInt(), image.top.roundToInt()), dstSize = IntSize(image.width.roundToInt().coerceAtLeast(1), image.height.roundToInt().coerceAtLeast(1)))
        clipRect(image.left, image.top, image.right, image.bottom) {
            val mask = Color.Black.copy(alpha = .55f)
            drawRect(mask, image.topLeft, Size(image.width, (area.top - image.top).coerceAtLeast(0f)))
            drawRect(mask, Offset(image.left, area.bottom), Size(image.width, (image.bottom - area.bottom).coerceAtLeast(0f)))
            drawRect(mask, Offset(image.left, area.top), Size((area.left - image.left).coerceAtLeast(0f), area.height))
            drawRect(mask, Offset(area.right, area.top), Size((image.right - area.right).coerceAtLeast(0f), area.height))
            drawRect(primary, area.topLeft, area.size, style = Stroke(1.dp.toPx()))
            for (part in 1..2) {
                val x = area.left + area.width * part / 3
                val y = area.top + area.height * part / 3
                drawLine(primary.copy(alpha = .3f), Offset(x, area.top), Offset(x, area.bottom), 1.dp.toPx())
                drawLine(primary.copy(alpha = .3f), Offset(area.left, y), Offset(area.right, y), 1.dp.toPx())
            }
            val length = min(12.dp.toPx(), min(area.width, area.height) / 4)
            val inset = min(4.dp.toPx(), length / 2)
            CropCorner.entries.forEach { corner ->
                val point = Offset(if (corner.x < 0) area.left + inset else area.right - inset, if (corner.y < 0) area.top + inset else area.bottom - inset)
                drawLine(primary, point, point + Offset(-corner.x * length, 0f), 2.dp.toPx())
                drawLine(primary, point, point + Offset(0f, -corner.y * length), 2.dp.toPx())
            }
        }
    }
}

/** Preserve size/ratio when possible, then translate the frame wholly inside the source. */
private fun constrainCropFrame(frame: Rect, width: Float, height: Float): Rect {
    if (!frame.width.isFinite() || !frame.height.isFinite() || frame.width <= 0 || frame.height <= 0) return Rect(0f, 0f, width, height)
    val factor = min(1f, min(width / frame.width, height / frame.height))
    val w = (frame.width * factor).coerceIn(.001f, width)
    val h = (frame.height * factor).coerceIn(.001f, height)
    val left = frame.left.takeIf { it.isFinite() }?.coerceIn(0f, width - w) ?: 0f
    val top = frame.top.takeIf { it.isFinite() }?.coerceIn(0f, height - h) ?: 0f
    return Rect(left, top, (left + w).coerceAtMost(width), (top + h).coerceAtMost(height))
}

private fun moveCropFrame(frame: Rect, delta: Offset, corner: CropCorner?, fixedRatio: Boolean, width: Float, height: Float, minimum: Float): Rect {
    if (corner == null) {
        val left = (frame.left + delta.x).coerceIn(0f, (width - frame.width).coerceAtLeast(0f))
        val top = (frame.top + delta.y).coerceIn(0f, (height - frame.height).coerceAtLeast(0f))
        return Rect(Offset(left, top), frame.size)
    }
    if (!fixedRatio) {
        val minWidth = min(minimum, frame.width)
        val minHeight = min(minimum, frame.height)
        return Rect(
            if (corner.x < 0) (frame.left + delta.x).coerceIn(0f, frame.right - minWidth) else frame.left,
            if (corner.y < 0) (frame.top + delta.y).coerceIn(0f, frame.bottom - minHeight) else frame.top,
            if (corner.x > 0) (frame.right + delta.x).coerceIn(frame.left + minWidth, width) else frame.right,
            if (corner.y > 0) (frame.bottom + delta.y).coerceIn(frame.top + minHeight, height) else frame.bottom,
        )
    }
    val ratio = frame.width / frame.height
    val anchorX = if (corner.x < 0) frame.right else frame.left
    val anchorY = if (corner.y < 0) frame.bottom else frame.top
    val availableX = if (corner.x < 0) anchorX else width - anchorX
    val availableY = if (corner.y < 0) anchorY else height - anchorY
    val maxWidth = min(availableX, availableY * ratio)
    val minWidth = min(maxWidth, max(minimum, minimum * ratio))
    val change = (corner.x * delta.x + corner.y * delta.y / ratio) / (1 + 1 / (ratio * ratio))
    val w = (frame.width + change).coerceIn(minWidth, maxWidth)
    val h = w / ratio
    return Rect(Offset(if (corner.x < 0) anchorX - w else anchorX, if (corner.y < 0) anchorY - h else anchorY), Size(w, h))
}

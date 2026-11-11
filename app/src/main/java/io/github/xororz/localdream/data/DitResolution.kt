package io.github.xororz.localdream.data

import kotlin.math.roundToInt

/**
 * Resolutions offered for DiT models. The models themselves only need multiples
 * of 16; the 64-pixel step keeps the slider usable while covering the common
 * aspect ratios.
 */
object DitResolution {
    const val MIN_SIZE = 512
    const val MAX_SIZE = 2048
    const val SIZE_STEP = 64
    const val SLIDER_STEPS = (MAX_SIZE - MIN_SIZE) / SIZE_STEP - 1

    fun snap(value: Float): Int = ((value / SIZE_STEP).roundToInt() * SIZE_STEP)
        .coerceIn(MIN_SIZE, MAX_SIZE)

    fun snap(value: Int): Int = snap(value.toFloat())

    /** Keep the captured ratio stable; clamp the pair together, then align to supported sizes. */
    fun lockedSize(value: Float, widthToHeight: Double, editingWidth: Boolean): Pair<Int, Int> {
        require(widthToHeight.isFinite() && widthToHeight > 0)
        val ratio = if (editingWidth) widthToHeight else 1.0 / widthToHeight
        val minPrimary = maxOf(MIN_SIZE.toDouble(), MIN_SIZE * ratio)
        val maxPrimary = minOf(MAX_SIZE.toDouble(), MAX_SIZE * ratio)
        val primary = value.toDouble().coerceIn(minPrimary, maxPrimary)
        val secondary = primary / ratio
        val pair = snap(primary.toFloat()) to snap(secondary.toFloat())
        return if (editingWidth) pair else pair.second to pair.first
    }

    fun isSupported(value: Int): Boolean = value in MIN_SIZE..MAX_SIZE &&
        (value - MIN_SIZE) % SIZE_STEP == 0
}

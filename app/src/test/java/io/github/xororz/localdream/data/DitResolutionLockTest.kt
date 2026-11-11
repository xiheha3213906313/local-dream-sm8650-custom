package io.github.xororz.localdream.data

import org.junit.Assert.*
import org.junit.Test

class DitResolutionLockTest {
    @Test fun portraitDimensionsStayLinkedInBothDirections() {
        assertEquals(768 to 1152, DitResolution.lockedSize(768f, 2.0 / 3.0, true))
        assertEquals(1024 to 1536, DitResolution.lockedSize(1536f, 2.0 / 3.0, false))
    }

    @Test fun reachingAResolutionLimitClampsThePairTogether() {
        assertEquals(1024 to 2048, DitResolution.lockedSize(2048f, .5, true))
        assertEquals(2048 to 512, DitResolution.lockedSize(512f, 4.0, false))
        assertEquals(512 to 1024, DitResolution.lockedSize(512f, .5, false))
    }

    @Test fun commonAspectRatiosAlwaysYieldSupportedModelSizes() {
        for (ratio in listOf(.25, .5, 2.0 / 3, 1.0, 4.0 / 3, 16.0 / 9, 4.0)) {
            for (value in 512..2048 step 64) {
                for (editingWidth in listOf(true, false)) {
                    val (width, height) = DitResolution.lockedSize(value.toFloat(), ratio, editingWidth)
                    assertTrue(DitResolution.isSupported(width))
                    assertTrue(DitResolution.isSupported(height))
                    assertTrue(kotlin.math.abs(width / height.toDouble() - ratio) / ratio < .08)
                }
            }
        }
    }
}

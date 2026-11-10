package io.github.xororz.localdream.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DitDeviceTest {
    @Test
    fun experimentalProfileRequiresExplicitBuildAndExactSoc() {
        assertTrue(DitEngine.isExperimentalSoc("SM8650", 31, true))
        assertTrue(DitEngine.isExperimentalSoc("sm8650", 36, true))
        assertFalse(DitEngine.isExperimentalSoc("SM8650", 30, true))
        assertFalse(DitEngine.isExperimentalSoc("SM8650", 36, false))
        assertFalse(DitEngine.isExperimentalSoc("SM8550", 36, true))
        assertFalse(DitEngine.isExperimentalSoc("SM8750", 36, true))
        assertFalse(DitEngine.isExperimentalSoc("SM8650P", 36, true))
        assertFalse(DitEngine.isExperimentalSoc("unknown", 36, true))
    }
}

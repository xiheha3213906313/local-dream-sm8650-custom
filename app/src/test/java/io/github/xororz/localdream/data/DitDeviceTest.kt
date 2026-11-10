package io.github.xororz.localdream.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DitDeviceTest {
    @Test
    fun gen3IsSupportedAlongsidePreviouslySupportedChips() {
        for (soc in listOf("SM8650", "sm8650", "SM8650P", "SM8750", "SM8750P", "SM8850", "SM8950", "SM8975")) {
            assertTrue(soc, DitEngine.isSupportedSoc(soc, 31))
            assertFalse(soc, DitEngine.isSupportedSoc(soc, 30))
        }
        for (soc in listOf("SM8450", "SM8550", "SM8635", "SM8735", "unknown", "SMfoo8650", "MT8650")) {
            assertFalse(soc, DitEngine.isSupportedSoc(soc, 36))
        }
    }

    @Test
    fun v75CompatibilityIsIndependentOfGeneralDeviceEligibility() {
        assertTrue(DitEngine.isSm8650Soc("SM8650", 31))
        assertTrue(DitEngine.isSm8650Soc("sm8650p", 36))
        assertFalse(DitEngine.isSm8650Soc("SM8650", 30))
        assertFalse(DitEngine.isSm8650Soc("SM8750", 36))
        assertFalse(DitEngine.isSm8650Soc("SM8550", 36))
        assertFalse(DitEngine.isSm8650Soc("unknown", 36))
    }
}

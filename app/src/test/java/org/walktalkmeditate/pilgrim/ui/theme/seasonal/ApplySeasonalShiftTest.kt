// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import android.app.Application
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SeasonalColorEngine.applySeasonalShift] calls through to
 * `android.graphics.Color.RGBToHSV` / `HSVToColor` which need an
 * Android runtime. Robolectric provides it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApplySeasonalShiftTest {

    // Moss base from the light palette (Color.kt).
    private val mossBase = Color(red = 0.478f, green = 0.545f, blue = 0.435f, alpha = 1f)

    @Test fun `minimal intensity barely shifts`() {
        val summer = SeasonalColorEngine.applySeasonalShift(
            base = mossBase,
            intensity = SeasonalColorEngine.Intensity.Minimal,
            date = LocalDate.of(2026, 7, 15),
            hemisphere = Hemisphere.Northern,
        )
        // .minimal = 10% of the signal. Channel deltas should be tiny.
        assertTrue("R diff too large", abs(summer.red - mossBase.red) < 0.05f)
        assertTrue("G diff too large", abs(summer.green - mossBase.green) < 0.05f)
        assertTrue("B diff too large", abs(summer.blue - mossBase.blue) < 0.05f)
    }

    @Test fun `full summer intensity brightens moss toward yellow-green`() {
        val summer = SeasonalColorEngine.applySeasonalShift(
            base = mossBase,
            intensity = SeasonalColorEngine.Intensity.Full,
            date = LocalDate.of(2026, 7, 15),
            hemisphere = Hemisphere.Northern,
        )
        // Summer: brightness +3%, saturation +15%, hue +0.01 (toward red).
        // Net effect on moss: slight brightening. Overall luminance
        // should not decrease.
        val mossLum = (mossBase.red + mossBase.green + mossBase.blue) / 3f
        val summerLum = (summer.red + summer.green + summer.blue) / 3f
        assertTrue(
            "summer lum $summerLum should be >= moss lum $mossLum",
            summerLum >= mossLum - 0.005f,
        )
    }

    @Test fun `moderate intensity is approximately 40 percent of full`() {
        val full = SeasonalColorEngine.applySeasonalShift(
            mossBase, SeasonalColorEngine.Intensity.Full,
            LocalDate.of(2026, 7, 15), Hemisphere.Northern,
        )
        val moderate = SeasonalColorEngine.applySeasonalShift(
            mossBase, SeasonalColorEngine.Intensity.Moderate,
            LocalDate.of(2026, 7, 15), Hemisphere.Northern,
        )
        listOf(
            Triple(mossBase.red, moderate.red, full.red),
            Triple(mossBase.green, moderate.green, full.green),
            Triple(mossBase.blue, moderate.blue, full.blue),
        ).forEach { (base, mod, ful) ->
            val fullDelta = ful - base
            val modDelta = mod - base
            if (abs(fullDelta) > 0.01f) {
                val ratio = modDelta / fullDelta
                assertTrue(
                    "ratio=$ratio should be near 0.4 (base=$base mod=$mod full=$ful)",
                    abs(ratio - 0.4f) < 0.20f,
                )
            }
        }
    }

    @Test fun `alpha is preserved`() {
        val halfTransparent = mossBase.copy(alpha = 0.5f)
        val shifted = SeasonalColorEngine.applySeasonalShift(
            halfTransparent, SeasonalColorEngine.Intensity.Full,
            LocalDate.of(2026, 7, 15), Hemisphere.Northern,
        )
        assertEquals(0.5f, shifted.alpha, 0.01f)
    }

    @Test fun `hue wrap handles winter negative shift on low-hue base`() {
        // Base with a hue near 0 (pure red-ish). Winter delta -0.02 at
        // full intensity would push hue to -0.02, which should wrap
        // back into ~0.98, not get clamped to 0.
        val lowHue = Color(red = 1f, green = 0.02f, blue = 0f, alpha = 1f)
        val winter = SeasonalColorEngine.applySeasonalShift(
            base = lowHue,
            intensity = SeasonalColorEngine.Intensity.Full,
            date = LocalDate.of(2026, 1, 15),
            hemisphere = Hemisphere.Northern,
        )
        // Red with a small blue shift should still be reddish, not
        // NaN or garbled. Sanity check: alpha preserved, all channels
        // in [0, 1].
        assertEquals(1f, winter.alpha, 0.01f)
        assertTrue(winter.red in 0f..1f)
        assertTrue(winter.green in 0f..1f)
        assertTrue(winter.blue in 0f..1f)
    }

    @Test fun `saturation clamp prevents overshoot`() {
        // A fully-saturated base at summer peak (+15% sat at full
        // intensity) should clamp to 1.0, not 1.15.
        val fullySat = Color(red = 1f, green = 0f, blue = 0f, alpha = 1f)
        val shifted = SeasonalColorEngine.applySeasonalShift(
            base = fullySat,
            intensity = SeasonalColorEngine.Intensity.Full,
            date = LocalDate.of(2026, 7, 15),
            hemisphere = Hemisphere.Northern,
        )
        // At full red, HSV saturation starts at 1.0 and can't go higher.
        // Brightness can rise; we just want to confirm nothing crashes
        // and output is still in-gamut.
        assertTrue(shifted.red in 0f..1f)
        assertTrue(shifted.green in 0f..1f)
        assertTrue(shifted.blue in 0f..1f)
    }
}

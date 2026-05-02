// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryCalloutProseTest {
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private fun base() = WalkSummaryCalloutInputs(
        currentDistanceMeters = 1000.0,
        currentMeditationSeconds = 0L,
        pastWalksMaxDistance = 0.0,
        pastWalksMaxMeditation = 0L,
        pastWalksDistanceSum = 0.0,
        units = UnitSystem.Metric,
        seasonalMarker = null,
    )

    @Test fun seasonalMarker_fires_when_celestialEnabled_true() {
        val inputs = base().copy(seasonalMarker = SeasonalMarker.SpringEquinox)
        assertEquals("You walked on the Spring Equinox", WalkSummaryCalloutProse.compute(inputs, celestialEnabled = true, context))
    }

    @Test fun seasonalMarker_suppressed_when_celestialEnabled_false() {
        val inputs = base().copy(seasonalMarker = SeasonalMarker.SpringEquinox)
        assertNull(WalkSummaryCalloutProse.compute(inputs, celestialEnabled = false, context))
    }

    @Test fun longestMeditation_fires_when_strictly_better_than_nonzero_past() {
        val inputs = base().copy(currentMeditationSeconds = 600L, pastWalksMaxMeditation = 300L)
        assertEquals("Your longest meditation yet", WalkSummaryCalloutProse.compute(inputs, celestialEnabled = false, context))
    }

    @Test fun longestMeditation_suppressed_when_past_was_zero() {
        val inputs = base().copy(currentMeditationSeconds = 600L, pastWalksMaxMeditation = 0L)
        assertNull(WalkSummaryCalloutProse.compute(inputs, celestialEnabled = false, context))
    }

    @Test fun longestWalk_fires_when_strictly_better_than_nonzero_past() {
        val inputs = base().copy(currentDistanceMeters = 5000.0, pastWalksMaxDistance = 3000.0)
        assertEquals("Your longest walk yet", WalkSummaryCalloutProse.compute(inputs, celestialEnabled = false, context))
    }

    @Test fun totalDistance_fires_at_10km_crossing() {
        val inputs = base().copy(
            currentDistanceMeters = 10_000.0,
            pastWalksDistanceSum = 0.0,
        )
        assertEquals("You've now walked 10 km total", WalkSummaryCalloutProse.compute(inputs, celestialEnabled = false, context))
    }

    @Test fun null_fallthrough_when_nothing_applies() {
        assertNull(WalkSummaryCalloutProse.compute(base(), celestialEnabled = false, context))
    }
}

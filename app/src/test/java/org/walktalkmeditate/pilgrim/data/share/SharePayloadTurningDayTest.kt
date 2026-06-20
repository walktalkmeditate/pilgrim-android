// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * iOS parity `WalkShareViewModel.swift:327-343` @fcd2255 — the share
 * payload carries a `turning_day` code on the four cardinal turnings,
 * hemisphere-corrected from the first route coordinate. Android shipped
 * a hardcoded `null`.
 */
class SharePayloadTurningDayTest {

    // --- pure code mapping ---

    @Test fun `cardinal turnings map to wire codes (northern)`() {
        assertEquals("spring-equinox", turningDayCode(SeasonalMarker.SpringEquinox, southern = false))
        assertEquals("summer-solstice", turningDayCode(SeasonalMarker.SummerSolstice, southern = false))
        assertEquals("autumn-equinox", turningDayCode(SeasonalMarker.AutumnEquinox, southern = false))
        assertEquals("winter-solstice", turningDayCode(SeasonalMarker.WinterSolstice, southern = false))
    }

    @Test fun `southern hemisphere swaps the season`() {
        // A December (astronomical winter) solstice is summer below the equator.
        assertEquals("summer-solstice", turningDayCode(SeasonalMarker.WinterSolstice, southern = true))
        assertEquals("winter-solstice", turningDayCode(SeasonalMarker.SummerSolstice, southern = true))
        assertEquals("autumn-equinox", turningDayCode(SeasonalMarker.SpringEquinox, southern = true))
        assertEquals("spring-equinox", turningDayCode(SeasonalMarker.AutumnEquinox, southern = true))
    }

    @Test fun `cross-quarter and null markers produce no code`() {
        assertNull(turningDayCode(SeasonalMarker.Beltane, southern = false))
        assertNull(turningDayCode(SeasonalMarker.Samhain, southern = true))
        assertNull(turningDayCode(null, southern = false))
    }

    // --- end-to-end through build() ---

    private val winterSolsticeMs =
        LocalDate.of(2025, 12, 21).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val midSummerMs =
        LocalDate.of(2025, 7, 15).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun inputs(startMs: Long, lat: Double) = ShareInputs(
        walk = Walk(id = 1L, startTimestamp = startMs, endTimestamp = startMs + 3_600_000L),
        routePoints = listOf(LocationPoint(timestamp = startMs, latitude = lat, longitude = 10.0)),
        altitudeSamples = emptyList(),
        activityIntervals = emptyList(),
        voiceRecordings = emptyList(),
        waypoints = emptyList(),
        distanceMeters = 1_000.0,
        activeDurationSeconds = 600.0,
        meditateDurationSeconds = 0.0,
        talkDurationSeconds = 0.0,
        elevationAscentMeters = 0.0,
        elevationDescentMeters = 0.0,
        steps = null,
    )

    private val options = WalkShareOptions(
        expiry = ExpiryOption.Season,
        journal = "",
        includeDistance = true,
        includeDuration = true,
        includeElevation = false,
        includeActivityBreakdown = false,
        includeSteps = false,
        includeWaypoints = false,
    )

    @Test fun `build emits winter-solstice for a northern December solstice walk`() {
        val payload = SharePayloadBuilder.build(inputs(winterSolsticeMs, lat = 45.0), options)
        assertEquals("winter-solstice", payload.turningDay)
    }

    @Test fun `build hemisphere-corrects a southern December solstice walk to summer`() {
        val payload = SharePayloadBuilder.build(inputs(winterSolsticeMs, lat = -33.0), options)
        assertEquals("summer-solstice", payload.turningDay)
    }

    @Test fun `build emits null on a non-turning day`() {
        val payload = SharePayloadBuilder.build(inputs(midSummerMs, lat = 45.0), options)
        assertNull(payload.turningDay)
    }
}

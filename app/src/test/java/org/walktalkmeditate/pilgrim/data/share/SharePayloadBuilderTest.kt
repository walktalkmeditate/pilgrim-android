// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint

class SharePayloadBuilderTest {

    private fun walk(startMs: Long = 1_700_000_000_000L) = Walk(
        id = 1L,
        startTimestamp = startMs,
        endTimestamp = startMs + 60 * 60 * 1000L,
    )

    private fun baseInputs(
        routePoints: List<LocationPoint> = listOf(
            LocationPoint(timestamp = 1_700_000_000_000L, latitude = 45.0, longitude = -70.0),
            LocationPoint(timestamp = 1_700_000_060_000L, latitude = 45.001, longitude = -70.001),
        ),
        altitudes: List<AltitudeSample> = emptyList(),
        intervals: List<ActivityInterval> = emptyList(),
        recordings: List<VoiceRecording> = emptyList(),
        waypoints: List<Waypoint> = emptyList(),
    ) = ShareInputs(
        walk = walk(),
        routePoints = routePoints,
        altitudeSamples = altitudes,
        activityIntervals = intervals,
        voiceRecordings = recordings,
        waypoints = waypoints,
        distanceMeters = 1_234.0,
        activeDurationSeconds = 600.0,
        meditateDurationSeconds = 0.0,
        talkDurationSeconds = 0.0,
        elevationAscentMeters = 42.0,
        elevationDescentMeters = 40.0,
        steps = 1_500,
    )

    private fun allOn(expiry: ExpiryOption = ExpiryOption.Season) = WalkShareOptions(
        expiry = expiry,
        journal = "",
        includeDistance = true,
        includeDuration = true,
        includeElevation = true,
        includeActivityBreakdown = true,
        includeSteps = true,
        includeWaypoints = false,
    )

    @Test
    fun `expiry_days reflects the selected ExpiryOption`() {
        assertEquals(30, SharePayloadBuilder.build(baseInputs(), allOn(ExpiryOption.Moon)).expiryDays)
        assertEquals(90, SharePayloadBuilder.build(baseInputs(), allOn(ExpiryOption.Season)).expiryDays)
        assertEquals(365, SharePayloadBuilder.build(baseInputs(), allOn(ExpiryOption.Cycle)).expiryDays)
    }

    @Test
    fun `units is hardcoded metric`() {
        assertEquals("metric", SharePayloadBuilder.build(baseInputs(), allOn()).units)
    }

    @Test
    fun `distance and activeDuration are always sent regardless of toggle (iOS parity)`() {
        // Toggle semantics are DISPLAY-level, not data-transmission level
        // (iOS WalkShareViewModel.swift:261-262 parity). The
        // toggled_stats list tells the server which fields to render
        // on the generated HTML page; raw values for foundational
        // walk metrics (distance, duration, meditate, talk) ALWAYS
        // ride in the payload. This regression-guard test prevents a
        // well-intentioned "privacy fix" from accidentally diverging
        // from iOS by gating distance/duration on their toggles.
        val payload = SharePayloadBuilder.build(
            baseInputs(),
            allOn().copy(includeDistance = false, includeDuration = false),
        )
        assertEquals(1_234.0, payload.stats.distance!!, 0.0)
        assertEquals(600.0, payload.stats.activeDuration!!, 0.0)
        assertEquals(false, payload.toggledStats.contains("distance"))
        assertEquals(false, payload.toggledStats.contains("duration"))
    }

    @Test
    fun `toggled_stats list reflects toggles, elevation and steps drop when off`() {
        val partial = SharePayloadBuilder.build(
            baseInputs(),
            allOn().copy(includeElevation = false, includeSteps = false),
        )
        assertEquals(listOf("distance", "duration", "activity_breakdown"), partial.toggledStats)
        assertNull(partial.stats.elevationAscent)
        assertNull(partial.stats.steps)
    }

    @Test
    fun `route points zip altitude by timestamp, default 0 when missing`() {
        val route = listOf(
            LocationPoint(timestamp = 1_700_000_000_000L, latitude = 45.0, longitude = -70.0),
            LocationPoint(timestamp = 1_700_000_010_000L, latitude = 45.001, longitude = -70.001),
        )
        val alts = listOf(
            AltitudeSample(walkId = 1L, timestamp = 1_700_000_000_000L, altitudeMeters = 100.0),
            // Second sample missing altitude — builder defaults to 0.0.
        )
        val payload = SharePayloadBuilder.build(
            baseInputs(routePoints = route, altitudes = alts),
            allOn(),
        )
        assertEquals(100.0, payload.route[0].alt, 0.0)
        assertEquals(0.0, payload.route[1].alt, 0.0)
    }

    @Test
    fun `timestamps are converted from millis to seconds`() {
        val route = listOf(
            LocationPoint(timestamp = 1_700_000_000_000L, latitude = 45.0, longitude = -70.0),
            LocationPoint(timestamp = 1_700_000_060_000L, latitude = 45.001, longitude = -70.001),
        )
        val payload = SharePayloadBuilder.build(baseInputs(routePoints = route), allOn())
        assertEquals(1_700_000_000L, payload.route[0].ts)
        assertEquals(1_700_000_060L, payload.route[1].ts)
    }

    @Test
    fun `activity intervals include MEDITATING + voice recording talk entries`() {
        val intervals = listOf(
            ActivityInterval(
                walkId = 1L,
                startTimestamp = 1_700_000_000_000L,
                endTimestamp = 1_700_000_010_000L,
                activityType = ActivityType.MEDITATING,
            ),
            ActivityInterval(
                walkId = 1L,
                startTimestamp = 1_700_000_020_000L,
                endTimestamp = 1_700_000_030_000L,
                activityType = ActivityType.WALKING, // skipped
            ),
        )
        val recordings = listOf(
            VoiceRecording(
                walkId = 1L,
                startTimestamp = 1_700_000_040_000L,
                endTimestamp = 1_700_000_050_000L,
                durationMillis = 10_000L,
                fileRelativePath = "recordings/x.wav",
            ),
        )
        val payload = SharePayloadBuilder.build(
            baseInputs(intervals = intervals, recordings = recordings),
            allOn(),
        )
        assertEquals(2, payload.activityIntervals.size)
        assertEquals("meditation", payload.activityIntervals[0].type)
        assertEquals(1_700_000_000L, payload.activityIntervals[0].startTs)
        assertEquals("talk", payload.activityIntervals[1].type)
        assertEquals(1_700_000_040L, payload.activityIntervals[1].startTs)
    }

    @Test
    fun `waypoints are included only when the toggle is on, null when off or empty`() {
        val wps = listOf(
            Waypoint(
                walkId = 1L,
                timestamp = 1_700_000_000_000L,
                latitude = 45.0,
                longitude = -70.0,
                label = "rock",
                icon = "stone",
            ),
        )
        val withToggleOff = SharePayloadBuilder.build(
            baseInputs(waypoints = wps),
            allOn().copy(includeWaypoints = false),
        )
        assertNull(withToggleOff.waypoints)

        val withToggleOn = SharePayloadBuilder.build(
            baseInputs(waypoints = wps),
            allOn().copy(includeWaypoints = true),
        )
        assertNotNull(withToggleOn.waypoints)
        assertEquals(1, withToggleOn.waypoints!!.size)
        assertEquals("rock", withToggleOn.waypoints[0].label)
    }

    @Test
    fun `nullable waypoint label and icon default to empty strings`() {
        val wps = listOf(
            Waypoint(
                walkId = 1L,
                timestamp = 1_700_000_000_000L,
                latitude = 45.0,
                longitude = -70.0,
                label = null,
                icon = null,
            ),
        )
        val payload = SharePayloadBuilder.build(
            baseInputs(waypoints = wps),
            allOn().copy(includeWaypoints = true),
        )
        assertEquals("", payload.waypoints!![0].label)
        assertEquals("", payload.waypoints[0].icon)
    }

    @Test
    fun `journal is null when blank, trimmed-preserved otherwise`() {
        val blank = SharePayloadBuilder.build(baseInputs(), allOn().copy(journal = "   "))
        assertNull(blank.journal)

        val real = SharePayloadBuilder.build(baseInputs(), allOn().copy(journal = "a quiet walk"))
        assertEquals("a quiet walk", real.journal)
    }

    @Test
    fun `tz_identifier reflects the supplied zoneId`() {
        val payload = SharePayloadBuilder.build(
            baseInputs(),
            allOn(),
            zoneId = ZoneId.of("Asia/Tokyo"),
        )
        assertEquals("Asia/Tokyo", payload.tzIdentifier)
    }

    @Test
    fun `start_date is a locale-root ISO offset string`() {
        val payload = SharePayloadBuilder.build(
            baseInputs(),
            allOn(),
            zoneId = ZoneId.of("UTC"),
        )
        // Shape: 2023-11-14T22:13:20Z (no offset colon on Z per
        // ISO_OFFSET_DATE_TIME behavior — epoch 1_700_000_000 in UTC)
        assertTrue(
            "unexpected start_date shape '${payload.startDate}'",
            payload.startDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")),
        )
    }

    @Test
    fun `distance drops to null when zero`() {
        val payload = SharePayloadBuilder.build(
            baseInputs().copy(distanceMeters = 0.0),
            allOn(),
        )
        assertNull(payload.stats.distance)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import androidx.compose.ui.graphics.Color
import java.time.Instant
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
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec

class EtegamiSpecTest {

    private val emptySeal = SealSpec(
        uuid = "seal-uuid",
        startMillis = 0L,
        distanceMeters = 1_000.0,
        durationSeconds = 60.0,
        displayDistance = "1.0",
        unitLabel = "km",
        ink = Color.Black,
    )

    private fun walk(
        intention: String? = null,
        notes: String? = null,
    ) = Walk(
        id = 1L,
        startTimestamp = 1_000L,
        endTimestamp = 61_000L,
        intention = intention,
        notes = notes,
    )

    @Test
    fun `topText prefers intention over notes`() {
        val spec = composeEtegamiSpec(
            walk = walk(intention = "deep breath", notes = "my notes"),
            routePoints = emptyList(),
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = emptyList(),
            activityIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        assertEquals("deep breath", spec.topText)
    }

    @Test
    fun `topText falls back to notes when intention absent`() {
        val spec = composeEtegamiSpec(
            walk = walk(intention = null, notes = "evening walk"),
            routePoints = emptyList(),
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = emptyList(),
            activityIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        assertEquals("evening walk", spec.topText)
    }

    @Test
    fun `topText null when both intention and notes are absent`() {
        val spec = composeEtegamiSpec(
            walk = walk(intention = null, notes = null),
            routePoints = emptyList(),
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = emptyList(),
            activityIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        assertNull(spec.topText)
    }

    @Test
    fun `blank intention falls through to notes`() {
        val spec = composeEtegamiSpec(
            walk = walk(intention = "   ", notes = "fallback"),
            routePoints = emptyList(),
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = emptyList(),
            activityIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        assertEquals("fallback", spec.topText)
    }

    @Test
    fun `elevation gain sums positive altitude deltas only`() {
        val samples = listOf(
            AltitudeSample(walkId = 1L, timestamp = 1L, altitudeMeters = 100.0),
            AltitudeSample(walkId = 1L, timestamp = 2L, altitudeMeters = 110.0), // +10
            AltitudeSample(walkId = 1L, timestamp = 3L, altitudeMeters = 105.0), // -5 (skip)
            AltitudeSample(walkId = 1L, timestamp = 4L, altitudeMeters = 125.0), // +20
            AltitudeSample(walkId = 1L, timestamp = 5L, altitudeMeters = 125.0), // +0 (skip)
        )
        val spec = composeEtegamiSpec(
            walk = walk(),
            routePoints = emptyList(),
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = samples,
            activityIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        assertEquals(30.0, spec.elevationGainMeters, 0.001)
    }

    @Test
    fun `elevation gain handles unsorted samples by sorting first`() {
        val samples = listOf(
            AltitudeSample(walkId = 1L, timestamp = 5L, altitudeMeters = 125.0),
            AltitudeSample(walkId = 1L, timestamp = 1L, altitudeMeters = 100.0),
            AltitudeSample(walkId = 1L, timestamp = 3L, altitudeMeters = 105.0),
            AltitudeSample(walkId = 1L, timestamp = 2L, altitudeMeters = 110.0),
            AltitudeSample(walkId = 1L, timestamp = 4L, altitudeMeters = 125.0),
        )
        val spec = composeEtegamiSpec(
            walk = walk(),
            routePoints = emptyList(),
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = samples,
            activityIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        // Sorted: (1,100) → (2,110)+10 → (3,105)-5 → (4,125)+20 → (5,125)+0
        assertEquals(30.0, spec.elevationGainMeters, 0.001)
    }

    @Test
    fun `activity markers assembled from meditation intervals and voice recordings, sorted`() {
        val meditationInterval = ActivityInterval(
            walkId = 1L,
            startTimestamp = 3_000L,
            endTimestamp = 4_000L,
            activityType = ActivityType.MEDITATING,
        )
        val walkingInterval = ActivityInterval(
            walkId = 1L,
            startTimestamp = 1_500L,
            endTimestamp = 2_500L,
            activityType = ActivityType.WALKING,
        )
        val voice = VoiceRecording(
            walkId = 1L,
            startTimestamp = 2_000L,
            endTimestamp = 2_500L,
            durationMillis = 500L,
            fileRelativePath = "recordings/1/2000.wav",
        )
        // Route spans 1_000..61_000 so all markers lie inside the range
        // (the compose step filters out-of-range markers).
        val route = listOf(
            LocationPoint(timestamp = 1_000L, latitude = 0.0, longitude = 0.0),
            LocationPoint(timestamp = 61_000L, latitude = 0.0, longitude = 0.0),
        )
        val spec = composeEtegamiSpec(
            walk = walk(),
            routePoints = route,
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = emptyList(),
            activityIntervals = listOf(meditationInterval, walkingInterval),
            voiceRecordings = listOf(voice),
        )
        assertEquals(2, spec.activityMarkers.size)
        // Sorted by timestamp ascending.
        assertEquals(2_000L, spec.activityMarkers[0].timestampMs)
        assertEquals(ActivityMarker.Kind.Voice, spec.activityMarkers[0].kind)
        assertEquals(3_000L, spec.activityMarkers[1].timestampMs)
        assertEquals(ActivityMarker.Kind.Meditation, spec.activityMarkers[1].kind)
    }

    @Test
    fun `activity markers outside walk GPS time range are dropped`() {
        // Walk GPS range is [1_000, 61_000]. Voice recording at 70_000
        // (after last GPS sample — user recording a closing reflection)
        // and meditation at 500 (before first GPS sample — shouldn't
        // happen in practice, defense-in-depth) must be dropped to
        // prevent visual pile-up on the route's end/start dots.
        val route = listOf(
            LocationPoint(timestamp = 1_000L, latitude = 0.0, longitude = 0.0),
            LocationPoint(timestamp = 61_000L, latitude = 0.1, longitude = 0.1),
        )
        val lateRecording = VoiceRecording(
            walkId = 1L,
            startTimestamp = 70_000L,
            endTimestamp = 72_000L,
            durationMillis = 2_000L,
            fileRelativePath = "recordings/1/70000.wav",
        )
        val earlyMeditation = ActivityInterval(
            walkId = 1L,
            startTimestamp = 500L,
            endTimestamp = 900L,
            activityType = ActivityType.MEDITATING,
        )
        val inRangeMeditation = ActivityInterval(
            walkId = 1L,
            startTimestamp = 30_000L,
            endTimestamp = 31_000L,
            activityType = ActivityType.MEDITATING,
        )
        val spec = composeEtegamiSpec(
            walk = walk(),
            routePoints = route,
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = emptyList(),
            activityIntervals = listOf(earlyMeditation, inRangeMeditation),
            voiceRecordings = listOf(lateRecording),
        )
        assertEquals(1, spec.activityMarkers.size)
        assertEquals(30_000L, spec.activityMarkers[0].timestampMs)
    }

    @Test
    fun `activity markers dropped when route is empty`() {
        // No GPS samples = no where to place markers; filter drops all.
        val spec = composeEtegamiSpec(
            walk = walk(),
            routePoints = emptyList(),
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = emptyList(),
            activityIntervals = listOf(
                ActivityInterval(
                    walkId = 1L,
                    startTimestamp = 5_000L,
                    endTimestamp = 6_000L,
                    activityType = ActivityType.MEDITATING,
                ),
            ),
            voiceRecordings = emptyList(),
        )
        assertTrue(spec.activityMarkers.isEmpty())
    }

    @Test
    fun `hourOfDay is precomputed from walk start + zoneId`() {
        // 1_700_000_000_000 ms = 2023-11-14T22:13:20Z. In UTC that's
        // hour 22; confirm the pre-computation lands there rather than
        // leaking ZoneId into the spec.
        val spec = composeEtegamiSpec(
            walk = Walk(
                id = 1L,
                startTimestamp = 1_700_000_000_000L,
                endTimestamp = 1_700_000_060_000L,
                intention = null,
                notes = null,
            ),
            routePoints = emptyList(),
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = emptyList(),
            activityIntervals = emptyList(),
            voiceRecordings = emptyList(),
            zoneId = ZoneId.of("UTC"),
        )
        val expected = Instant.ofEpochMilli(1_700_000_000_000L)
            .atZone(ZoneId.of("UTC"))
            .hour
        assertEquals(expected, spec.hourOfDay)
        assertTrue(spec.hourOfDay in 0..23)
    }

    @Test
    fun `walkUuid and startTimestamp propagate from Walk`() {
        val spec = composeEtegamiSpec(
            walk = walk(intention = "x"),
            routePoints = emptyList(),
            sealSpec = emptySeal,
            lightReading = null,
            distanceMeters = 0.0,
            durationMillis = 0L,
            altitudeSamples = emptyList(),
            activityIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        assertNotNull(spec.walkUuid)
        assertTrue(spec.walkUuid.isNotBlank())
        assertEquals(1_000L, spec.startedAtEpochMs)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType

class RouteSegmentsTest {

    private fun sample(t: Long, lat: Double = 0.0, lng: Double = 0.0) =
        RouteDataSample(walkId = 1L, timestamp = t, latitude = lat, longitude = lng, altitudeMeters = 0.0)

    private fun meditation(start: Long, end: Long) = ActivityInterval(
        walkId = 1L,
        startTimestamp = start,
        endTimestamp = end,
        activityType = ActivityType.MEDITATING,
    )

    private fun recording(start: Long, durationMs: Long) = VoiceRecording(
        walkId = 1L,
        startTimestamp = start,
        endTimestamp = start + durationMs,
        durationMillis = durationMs,
        fileRelativePath = "recordings/x.wav",
        transcription = null,
    )

    @Test fun emptySamples_returnsEmptyList() {
        val result = computeRouteSegments(emptyList(), emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test fun singleSample_returnsEmptyList() {
        val result = computeRouteSegments(listOf(sample(0L)), emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test fun allWalking_returnsOneSegment() {
        val samples = listOf(sample(0L, 1.0, 1.0), sample(10L, 2.0, 2.0), sample(20L, 3.0, 3.0))
        val result = computeRouteSegments(samples, emptyList(), emptyList())
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Walking, result[0].activity)
        assertEquals(3, result[0].points.size)
    }

    @Test fun talkInMiddle_splitsIntoWalkTalkWalk() {
        val samples = (0L..40L step 10L).map { sample(it, it.toDouble(), it.toDouble()) }
        val recordings = listOf(recording(start = 15L, durationMs = 10L)) // covers t=15..25
        val result = computeRouteSegments(samples, emptyList(), recordings)
        assertEquals(3, result.size)
        assertEquals(RouteActivity.Walking, result[0].activity)
        assertEquals(RouteActivity.Talking, result[1].activity)
        assertEquals(RouteActivity.Walking, result[2].activity)
    }

    @Test fun meditationOverlapsTalking_meditationWins() {
        val samples = listOf(sample(10L), sample(20L), sample(30L))
        val intervals = listOf(meditation(start = 5L, end = 35L))
        val recordings = listOf(recording(start = 10L, durationMs = 15L))
        val result = computeRouteSegments(samples, intervals, recordings)
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Meditating, result[0].activity)
    }

    @Test fun boundaryPointSharedBetweenSegments() {
        val samples = (0L..30L step 10L).map { sample(it, it.toDouble(), it.toDouble()) }
        val intervals = listOf(meditation(start = 15L, end = 25L))
        val result = computeRouteSegments(samples, intervals, emptyList())
        assertEquals(3, result.size)
        // Last point of segment 0 == first point of segment 1
        assertEquals(result[0].points.last(), result[1].points.first())
        assertEquals(result[1].points.last(), result[2].points.first())
    }

    @Test fun pureMeditation_returnsOneMeditatingSegment() {
        val samples = listOf(sample(10L), sample(20L), sample(30L))
        val intervals = listOf(meditation(start = 5L, end = 35L))
        val result = computeRouteSegments(samples, intervals, emptyList())
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Meditating, result[0].activity)
    }

    @Test fun nonMeditationActivityTypeIgnored() {
        // Only MEDITATING intervals classify as meditating; if the entity carried
        // a different activityType (future-proofing) the classifier ignores it.
        val samples = listOf(sample(10L), sample(20L))
        val intervals = listOf(
            ActivityInterval(
                walkId = 1L,
                startTimestamp = 5L,
                endTimestamp = 25L,
                activityType = ActivityType.WALKING,
            ),
        )
        val result = computeRouteSegments(samples, intervals, emptyList())
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Walking, result[0].activity)
    }
}

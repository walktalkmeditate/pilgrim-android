// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType

class MapAnnotationsTest {
    private fun sample(t: Long, lat: Double = 0.0, lng: Double = 0.0) =
        RouteDataSample(walkId = 1L, timestamp = t, latitude = lat, longitude = lng,
            altitudeMeters = 0.0)

    private fun meditation(start: Long, end: Long) = ActivityInterval(
        walkId = 1L, startTimestamp = start, endTimestamp = end,
        activityType = ActivityType.MEDITATING,
    )

    private fun recording(start: Long, dur: Long) = VoiceRecording(
        walkId = 1L, startTimestamp = start, endTimestamp = start + dur,
        durationMillis = dur, fileRelativePath = "x.wav", transcription = null,
    )

    @Test fun emptySamples_returnsEmpty() {
        assertTrue(computeWalkMapAnnotations(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test fun singleSample_yieldsStartOnly() {
        val result = computeWalkMapAnnotations(
            routeSamples = listOf(sample(100L, 1.0, 1.0)),
            meditationIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        assertEquals(1, result.size)
        assertEquals(WalkMapAnnotationKind.StartPoint, result[0].kind)
    }

    @Test fun multipleSamples_yieldsStartAndEnd() {
        val result = computeWalkMapAnnotations(
            routeSamples = listOf(sample(100L, 1.0, 1.0), sample(200L, 2.0, 2.0)),
            meditationIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        assertEquals(2, result.size)
        assertEquals(WalkMapAnnotationKind.StartPoint, result[0].kind)
        assertEquals(1.0, result[0].latitude, 0.0)
        assertEquals(WalkMapAnnotationKind.EndPoint, result[1].kind)
        assertEquals(2.0, result[1].latitude, 0.0)
    }

    @Test fun meditation_pinAtClosestSampleToStart() {
        val samples = listOf(
            sample(100L, 1.0, 1.0),
            sample(500L, 2.0, 2.0),
            sample(900L, 3.0, 3.0),
        )
        val result = computeWalkMapAnnotations(
            routeSamples = samples,
            meditationIntervals = listOf(meditation(start = 480L, end = 700L)),
            voiceRecordings = emptyList(),
        )
        // start + end + 1 meditation
        assertEquals(3, result.size)
        val medAnn = result.first { it.kind is WalkMapAnnotationKind.Meditation }
        // Closest to t=480 is sample at t=500 (lat=2.0)
        assertEquals(2.0, medAnn.latitude, 0.0001)
        assertEquals(220L, (medAnn.kind as WalkMapAnnotationKind.Meditation).durationMillis)
    }

    @Test fun voiceRecording_pinAtClosestSampleToStart() {
        val samples = listOf(
            sample(100L, 1.0, 1.0),
            sample(500L, 2.0, 2.0),
            sample(900L, 3.0, 3.0),
        )
        val result = computeWalkMapAnnotations(
            routeSamples = samples,
            meditationIntervals = emptyList(),
            voiceRecordings = listOf(recording(start = 850L, dur = 50L)),
        )
        assertEquals(3, result.size)
        val voiceAnn = result.first { it.kind is WalkMapAnnotationKind.VoiceRecording }
        // Closest to t=850 is sample at t=900 (lat=3.0)
        assertEquals(3.0, voiceAnn.latitude, 0.0001)
        assertEquals(50L, (voiceAnn.kind as WalkMapAnnotationKind.VoiceRecording).durationMillis)
    }
}

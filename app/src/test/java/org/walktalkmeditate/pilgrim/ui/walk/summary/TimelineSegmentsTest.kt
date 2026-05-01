// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType

class TimelineSegmentsTest {
    private fun meditation(start: Long, end: Long) = ActivityInterval(
        walkId = 1L, startTimestamp = start, endTimestamp = end,
        activityType = ActivityType.MEDITATING,
    )
    private fun recording(start: Long, dur: Long) = VoiceRecording(
        walkId = 1L, startTimestamp = start, endTimestamp = start + dur,
        durationMillis = dur, fileRelativePath = "x.wav", transcription = null,
    )

    @Test fun emptyInputs_returnsEmpty() {
        val segs = computeTimelineSegments(0L, 1000L, emptyList(), emptyList())
        assertTrue(segs.isEmpty())
    }

    @Test fun singleTalk_yieldsOneTalkSegment_atCorrectFraction() {
        // walk 0..1000 ms, talk 200..400 ms → fraction 0.2..0.4 (width 0.2)
        val segs = computeTimelineSegments(
            startMs = 0L, endMs = 1000L,
            meditations = emptyList(),
            recordings = listOf(recording(start = 200L, dur = 200L)),
        )
        assertEquals(1, segs.size)
        assertEquals(TimelineSegmentType.Talking, segs[0].type)
        assertEquals(0.2f, segs[0].startFraction, 0.0001f)
        assertEquals(0.2f, segs[0].widthFraction, 0.0001f)
    }

    @Test fun singleMeditation_yieldsOneMeditationSegment() {
        val segs = computeTimelineSegments(
            startMs = 0L, endMs = 2000L,
            meditations = listOf(meditation(start = 500L, end = 1500L)),
            recordings = emptyList(),
        )
        assertEquals(1, segs.size)
        assertEquals(TimelineSegmentType.Meditating, segs[0].type)
        assertEquals(0.25f, segs[0].startFraction, 0.0001f)
        assertEquals(0.5f, segs[0].widthFraction, 0.0001f)
    }

    @Test fun bothInOneWalk_returnsSortedByStart() {
        val segs = computeTimelineSegments(
            startMs = 0L, endMs = 1000L,
            meditations = listOf(meditation(start = 600L, end = 800L)),
            recordings = listOf(recording(start = 100L, dur = 200L)),
        )
        assertEquals(2, segs.size)
        assertEquals(TimelineSegmentType.Talking, segs[0].type)
        assertEquals(TimelineSegmentType.Meditating, segs[1].type)
    }

    @Test fun boundaryFractions_clampedZeroToOne() {
        // Interval starts before walkStart, ends after walkEnd → start=0, end=1
        val segs = computeTimelineSegments(
            startMs = 1000L, endMs = 2000L,
            meditations = listOf(meditation(start = 500L, end = 2500L)),
            recordings = emptyList(),
        )
        assertEquals(1, segs.size)
        assertEquals(0f, segs[0].startFraction, 0.0001f)
        assertEquals(1f, segs[0].widthFraction, 0.0001f)
    }

    @Test fun nonMeditationActivityType_excluded() {
        val intervals = listOf(
            ActivityInterval(walkId = 1L, startTimestamp = 100L, endTimestamp = 200L,
                activityType = ActivityType.WALKING),
        )
        val segs = computeTimelineSegments(0L, 1000L, intervals, emptyList())
        assertTrue(segs.isEmpty())
    }
}

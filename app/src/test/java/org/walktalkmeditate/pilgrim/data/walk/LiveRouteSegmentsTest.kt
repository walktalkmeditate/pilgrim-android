// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Live-walk activity classification, ported from iOS
 * `ActiveWalkViewModel.activityType(at:)@2ee1185:583-602` — completed
 * meditation intervals, then the in-progress meditation, then completed
 * recordings, then the in-progress recording, else walking.
 */
class LiveRouteSegmentsTest {

    private fun points(vararg timestamps: Long): List<LocationPoint> =
        timestamps.mapIndexed { i, ts ->
            LocationPoint(timestamp = ts, latitude = i * 0.001, longitude = i * 0.002)
        }

    private fun activities(segments: List<RouteSegment>) = segments.map { it.activity }

    @Test
    fun `a walk with no talk and no meditation is one walking segment`() {
        val route = points(0, 1_000, 2_000, 3_000)
        val segments = computeLiveRouteSegments(
            points = route,
            meditationWindows = emptyList(),
            liveMeditationStartMillis = null,
            talkWindows = emptyList(),
            liveTalkStartMillis = null,
        )

        assertEquals(listOf(RouteActivity.Walking), activities(segments))
        assertEquals(route, segments.single().points)
    }

    @Test
    fun `fewer than two points draws nothing`() {
        assertEquals(
            emptyList<RouteSegment>(),
            computeLiveRouteSegments(
                points = points(0),
                meditationWindows = emptyList(),
                liveMeditationStartMillis = null,
                talkWindows = emptyList(),
                liveTalkStartMillis = null,
            ),
        )
    }

    @Test
    fun `an in-progress recording tints every fix from its start onward`() {
        val segments = computeLiveRouteSegments(
            points = points(0, 1_000, 2_000, 3_000, 4_000),
            meditationWindows = emptyList(),
            liveMeditationStartMillis = null,
            talkWindows = emptyList(),
            liveTalkStartMillis = 2_000L,
        )

        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Talking),
            activities(segments),
        )
        // The transition fix sits in BOTH segments so the polylines join.
        assertEquals(listOf(0L, 1_000L, 2_000L), segments[0].points.map { it.timestamp })
        assertEquals(listOf(2_000L, 3_000L, 4_000L), segments[1].points.map { it.timestamp })
    }

    @Test
    fun `a completed recording tints only the fixes inside its window`() {
        val segments = computeLiveRouteSegments(
            points = points(0, 1_000, 2_000, 3_000, 4_000, 5_000),
            meditationWindows = emptyList(),
            liveMeditationStartMillis = null,
            talkWindows = listOf(ActivityWindow(1_000L, 3_000L)),
            liveTalkStartMillis = null,
        )

        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Talking, RouteActivity.Walking),
            activities(segments),
        )
    }

    @Test
    fun `a talk that started with the walk needs no leading walking segment`() {
        val segments = computeLiveRouteSegments(
            points = points(0, 1_000, 2_000, 3_000),
            meditationWindows = emptyList(),
            liveMeditationStartMillis = null,
            talkWindows = emptyList(),
            liveTalkStartMillis = 0L,
        )

        assertEquals(listOf(RouteActivity.Talking), activities(segments))
    }

    @Test
    fun `an in-progress meditation tints every fix from its start onward`() {
        val segments = computeLiveRouteSegments(
            points = points(0, 1_000, 2_000, 3_000),
            meditationWindows = emptyList(),
            liveMeditationStartMillis = 1_000L,
            talkWindows = emptyList(),
            liveTalkStartMillis = null,
        )

        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Meditating),
            activities(segments),
        )
    }

    @Test
    fun `a completed meditation returns to walking after its window`() {
        val segments = computeLiveRouteSegments(
            points = points(0, 1_000, 2_000, 3_000, 4_000),
            meditationWindows = listOf(ActivityWindow(1_000L, 2_000L)),
            liveMeditationStartMillis = null,
            talkWindows = emptyList(),
            liveTalkStartMillis = null,
        )

        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Meditating, RouteActivity.Walking),
            activities(segments),
        )
    }

    // iOS checks meditation (completed, then live) before talking, so a fix
    // inside both wins meditating — same precedence computeRouteSegments
    // applies on the summary map.
    @Test
    fun `meditation outranks talking on an overlapping fix`() {
        val segments = computeLiveRouteSegments(
            points = points(0, 1_000, 2_000),
            meditationWindows = listOf(ActivityWindow(1_000L, 2_000L)),
            liveMeditationStartMillis = null,
            talkWindows = listOf(ActivityWindow(1_000L, 2_000L)),
            liveTalkStartMillis = null,
        )

        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Meditating),
            activities(segments),
        )
    }

    @Test
    fun `a live meditation outranks a live talk`() {
        val segments = computeLiveRouteSegments(
            points = points(0, 1_000, 2_000),
            meditationWindows = emptyList(),
            liveMeditationStartMillis = 1_000L,
            talkWindows = emptyList(),
            liveTalkStartMillis = 1_000L,
        )

        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Meditating),
            activities(segments),
        )
    }

    // The tail-mutation renderer keeps settled annotations alive across
    // fixes by structural equality, so every segment that has closed —
    // meaning a later segment already exists — must come back
    // byte-identical as the route grows under it. Only the last segment
    // moves, and only by gaining points.
    @Test
    fun `closed segments keep stable identity as the route grows`() {
        val all = points(0, 1_000, 2_000, 3_000, 4_000, 5_000)
        val talk = listOf(ActivityWindow(1_000L, 2_000L))

        fun segmentsAfter(fixes: Int) = computeLiveRouteSegments(
            points = all.take(fixes),
            meditationWindows = emptyList(),
            liveMeditationStartMillis = null,
            talkWindows = talk,
            liveTalkStartMillis = null,
        )

        val afterFourFixes = segmentsAfter(4)
        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Talking, RouteActivity.Walking),
            activities(afterFourFixes),
        )

        for (fixes in 5..all.size) {
            val grown = segmentsAfter(fixes)
            // Everything but the tail is frozen.
            assertEquals(afterFourFixes[0], grown[0])
            assertEquals(afterFourFixes[1], grown[1])
            // The tail only ever gains points.
            val tail = grown.last()
            assertTrue(
                "tail shrank at $fixes fixes: $tail",
                tail.points.size >= afterFourFixes.last().points.size,
            )
            assertEquals(
                afterFourFixes.last().points,
                tail.points.take(afterFourFixes.last().points.size),
            )
        }
    }

    // The live tail absorbs the transition fix when the activity changes,
    // which is what lets the renderer mutate one polyline and create
    // exactly one more per transition.
    @Test
    fun `the tail gains the boundary fix when a new activity starts`() {
        val route = points(0, 1_000, 2_000, 3_000)

        val beforeTransition = computeLiveRouteSegments(
            points = route.take(3),
            meditationWindows = emptyList(),
            liveMeditationStartMillis = null,
            talkWindows = emptyList(),
            liveTalkStartMillis = null,
        )
        val afterTransition = computeLiveRouteSegments(
            points = route,
            meditationWindows = emptyList(),
            liveMeditationStartMillis = null,
            talkWindows = emptyList(),
            liveTalkStartMillis = 3_000L,
        )

        assertEquals(listOf(RouteActivity.Walking), activities(beforeTransition))
        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Talking),
            activities(afterTransition),
        )
        assertEquals(route, afterTransition[0].points)
        assertEquals(listOf(3_000L), afterTransition[1].points.map { it.timestamp })
    }

    // The window a recording occupied while live becomes a closed window
    // when the row lands. Classification must not change for the fixes it
    // already covered, or the renderer would rebuild settled polylines.
    @Test
    fun `closing a live talk window leaves earlier classification untouched`() {
        val route = points(0, 1_000, 2_000, 3_000)
        val whileRecording = computeLiveRouteSegments(
            points = route,
            meditationWindows = emptyList(),
            liveMeditationStartMillis = null,
            talkWindows = emptyList(),
            liveTalkStartMillis = 1_000L,
        )
        val afterRowLanded = computeLiveRouteSegments(
            points = route,
            meditationWindows = emptyList(),
            liveMeditationStartMillis = null,
            talkWindows = listOf(ActivityWindow(1_000L, 3_000L)),
            liveTalkStartMillis = null,
        )

        assertEquals(whileRecording, afterRowLanded)
    }

    @Test
    fun `live classification matches the summary segmenter for the same windows`() {
        val route = points(0, 1_000, 2_000, 3_000, 4_000, 5_000)
        val live = computeLiveRouteSegments(
            points = route,
            meditationWindows = listOf(ActivityWindow(4_000L, 5_000L)),
            liveMeditationStartMillis = null,
            talkWindows = listOf(ActivityWindow(1_000L, 2_000L)),
            liveTalkStartMillis = null,
        )

        assertEquals(
            listOf(
                RouteActivity.Walking,
                RouteActivity.Talking,
                RouteActivity.Walking,
                RouteActivity.Meditating,
            ),
            activities(live),
        )
    }
}

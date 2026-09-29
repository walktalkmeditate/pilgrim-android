// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

class ActivityIntervalReplayTest {

    private fun event(t: Long, type: WalkEventType, walkId: Long = 1L) =
        WalkEvent(walkId = walkId, timestamp = t, eventType = type)

    @Test
    fun noEvents_returnsEmptyList() {
        val result = deriveActivityIntervals(events = emptyList(), walkId = 1L, closeAt = 10_000L)

        assertTrue(result.isEmpty())
    }

    @Test
    fun singleCompletedPair_returnsOneMeditatingInterval() {
        val result = deriveActivityIntervals(
            events = listOf(
                event(500L, WalkEventType.MEDITATION_START),
                event(2_000L, WalkEventType.MEDITATION_END),
            ),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertEquals(1, result.size)
        assertEquals(500L, result[0].startTimestamp)
        assertEquals(2_000L, result[0].endTimestamp)
        assertEquals(ActivityType.MEDITATING, result[0].activityType)
    }

    @Test
    fun backToBackSessions_returnsTwoSeparateIntervals() {
        val result = deriveActivityIntervals(
            events = listOf(
                event(1_000L, WalkEventType.MEDITATION_START),
                event(1_400L, WalkEventType.MEDITATION_END),
                event(2_000L, WalkEventType.MEDITATION_START),
                event(2_900L, WalkEventType.MEDITATION_END),
            ),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertEquals(2, result.size)
        assertEquals(1_000L, result[0].startTimestamp)
        assertEquals(1_400L, result[0].endTimestamp)
        assertEquals(2_000L, result[1].startTimestamp)
        assertEquals(2_900L, result[1].endTimestamp)
    }

    @Test
    fun danglingStart_closedAtCloseAt() {
        // Walk finished (or was paused) mid-meditation — the reducer
        // never persists a synthetic MEDITATION_END, so the replay must
        // close the interval at the walk's end timestamp instead of
        // dropping the walker's final stretch.
        val result = deriveActivityIntervals(
            events = listOf(event(1_000L, WalkEventType.MEDITATION_START)),
            walkId = 1L,
            closeAt = 1_800L,
        )

        assertEquals(1, result.size)
        assertEquals(1_000L, result[0].startTimestamp)
        assertEquals(1_800L, result[0].endTimestamp)
        assertEquals(ActivityType.MEDITATING, result[0].activityType)
    }

    @Test
    fun danglingStart_withNullCloseAt_isDropped() {
        // Matches replayWalkEventTotals's contract for a still-open
        // walk: without a close point there is nothing to fold the
        // pending interval into, and this function has no pending-state
        // out-channel (unlike WalkEventTotals.pendingMeditationAt).
        val result = deriveActivityIntervals(
            events = listOf(event(1_000L, WalkEventType.MEDITATION_START)),
            walkId = 1L,
            closeAt = null,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun unrelatedEventTypes_areIgnored() {
        val result = deriveActivityIntervals(
            events = listOf(
                event(100L, WalkEventType.SEEK_MODE),
                event(200L, WalkEventType.PAUSED),
                event(300L, WalkEventType.RESUMED),
                event(400L, WalkEventType.WAYPOINT_MARKED),
                event(500L, WalkEventType.SEEK_ARRIVAL),
                event(550L, WalkEventType.HONOR_MODE),
                event(560L, WalkEventType.HONOR_ARRIVAL),
                event(600L, WalkEventType.UNKNOWN),
                event(700L, WalkEventType.MEDITATION_START),
                event(900L, WalkEventType.MEDITATION_END),
            ),
            walkId = 1L,
            closeAt = 2_000L,
        )

        assertEquals(1, result.size)
        assertEquals(700L, result[0].startTimestamp)
        assertEquals(900L, result[0].endTimestamp)
    }

    @Test
    fun backToBackSittingsSharingAMillisecond_staySeparate_whateverTheTieOrder() {
        // SQL returns timestamp ties in no defined order. Before the
        // END-before-START tie-break, START(2000) sorting ahead of
        // END(2000) made the later START overwrite the open one and the
        // END close nothing: both sittings collapsed to zero.
        val result = deriveActivityIntervals(
            events = listOf(
                event(1_000L, WalkEventType.MEDITATION_START),
                event(2_000L, WalkEventType.MEDITATION_START),
                event(2_000L, WalkEventType.MEDITATION_END),
                event(3_000L, WalkEventType.MEDITATION_END),
            ),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertEquals(listOf(1_000L to 2_000L, 2_000L to 3_000L), result.spans())
    }

    @Test
    fun overlappingSittings_mergeIntoOne() {
        // [1000, 3000] and [2000, 4000], e.g. two overlapping activities
        // from a package. Before: the later START won, leaving [2000, 3000].
        val result = deriveActivityIntervals(
            events = listOf(
                event(1_000L, WalkEventType.MEDITATION_START),
                event(2_000L, WalkEventType.MEDITATION_START),
                event(3_000L, WalkEventType.MEDITATION_END),
                event(4_000L, WalkEventType.MEDITATION_END),
            ),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertEquals(listOf(1_000L to 4_000L), result.spans())
    }

    @Test
    fun nestedSitting_mergesIntoTheOuterOne() {
        val result = deriveActivityIntervals(
            events = listOf(
                event(1_000L, WalkEventType.MEDITATION_START),
                event(1_500L, WalkEventType.MEDITATION_START),
                event(1_800L, WalkEventType.MEDITATION_END),
                event(4_000L, WalkEventType.MEDITATION_END),
            ),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertEquals(listOf(1_000L to 4_000L), result.spans())
    }

    @Test
    fun unsortedEvents_deriveTheSameSittingsAsSorted() {
        val sorted = listOf(
            event(1_000L, WalkEventType.MEDITATION_START),
            event(1_400L, WalkEventType.MEDITATION_END),
            event(1_400L, WalkEventType.MEDITATION_START),
            event(2_900L, WalkEventType.MEDITATION_END),
            event(4_000L, WalkEventType.MEDITATION_START),
        )

        val fromShuffled = deriveActivityIntervals(sorted.reversed(), walkId = 1L, closeAt = 4_500L)

        assertEquals(deriveActivityIntervals(sorted, walkId = 1L, closeAt = 4_500L).spans(), fromShuffled.spans())
        assertEquals(listOf(1_000L to 1_400L, 1_400L to 2_900L, 4_000L to 4_500L), fromShuffled.spans())
    }

    @Test
    fun extraStartWithoutItsEnd_keepsTheMergedSittingOpenUntilCloseAt() {
        // Two STARTs and one END can only be two overlapping sittings with
        // one END missing, so the merged sitting is still open at the last
        // event and closes at the walk's end. (Before: the later START won
        // and the result was [1200, 1500].)
        val result = deriveActivityIntervals(
            events = listOf(
                event(1_000L, WalkEventType.MEDITATION_START),
                event(1_200L, WalkEventType.MEDITATION_START),
                event(1_500L, WalkEventType.MEDITATION_END),
            ),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertEquals(listOf(1_000L to 5_000L), result.spans())
    }

    @Test
    fun startAndEndInTheSameMillisecond_contributeNothing() {
        // The strict end > start rule: a zero-length sitting adds no
        // interval, and the END-before-START tie-break must not turn its
        // START into one left open until the walk's end.
        val result = deriveActivityIntervals(
            events = listOf(
                event(2_000L, WalkEventType.MEDITATION_START),
                event(2_000L, WalkEventType.MEDITATION_END),
                event(3_000L, WalkEventType.MEDITATION_START),
                event(3_500L, WalkEventType.MEDITATION_END),
            ),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertEquals(listOf(3_000L to 3_500L), result.spans())
    }

    @Test
    fun endStampedBeforeItsStart_isIgnoredAndTheStartClosesAtCloseAt() {
        // Clock skew (the clock stepped back mid-sitting): by timestamp the
        // END comes first and finds nothing open. The START then stays
        // open until the walk's end, as it already did when the DAO's
        // ORDER BY timestamp fed the old replay.
        val result = deriveActivityIntervals(
            events = listOf(
                event(2_000L, WalkEventType.MEDITATION_START),
                event(1_000L, WalkEventType.MEDITATION_END),
            ),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertEquals(listOf(2_000L to 5_000L), result.spans())
    }

    @Test
    fun danglingStart_isDroppedWhenCloseAtIsNotAfterIt() {
        val result = deriveActivityIntervals(
            events = listOf(event(5_000L, WalkEventType.MEDITATION_START)),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun unmatchedEnd_withNoPrecedingStart_isIgnored() {
        val result = deriveActivityIntervals(
            events = listOf(event(1_000L, WalkEventType.MEDITATION_END)),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun everyIntervalIsStampedWithTheGivenWalkId() {
        val result = deriveActivityIntervals(
            events = listOf(
                event(1_000L, WalkEventType.MEDITATION_START, walkId = 42L),
                event(1_400L, WalkEventType.MEDITATION_END, walkId = 42L),
            ),
            walkId = 42L,
            closeAt = 5_000L,
        )

        assertEquals(1, result.size)
        assertEquals(42L, result[0].walkId)
    }

    private fun List<ActivityInterval>.spans(): List<Pair<Long, Long>> =
        map { it.startTimestamp to it.endTimestamp }
}

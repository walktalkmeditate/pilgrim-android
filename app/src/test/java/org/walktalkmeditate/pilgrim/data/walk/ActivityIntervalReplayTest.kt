// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
    fun repeatedStart_lastWriteWins_matchingReplayWalkEventTotals() {
        // Two STARTs before an END: replayWalkEventTotals unconditionally
        // overwrites pendingMeditationAt on each START, silently
        // discarding the earlier open. Mirrored exactly here — only the
        // later START pairs with the END.
        val result = deriveActivityIntervals(
            events = listOf(
                event(1_000L, WalkEventType.MEDITATION_START),
                event(1_200L, WalkEventType.MEDITATION_START),
                event(1_500L, WalkEventType.MEDITATION_END),
            ),
            walkId = 1L,
            closeAt = 5_000L,
        )

        assertEquals(1, result.size)
        assertEquals(1_200L, result[0].startTimestamp)
        assertEquals(1_500L, result[0].endTimestamp)
    }

    @Test
    fun endAtOrBeforeStart_contributesNoInterval() {
        // Clock skew guard, mirroring replayWalkEventTotals's
        // coerceAtLeast(0): a non-positive delta contributes nothing —
        // here, that means no interval is emitted at all.
        val result = deriveActivityIntervals(
            events = listOf(
                event(2_000L, WalkEventType.MEDITATION_START),
                event(1_000L, WalkEventType.MEDITATION_END),
            ),
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
}

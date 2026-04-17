// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkEventReplayTest {

    private data class Event(
        override val timestamp: Long,
        override val type: WalkEventType,
    ) : WalkEventLike

    @Test
    fun `empty events produce zero totals and no pending`() {
        val totals = replayWalkEventTotals(events = emptyList(), closeAt = null)

        assertEquals(0L, totals.totalPausedMillis)
        assertEquals(0L, totals.totalMeditatedMillis)
        assertNull(totals.pendingPauseAt)
        assertNull(totals.pendingMeditationAt)
    }

    @Test
    fun `completed PAUSED-RESUMED pair accumulates into totalPausedMillis`() {
        val totals = replayWalkEventTotals(
            events = listOf(
                Event(1_000L, WalkEventType.PAUSED),
                Event(1_400L, WalkEventType.RESUMED),
            ),
            closeAt = null,
        )

        assertEquals(400L, totals.totalPausedMillis)
        assertNull(totals.pendingPauseAt)
    }

    @Test
    fun `completed MEDITATION pair accumulates into totalMeditatedMillis`() {
        val totals = replayWalkEventTotals(
            events = listOf(
                Event(500L, WalkEventType.MEDITATION_START),
                Event(2_000L, WalkEventType.MEDITATION_END),
            ),
            closeAt = null,
        )

        assertEquals(1_500L, totals.totalMeditatedMillis)
    }

    @Test
    fun `dangling PAUSED with null closeAt surfaces pendingPauseAt`() {
        val totals = replayWalkEventTotals(
            events = listOf(Event(1_000L, WalkEventType.PAUSED)),
            closeAt = null,
        )

        assertEquals(0L, totals.totalPausedMillis)
        assertEquals(1_000L, totals.pendingPauseAt)
    }

    @Test
    fun `dangling PAUSED with closeAt folds into totalPausedMillis`() {
        val totals = replayWalkEventTotals(
            events = listOf(Event(1_000L, WalkEventType.PAUSED)),
            closeAt = 1_500L,
        )

        // Dangling PAUSED closed at closeAt — 500 ms of pause.
        assertEquals(500L, totals.totalPausedMillis)
        assertNull(totals.pendingPauseAt)
    }

    @Test
    fun `dangling MEDITATION_START with closeAt folds into totalMeditatedMillis`() {
        val totals = replayWalkEventTotals(
            events = listOf(Event(1_000L, WalkEventType.MEDITATION_START)),
            closeAt = 1_800L,
        )

        assertEquals(800L, totals.totalMeditatedMillis)
    }

    @Test
    fun `completed pair followed by dangling pause yields correct total`() {
        val totals = replayWalkEventTotals(
            events = listOf(
                Event(1_000L, WalkEventType.PAUSED),
                Event(1_200L, WalkEventType.RESUMED),
                Event(2_000L, WalkEventType.PAUSED),
            ),
            closeAt = 2_500L,
        )

        // First pause = 200, second (dangling) = 500 — total 700.
        assertEquals(700L, totals.totalPausedMillis)
    }

    @Test
    fun `RESUMED without preceding PAUSED is ignored`() {
        val totals = replayWalkEventTotals(
            events = listOf(Event(1_000L, WalkEventType.RESUMED)),
            closeAt = 2_000L,
        )

        assertEquals(0L, totals.totalPausedMillis)
        assertNull(totals.pendingPauseAt)
    }

    @Test
    fun `negative delta coerced to zero for clock skew protection`() {
        val totals = replayWalkEventTotals(
            events = listOf(
                Event(2_000L, WalkEventType.PAUSED),
                Event(1_000L, WalkEventType.RESUMED), // clock went backwards
            ),
            closeAt = null,
        )

        assertEquals(0L, totals.totalPausedMillis)
    }

    @Test
    fun `WAYPOINT_MARKED events are ignored`() {
        val totals = replayWalkEventTotals(
            events = listOf(Event(1_000L, WalkEventType.WAYPOINT_MARKED)),
            closeAt = 2_000L,
        )

        assertEquals(0L, totals.totalPausedMillis)
        assertEquals(0L, totals.totalMeditatedMillis)
    }
}

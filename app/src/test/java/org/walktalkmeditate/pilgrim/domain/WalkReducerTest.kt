// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkReducerTest {

    @Test
    fun `start from idle transitions to active and does not emit side effects`() {
        val (next, effect) = WalkReducer.reduce(
            state = WalkState.Idle,
            action = WalkAction.Start(walkId = 42L, at = 1_000L),
        )

        assertTrue(next is WalkState.Active)
        assertEquals(42L, (next as WalkState.Active).walk.walkId)
        assertEquals(1_000L, next.walk.startedAt)
        assertEquals(0.0, next.walk.distanceMeters, 0.0)
        assertSame(WalkEffect.None, effect)
    }

    @Test
    fun `first location sample while active sets lastLocation but adds no distance`() {
        val start = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        val point = LocationPoint(timestamp = 100L, latitude = 35.0, longitude = 139.0)

        val (next, effect) = WalkReducer.reduce(start, WalkAction.LocationSampled(point))

        val active = next as WalkState.Active
        assertEquals(point, active.walk.lastLocation)
        assertEquals(0.0, active.walk.distanceMeters, 0.0001)
        assertTrue(effect is WalkEffect.PersistLocation)
        assertEquals(point, (effect as WalkEffect.PersistLocation).point)
    }

    @Test
    fun `subsequent location samples accumulate haversine distance`() {
        val first = LocationPoint(timestamp = 100L, latitude = 0.0, longitude = 0.0)
        val second = LocationPoint(timestamp = 200L, latitude = 0.0, longitude = 0.001)

        val initial = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, lastLocation = first),
        )
        val (next, _) = WalkReducer.reduce(initial, WalkAction.LocationSampled(second))

        val distance = (next as WalkState.Active).walk.distanceMeters
        // 0.001 degree at equator ≈ 111.32 meters.
        assertEquals(111.32, distance, 0.5)
    }

    @Test
    fun `pause transitions to paused and emits paused event`() {
        val start = WalkState.Active(WalkAccumulator(walkId = 9L, startedAt = 0L))

        val (next, effect) = WalkReducer.reduce(start, WalkAction.Pause(at = 500L))

        assertTrue(next is WalkState.Paused)
        assertEquals(500L, (next as WalkState.Paused).pausedAt)
        assertTrue(effect is WalkEffect.PersistEvent)
        assertEquals(WalkEventType.PAUSED, (effect as WalkEffect.PersistEvent).eventType)
        assertEquals(500L, effect.timestamp)
    }

    @Test
    fun `resume from paused accumulates the ongoing pause duration`() {
        val paused = WalkState.Paused(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalPausedMillis = 200L),
            pausedAt = 1_000L,
        )

        val (next, effect) = WalkReducer.reduce(paused, WalkAction.Resume(at = 1_500L))

        val active = next as WalkState.Active
        assertEquals(700L, active.walk.totalPausedMillis)
        assertEquals(WalkEventType.RESUMED, (effect as WalkEffect.PersistEvent).eventType)
    }

    @Test
    fun `meditate start transitions to meditating and emits event`() {
        val start = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))

        val (next, effect) = WalkReducer.reduce(start, WalkAction.MeditateStart(at = 300L))

        assertTrue(next is WalkState.Meditating)
        assertEquals(300L, (next as WalkState.Meditating).meditationStartedAt)
        assertEquals(WalkEventType.MEDITATION_START, (effect as WalkEffect.PersistEvent).eventType)
    }

    @Test
    fun `meditate end accumulates meditation duration and returns to active`() {
        val meditating = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 100L),
            meditationStartedAt = 500L,
        )

        val (next, effect) = WalkReducer.reduce(meditating, WalkAction.MeditateEnd(at = 1_100L))

        val active = next as WalkState.Active
        assertEquals(700L, active.walk.totalMeditatedMillis)
        assertEquals(WalkEventType.MEDITATION_END, (effect as WalkEffect.PersistEvent).eventType)
    }

    @Test
    fun `finish from active emits FinalizeWalk`() {
        val start = WalkState.Active(WalkAccumulator(walkId = 7L, startedAt = 0L))

        val (next, effect) = WalkReducer.reduce(start, WalkAction.Finish(at = 900L))

        val finished = next as WalkState.Finished
        assertEquals(900L, finished.endedAt)
        val finalize = effect as WalkEffect.FinalizeWalk
        assertEquals(7L, finalize.walkId)
        assertEquals(900L, finalize.endTimestamp)
    }

    @Test
    fun `finish while paused folds ongoing pause duration into totals`() {
        val paused = WalkState.Paused(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalPausedMillis = 50L),
            pausedAt = 1_000L,
        )

        val (next, _) = WalkReducer.reduce(paused, WalkAction.Finish(at = 1_300L))

        val finished = next as WalkState.Finished
        assertEquals(350L, finished.walk.totalPausedMillis)
    }

    @Test
    fun `finish while meditating folds ongoing meditation into totals`() {
        val meditating = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 0L),
            meditationStartedAt = 400L,
        )

        val (next, _) = WalkReducer.reduce(meditating, WalkAction.Finish(at = 1_000L))

        val finished = next as WalkState.Finished
        assertEquals(600L, finished.walk.totalMeditatedMillis)
    }

    @Test
    fun `start ignored when already active`() {
        val start = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))

        val (next, effect) = WalkReducer.reduce(start, WalkAction.Start(walkId = 99L, at = 100L))

        assertSame(start, next)
        assertSame(WalkEffect.None, effect)
    }

    @Test
    fun `location sample ignored when paused`() {
        val paused = WalkState.Paused(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
            pausedAt = 100L,
        )
        val point = LocationPoint(timestamp = 150L, latitude = 0.0, longitude = 0.0)

        val (next, effect) = WalkReducer.reduce(paused, WalkAction.LocationSampled(point))

        assertSame(paused, next)
        assertSame(WalkEffect.None, effect)
    }

    @Test
    fun `location sample ignored when meditating`() {
        val meditating = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
            meditationStartedAt = 100L,
        )
        val point = LocationPoint(timestamp = 150L, latitude = 0.0, longitude = 0.0)

        val (next, effect) = WalkReducer.reduce(meditating, WalkAction.LocationSampled(point))

        assertSame(meditating, next)
        assertSame(WalkEffect.None, effect)
    }

    @Test
    fun `finished state ignores non-Start actions`() {
        val finished = WalkState.Finished(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 1_000.0),
            endedAt = 1_000L,
        )

        val (next, effect) = WalkReducer.reduce(
            finished,
            WalkAction.LocationSampled(LocationPoint(timestamp = 2_000L, latitude = 0.0, longitude = 0.0)),
        )

        assertSame(finished, next)
        assertSame(WalkEffect.None, effect)
    }

    @Test
    fun `start from finished transitions to a fresh active walk`() {
        val finished = WalkState.Finished(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 1_200.0),
            endedAt = 1_000L,
        )

        val (next, _) = WalkReducer.reduce(finished, WalkAction.Start(walkId = 2L, at = 2_000L))

        val active = next as WalkState.Active
        assertEquals(2L, active.walk.walkId)
        assertEquals(2_000L, active.walk.startedAt)
        // Distance from the previous walk must not leak into the new one.
        assertEquals(0.0, active.walk.distanceMeters, 0.0)
    }

    @Test
    fun `resume with clock skew backwards is clamped to zero`() {
        val paused = WalkState.Paused(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalPausedMillis = 100L),
            pausedAt = 2_000L,
        )

        // Resume timestamp BEFORE paused timestamp (clock moved back).
        val (next, _) = WalkReducer.reduce(paused, WalkAction.Resume(at = 1_500L))

        val active = next as WalkState.Active
        assertEquals(100L, active.walk.totalPausedMillis)
    }
}

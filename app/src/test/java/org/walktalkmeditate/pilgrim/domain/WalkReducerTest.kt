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

    // ---- Walk-mode carriage + SEEK_MODE marker (U8) ----------------------

    @Test
    fun `start defaults to wander mode`() {
        val (next, _) = WalkReducer.reduce(
            WalkState.Idle,
            WalkAction.Start(walkId = 1L, at = 100L),
        )
        assertEquals(WalkMode.Wander, (next as WalkState.Active).walk.mode)
    }

    @Test
    fun `seek start from idle carries the mode and emits exactly one SEEK_MODE event`() {
        val (next, effect) = WalkReducer.reduce(
            WalkState.Idle,
            WalkAction.Start(walkId = 7L, at = 300L, mode = WalkMode.Seek),
        )

        assertEquals(WalkMode.Seek, (next as WalkState.Active).walk.mode)
        val persist = effect as WalkEffect.PersistEvent
        assertEquals(7L, persist.walkId)
        assertEquals(WalkEventType.SEEK_MODE, persist.eventType)
        assertEquals(300L, persist.timestamp)
    }

    @Test
    fun `seek start from finished emits the SEEK_MODE marker too`() {
        val finished = WalkState.Finished(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
            endedAt = 1_000L,
        )

        val (next, effect) = WalkReducer.reduce(
            finished,
            WalkAction.Start(walkId = 2L, at = 2_000L, mode = WalkMode.Seek),
        )

        assertEquals(WalkMode.Seek, (next as WalkState.Active).walk.mode)
        assertEquals(
            WalkEffect.PersistEvent(walkId = 2L, eventType = WalkEventType.SEEK_MODE, timestamp = 2_000L),
            effect,
        )
    }

    @Test
    fun `wander start never emits a SEEK_MODE event from either resettable state`() {
        val (_, fromIdle) = WalkReducer.reduce(
            WalkState.Idle,
            WalkAction.Start(walkId = 1L, at = 100L, mode = WalkMode.Wander),
        )
        assertSame(WalkEffect.None, fromIdle)

        val finished = WalkState.Finished(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
            endedAt = 1_000L,
        )
        val (_, fromFinished) = WalkReducer.reduce(
            finished,
            WalkAction.Start(walkId = 2L, at = 2_000L),
        )
        assertSame(WalkEffect.None, fromFinished)
    }

    @Test
    fun `mode rides every transition of the walk lifecycle`() {
        val seekStart = WalkState.Active(
            WalkAccumulator(walkId = 5L, startedAt = 0L, mode = WalkMode.Seek),
        )

        val (paused, _) = WalkReducer.reduce(seekStart, WalkAction.Pause(at = 100L))
        assertEquals(WalkMode.Seek, (paused as WalkState.Paused).walk.mode)

        val (resumed, _) = WalkReducer.reduce(paused, WalkAction.Resume(at = 200L))
        assertEquals(WalkMode.Seek, (resumed as WalkState.Active).walk.mode)

        val (meditating, _) = WalkReducer.reduce(resumed, WalkAction.MeditateStart(at = 300L))
        assertEquals(WalkMode.Seek, (meditating as WalkState.Meditating).walk.mode)

        val (backActive, _) = WalkReducer.reduce(meditating, WalkAction.MeditateEnd(at = 400L))
        val (finished, _) = WalkReducer.reduce(backActive, WalkAction.Finish(at = 500L))
        assertEquals(WalkMode.Seek, (finished as WalkState.Finished).walk.mode)
        assertEquals(WalkMode.Seek, finished.walkModeOrNull)
    }

    @Test
    fun `walkModeFromEvents recognizes a seek by its marker`() {
        fun event(type: WalkEventType) = object : WalkEventLike {
            override val timestamp = 1L
            override val type = type
        }

        assertEquals(WalkMode.Wander, walkModeFromEvents(emptyList()))
        assertEquals(
            WalkMode.Wander,
            walkModeFromEvents(listOf(event(WalkEventType.PAUSED), event(WalkEventType.RESUMED))),
        )
        assertEquals(
            WalkMode.Seek,
            walkModeFromEvents(
                listOf(event(WalkEventType.SEEK_MODE), event(WalkEventType.MEDITATION_START)),
            ),
        )
    }

    @Test
    fun `WalkMode fromWire is forgiving`() {
        assertEquals(WalkMode.Seek, WalkMode.fromWire("Seek"))
        assertEquals(WalkMode.Wander, WalkMode.fromWire("Wander"))
        assertEquals(WalkMode.Wander, WalkMode.fromWire(null))
        assertEquals(WalkMode.Wander, WalkMode.fromWire("seek"))
        assertEquals(WalkMode.Wander, WalkMode.fromWire("Pilgrimage"))
    }
}

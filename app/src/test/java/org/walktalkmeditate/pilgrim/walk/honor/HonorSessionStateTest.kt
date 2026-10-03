// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngine
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.fix

class HonorSessionStateTest {

    private val walk = WalkAccumulator(walkId = 1L, startedAt = 1_000L, totalPausedMillis = 2_000L, totalMeditatedMillis = 4_000L)

    @Test
    fun `the engine clock is walk time less completed pauses, with sittings counted`() {
        assertEquals(8.0, honorEngineSeconds(WalkState.Active(walk), nowMillis = 11_000L)!!, 0.0)
        assertEquals(
            "a sitting keeps the companion walking (spec B §7)",
            8.0,
            honorEngineSeconds(WalkState.Meditating(walk, meditationStartedAt = 9_000L), nowMillis = 11_000L)!!,
            0.0,
        )
    }

    @Test
    fun `the engine clock is frozen through the pause in progress`() {
        val paused = WalkState.Paused(walk, pausedAt = 6_000L)

        assertEquals(3.0, honorEngineSeconds(paused, nowMillis = 6_000L)!!, 0.0)
        assertEquals(3.0, honorEngineSeconds(paused, nowMillis = 60_000L)!!, 0.0)
        assertNull(honorEngineSeconds(WalkState.Idle, nowMillis = 60_000L))
        assertNull(honorEngineSeconds(WalkState.Finished(walk, endedAt = 60_000L), nowMillis = 60_000L))
    }

    @Test
    fun `the rate ladder wraps, and an unknown rate restarts at its second step`() {
        assertEquals(listOf(1.25f, 1.5f, 2f, 1f), VOICE_RATES.map(::nextVoiceRate))
        assertEquals(1.25f, nextVoiceRate(3f))
    }

    @Test
    fun `a reply's index is the n of a voice-n id and nothing else`() {
        assertEquals(12, voiceOriginIndex("voice-12"))
        assertNull(voiceOriginIndex("sit-1"))
        assertNull(voiceOriginIndex("voice-x"))
    }

    @Test
    fun `the water spoken is the walk's water notices, with the row's quiet clock, and an unknown kind is ignored`() {
        val session = HonorSessionEntity(
            walkId = 1L,
            wayId = HonorHarness.STAGE_ID,
            sourceKind = HonorSourceKind.PILGRIMAGE,
            voicesEnabled = false,
            softTapEnabled = false,
            lastNoticeSeconds = 1_820.4,
        )
        val notices = listOf(
            HonorNoticeEntity(1L, HonorNoticeKind.WATER, "wp-osm-water-node1", meters = 280.0, firedAt = 5_000L),
            HonorNoticeEntity(1L, HonorNoticeKind.UNKNOWN, "wp-temple-10", meters = 900.0, firedAt = 9_000L),
        )

        val tracker = session.engineSnapshot(rows = emptyList(), notices = notices).tracker

        assertEquals(setOf("wp-osm-water-node1") to 1_820.4, tracker.firedMarks to tracker.lastNoticeSeconds)
    }

    @Test
    fun `a session row and its moment rows restore the engine where it stood`() {
        val way = HonorHarness.way()
        var now = 5_000_000L
        val live = HonorEngine(way, softTapEnabled = false, voicesEnabled = false, clock = Clock { now })
        live.updateActiveDuration(10.0)
        ((0..19).map { it * 0.0005 } + listOf(0.0098, 0.0099)).forEach {
            now += 1_000
            live.processLocation(fix(it, now))
        }
        val snapshot = live.snapshot()
        val session = HonorSessionEntity(
            walkId = 1L,
            wayId = way.id,
            sourceKind = HonorSourceKind.OWN_WALK,
            voicesEnabled = false,
            softTapEnabled = false,
        )
        val state = snapshot.toEngineState(walkId = 1L)
        val restoredRow = session.copy(
            phase = snapshot.phase,
            startFrac = state.startFrac,
            anchoredByFallback = state.anchoredByFallback,
            anchorActiveSeconds = state.anchorActiveSeconds,
            companionT0Seconds = state.companionT0Seconds,
            progressFrac = state.progressFrac,
            progressHighWater = state.progressHighWater,
            walkedFrac = state.walkedFrac,
            offWaySince = state.offWaySince,
            offWayActiveSeconds = state.offWayActiveSeconds,
            lastReacquireAttempt = state.lastReacquireAttempt,
            softTapSince = state.softTapSince,
            softTapArmed = state.softTapArmed,
            arrivalInsideFixes = state.arrivalInsideFixes,
            lastNoticeSeconds = state.lastNoticeSeconds,
        )
        val rows = snapshot.tracker.reached.map { HonorMomentStateEntity(walkId = 1L, momentId = it, reachedAt = 1L) }

        val revived = HonorEngine(way, softTapEnabled = false, voicesEnabled = false, clock = Clock { now })
        revived.restore(restoredRow.engineSnapshot(rows, notices = emptyList()))
        revived.updateActiveDuration(10.0)

        assertEquals(snapshot, revived.snapshot())
        assertEquals("two inside fixes carried over", 2, snapshot.arrivalInsideFixes)
        now += 1_000
        val next = fix(0.01, now)
        assertEquals(live.processLocation(next), revived.processLocation(next))
        assertEquals(HonorPhase.ARRIVED, revived.phase)
        assertEquals(live.snapshot(), revived.snapshot())
    }
}

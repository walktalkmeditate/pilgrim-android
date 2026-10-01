// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.service.WalkTrackingService.FreshStartAction
import org.walktalkmeditate.pilgrim.service.WalkTrackingService.HonorCommandAction
import org.walktalkmeditate.pilgrim.service.WalkTrackingService.HonorSessionAction
import org.walktalkmeditate.pilgrim.walk.honor.HonorCommand

/**
 * The `:tracker` service's Honor decisions, as pure functions (the
 * [WalkTrackingServiceDecisionTest] precedent): the walk-uuid replay guard
 * on a fresh start, which walks get a session, and the non-redelivery rule
 * for commands. `WalkTrackingHonorPipelineTest` runs the same decisions
 * against a real controller and Room.
 */
class WalkTrackingServiceHonorDecisionTest {

    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val unfinished = Walk(id = 7L, uuid = uuid, startTimestamp = 1_000L)
    private val finished = unfinished.copy(endTimestamp = 2_000L)

    // The walk-uuid replay guard

    @Test
    fun `a start with no uuid inserts, as every non-Honor start does`() {
        assertEquals(FreshStartAction.Insert, WalkTrackingService.decideFreshStart(null, null, hasHonorMarker = false))
    }

    @Test
    fun `a uuid Room has never seen inserts the walk`() {
        assertEquals(FreshStartAction.Insert, WalkTrackingService.decideFreshStart(uuid, null, hasHonorMarker = false))
    }

    @Test
    fun `a redelivered start whose walk is unfinished adopts it`() {
        assertEquals(
            FreshStartAction.AdoptExisting,
            WalkTrackingService.decideFreshStart(uuid, unfinished, hasHonorMarker = false),
        )
    }

    @Test
    fun `a redelivered start whose walk finished stops`() {
        assertEquals(
            FreshStartAction.StopFinished,
            WalkTrackingService.decideFreshStart(uuid, finished, hasHonorMarker = false),
        )
    }

    @Test
    fun `a redelivered start whose walk survives only in its marker stops`() {
        assertEquals(
            FreshStartAction.StopFinished,
            WalkTrackingService.decideFreshStart(uuid, null, hasHonorMarker = true),
        )
    }

    // Which walks get a session

    @Test
    fun `an honor walk in progress, in any state, gets its session`() {
        val walk = WalkAccumulator(walkId = 3L, startedAt = 0L, mode = WalkMode.Honor)
        listOf(
            WalkState.Active(walk),
            WalkState.Paused(walk, pausedAt = 10L),
            WalkState.Meditating(walk, meditationStartedAt = 10L),
        ).forEach { state ->
            assertEquals(HonorSessionAction.Start(3L), WalkTrackingService.decideHonorSessionAction(state))
        }
    }

    @Test
    fun `any other walk, or none, ends the session a cached process holds`() {
        val wander = WalkAccumulator(walkId = 3L, startedAt = 0L)
        val seek = wander.copy(mode = WalkMode.Seek)
        val honor = wander.copy(mode = WalkMode.Honor)
        listOf(
            WalkState.Idle,
            WalkState.Active(wander),
            WalkState.Active(seek),
            WalkState.Finished(honor, endedAt = 10L),
        ).forEach { state ->
            assertEquals(HonorSessionAction.Stop, WalkTrackingService.decideHonorSessionAction(state))
        }
    }

    // Commands never replay

    @Test
    fun `a live command is applied`() {
        assertEquals(
            HonorCommandAction.Apply,
            WalkTrackingService.decideHonorCommandAction(honorEnabled = true, redelivered = false, pipelineActive = true),
        )
    }

    @Test
    fun `a redelivered skip, rate, or reply does nothing`() {
        assertEquals(
            HonorCommandAction.Ignore,
            WalkTrackingService.decideHonorCommandAction(honorEnabled = true, redelivered = true, pipelineActive = true),
        )
    }

    @Test
    fun `a command with no walk pipeline stops the service`() {
        listOf(true, false).forEach { redelivered ->
            assertEquals(
                HonorCommandAction.StopNoPipeline,
                WalkTrackingService.decideHonorCommandAction(
                    honorEnabled = true,
                    redelivered = redelivered,
                    pipelineActive = false,
                ),
            )
        }
    }

    @Test
    fun `with the release flag off a command is never applied`() {
        assertEquals(
            HonorCommandAction.Ignore,
            WalkTrackingService.decideHonorCommandAction(honorEnabled = false, redelivered = false, pipelineActive = true),
        )
    }

    // The command wire

    @Test
    fun `every command kind decodes`() {
        assertEquals(
            HonorCommand.TogglePlayback("voice-1"),
            WalkTrackingService.honorCommandFromExtras(WalkTrackingService.HONOR_COMMAND_TOGGLE_PLAYBACK, "voice-1", 0.0),
        )
        assertEquals(
            HonorCommand.Scrub("voice-2", 0.25),
            WalkTrackingService.honorCommandFromExtras(WalkTrackingService.HONOR_COMMAND_SCRUB, "voice-2", 0.25),
        )
        assertEquals(
            HonorCommand.PauseResume("voice-1"),
            WalkTrackingService.honorCommandFromExtras(WalkTrackingService.HONOR_COMMAND_PAUSE_RESUME, "voice-1", 0.0),
        )
        assertEquals(
            HonorCommand.Skip("voice-1"),
            WalkTrackingService.honorCommandFromExtras(WalkTrackingService.HONOR_COMMAND_SKIP, "voice-1", 0.0),
        )
        assertEquals(
            HonorCommand.CycleRate,
            WalkTrackingService.honorCommandFromExtras(WalkTrackingService.HONOR_COMMAND_CYCLE_RATE, null, 0.0),
        )
        assertEquals(
            HonorCommand.PlayReply("voice-3"),
            WalkTrackingService.honorCommandFromExtras(WalkTrackingService.HONOR_COMMAND_PLAY_REPLY, "voice-3", 0.0),
        )
    }

    @Test
    fun `an unknown kind, or a moment command with no moment, decodes to nothing`() {
        assertNull(WalkTrackingService.honorCommandFromExtras("rewind", "voice-1", 0.0))
        assertNull(WalkTrackingService.honorCommandFromExtras(null, null, 0.0))
        assertNull(WalkTrackingService.honorCommandFromExtras(WalkTrackingService.HONOR_COMMAND_TOGGLE_PLAYBACK, null, 0.0))
        assertNull(WalkTrackingService.honorCommandFromExtras(WalkTrackingService.HONOR_COMMAND_PLAY_REPLY, null, 0.0))
        assertNull(WalkTrackingService.honorCommandFromExtras(WalkTrackingService.HONOR_COMMAND_PAUSE_RESUME, null, 0.0))
        assertNull(WalkTrackingService.honorCommandFromExtras(WalkTrackingService.HONOR_COMMAND_SKIP, null, 0.0))
    }
}

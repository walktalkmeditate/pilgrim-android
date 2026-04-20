// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.voiceguide.PromptDensity
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePrompt
import org.walktalkmeditate.pilgrim.domain.Clock

class VoiceGuideSchedulerTest {

    private lateinit var clock: MutableClock

    @Before fun setUp() { clock = MutableClock() }

    private fun prompt(
        id: String,
        seq: Int = 0,
        phase: String? = null,
    ) = VoiceGuidePrompt(
        id = id,
        seq = seq,
        durationSec = 1.0,
        fileSizeBytes = 100L,
        r2Key = "p/$id.aac",
        phase = phase,
    )

    private fun density(
        initialDelaySec: Int = 60,
        densityMinSec: Int = 120,
        densityMaxSec: Int = 180,
    ) = PromptDensity(
        densityMinSec = densityMinSec,
        densityMaxSec = densityMaxSec,
        minSpacingSec = 0,
        initialDelaySec = initialDelaySec,
        walkEndBufferSec = 0,
    )

    private fun buildWalk(
        prompts: List<VoiceGuidePrompt>,
        density: PromptDensity = density(),
        random: (Int) -> Int = { 0 },
    ) = VoiceGuideScheduler(
        context = VoiceGuideScheduler.SchedulerContext.Walk,
        prompts = prompts,
        scheduling = density,
        clock = clock,
        random = random,
    )

    @Test fun `decide returns null before start()`() {
        val sched = buildWalk(listOf(prompt("a")))
        assertNull(sched.decide(isPaused = false, isRecordingVoice = false))
    }

    @Test fun `decide returns null before initialDelaySec elapses`() {
        val sched = buildWalk(listOf(prompt("a")), density(initialDelaySec = 60))
        sched.start()
        clock.advanceSec(59)
        assertNull(sched.decide(isPaused = false, isRecordingVoice = false))
    }

    @Test fun `decide returns a prompt at exactly initialDelaySec`() {
        val sched = buildWalk(listOf(prompt("a")), density(initialDelaySec = 60))
        sched.start()
        clock.advanceSec(60)
        assertEquals("a", sched.decide(false, false)?.id)
    }

    @Test fun `after markPlayed, decide returns null until nextIntervalSec`() {
        val sched = buildWalk(
            listOf(prompt("a"), prompt("b", seq = 1)),
            density(initialDelaySec = 60, densityMinSec = 120, densityMaxSec = 180),
            random = { 0 }, // next interval = densityMinSec exactly
        )
        sched.start()
        clock.advanceSec(60)
        val first = sched.decide(false, false)!!
        sched.markPlaybackStarted()
        sched.markPlayed(first.id)
        // 119 seconds after markPlayed: still silent
        clock.advanceSec(119)
        assertNull(sched.decide(false, false))
        // 120 seconds after markPlayed: next prompt fires
        clock.advanceSec(1)
        assertEquals("b", sched.decide(false, false)?.id)
    }

    @Test fun `phase settling selects only settling-phased prompts when any exist`() {
        val sched = buildWalk(
            listOf(
                prompt("open1", seq = 0, phase = "settling"),
                prompt("deep1", seq = 1, phase = "deepening"),
            ),
            density(initialDelaySec = 0),
        )
        sched.start()
        // elapsed 60s — well within walk's 20-min settling threshold
        clock.advanceSec(60)
        val picked = sched.decide(false, false)
        assertEquals("open1", picked?.id)
    }

    @Test fun `phase with no matching prompts falls back to full pool`() {
        val sched = buildWalk(
            listOf(
                prompt("deep1", seq = 0, phase = "deepening"),
                prompt("deep2", seq = 1, phase = "deepening"),
            ),
            density(initialDelaySec = 0),
        )
        sched.start()
        // In settling phase but no settling-phased prompts — pool falls back
        clock.advanceSec(60)
        val picked = sched.decide(false, false)
        assertNotNull(picked)
        assertEquals("deep1", picked?.id)
    }

    @Test fun `prompts with null phase are always selectable`() {
        val sched = buildWalk(
            listOf(prompt("always", seq = 0, phase = null)),
            density(initialDelaySec = 0),
        )
        sched.start()
        clock.advanceSec(60)
        assertEquals("always", sched.decide(false, false)?.id)
    }

    @Test fun `cycling — after all played, history resets and cycles`() {
        val sched = buildWalk(
            listOf(prompt("a", seq = 0), prompt("b", seq = 1)),
            density(initialDelaySec = 0, densityMinSec = 1, densityMaxSec = 2),
            random = { 0 },
        )
        sched.start()
        clock.advanceSec(0)
        val p1 = sched.decide(false, false)!!
        sched.markPlaybackStarted(); sched.markPlayed(p1.id)
        clock.advanceSec(1)
        val p2 = sched.decide(false, false)!!
        sched.markPlaybackStarted(); sched.markPlayed(p2.id)
        assertEquals(setOf("a", "b"), setOf(p1.id, p2.id))
        // Now both are played. Next decide should cycle (after interval).
        clock.advanceSec(1)
        val p3 = sched.decide(false, false)!!
        // After reset, first of pool is "a" again.
        assertEquals("a", p3.id)
    }

    @Test fun `setPostMeditationSilence blocks decide for the configured window`() {
        val sched = buildWalk(listOf(prompt("a")), density(initialDelaySec = 0))
        sched.start()
        sched.setPostMeditationSilence(durationSec = 30)
        // 15s in: silenced
        clock.advanceSec(15)
        assertNull(sched.decide(false, false))
        // 30s in: still silenced (strict less-than)
        clock.advanceSec(15)
        // At exactly silenceUntil, `now < silenceUntil` is false → allowed.
        assertNotNull(sched.decide(false, false))
    }

    @Test fun `isPaused short-circuits decide`() {
        val sched = buildWalk(listOf(prompt("a")), density(initialDelaySec = 0))
        sched.start()
        clock.advanceSec(60)
        assertNull(sched.decide(isPaused = true, isRecordingVoice = false))
    }

    @Test fun `isRecordingVoice short-circuits decide`() {
        val sched = buildWalk(listOf(prompt("a")), density(initialDelaySec = 0))
        sched.start()
        clock.advanceSec(60)
        assertNull(sched.decide(isPaused = false, isRecordingVoice = true))
    }

    @Test fun `isPlaying short-circuits decide`() {
        val sched = buildWalk(listOf(prompt("a")), density(initialDelaySec = 0))
        sched.start()
        clock.advanceSec(60)
        sched.markPlaybackStarted()
        assertNull(sched.decide(false, false))
    }

    @Test fun `markPlaybackAborted clears isPlaying without marking prompt played`() {
        val sched = buildWalk(
            listOf(prompt("a", seq = 0), prompt("b", seq = 1)),
            density(initialDelaySec = 0),
        )
        sched.start()
        clock.advanceSec(60)
        val first = sched.decide(false, false)!!
        sched.markPlaybackStarted()
        sched.markPlaybackAborted()
        // Without markPlayed, lastPlayedMillis is not set → no interval guard.
        // And "a" is still unplayed, so next decide picks it again.
        val retry = sched.decide(false, false)
        assertEquals(first.id, retry?.id)
    }

    @Test fun `markPlayed draws next interval within density bounds`() {
        val sched = buildWalk(
            listOf(prompt("a", seq = 0), prompt("b", seq = 1)),
            density(initialDelaySec = 0, densityMinSec = 100, densityMaxSec = 200),
            random = { bound -> bound - 1 }, // max end of range
        )
        sched.start()
        clock.advanceSec(0)
        val p1 = sched.decide(false, false)!!
        sched.markPlaybackStarted(); sched.markPlayed(p1.id)
        // Max random draw → nextIntervalSec = min + (span - 1) = 100 + 99 = 199
        clock.advanceSec(198)
        assertNull(sched.decide(false, false))
        clock.advanceSec(1)
        assertNotNull(sched.decide(false, false))
    }

    /** Simple advanceable clock for direct time control. */
    private class MutableClock : Clock {
        private var nowMillis: Long = 1_700_000_000_000L
        override fun now(): Long = nowMillis
        fun advanceSec(seconds: Int) { nowMillis += seconds * 1_000L }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import kotlin.random.Random
import org.walktalkmeditate.pilgrim.data.voiceguide.PromptDensity
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePrompt
import org.walktalkmeditate.pilgrim.domain.Clock

/**
 * Pure-Kotlin port of iOS's `VoiceGuideScheduler`. Decides whether
 * (and which prompt) to play on each 30-second tick during a walk
 * or meditation session. The caller owns the tick loop; this class
 * is a state machine with a single `decide()` decision function.
 *
 * Algorithm (matching iOS 1:1):
 *  - After `start()`, wait `initialDelaySec` before the first play.
 *  - After each play, wait a random interval in
 *    `[densityMinSec, densityMaxSec]` before the next candidate.
 *  - Prompts are selected by phase (settling / deepening / closing),
 *    with unplayed prompts preferred; when all are played the
 *    history resets and cycling begins.
 *  - `setPostMeditationSilence` adds a one-shot forced-quiet window,
 *    used by the walk-context scheduler after meditation ends.
 *
 * Not thread-safe — all access must come from the same coroutine.
 */
class VoiceGuideScheduler(
    private val context: SchedulerContext,
    prompts: List<VoiceGuidePrompt>,
    private val scheduling: PromptDensity,
    private val clock: Clock,
    private val random: (bound: Int) -> Int = { Random.Default.nextInt(it) },
) {
    enum class SchedulerContext(val settlingSec: Int, val closingSec: Int) {
        /** 20-minute settling, 45-minute closing — hardcoded in iOS. */
        Walk(settlingSec = 20 * 60, closingSec = 45 * 60),

        /** 5-minute settling, 15-minute closing — hardcoded in iOS. */
        Meditation(settlingSec = 5 * 60, closingSec = 15 * 60),
    }

    private enum class Phase { Settling, Deepening, Closing }

    private val prompts: List<VoiceGuidePrompt> = prompts.sortedBy { it.seq }

    private var sessionStartMillis: Long? = null
    private var lastPlayedMillis: Long? = null
    private var nextIntervalSec: Int = scheduling.initialDelaySec
    private val played = mutableSetOf<String>()
    private var isPlaying = false
    private var silenceUntilMillis: Long? = null

    fun start() {
        sessionStartMillis = clock.now()
    }

    /**
     * Block plays for the next [durationSec] seconds. Called by the
     * orchestrator on `Meditating → Active` so the walk guide doesn't
     * resume mid-breath.
     */
    fun setPostMeditationSilence(durationSec: Int) {
        silenceUntilMillis = clock.now() + durationSec * 1_000L
    }

    fun markPlaybackStarted() {
        isPlaying = true
    }

    /**
     * Completion path. Records the played id, clears the isPlaying
     * flag, advances the last-played timestamp, and draws the next
     * interval.
     */
    fun markPlayed(promptId: String) {
        played += promptId
        isPlaying = false
        lastPlayedMillis = clock.now()
        val span = (scheduling.densityMaxSec - scheduling.densityMinSec).coerceAtLeast(1)
        nextIntervalSec = scheduling.densityMinSec + random(span)
    }

    /**
     * Error/abort path. Clears `isPlaying` without marking the prompt
     * played — so the same prompt can be retried on the next tick.
     * Called by the player's focus-loss / error listeners.
     */
    fun markPlaybackAborted() {
        isPlaying = false
    }

    /**
     * Core decision. Returns a prompt to play right now, or null if
     * the scheduler should keep waiting. The caller must follow a
     * non-null return with [markPlaybackStarted] before invoking the
     * player, and ensure [markPlayed] or [markPlaybackAborted] is
     * called on completion.
     */
    fun decide(isPaused: Boolean, isRecordingVoice: Boolean): VoiceGuidePrompt? {
        val started = sessionStartMillis ?: return null
        if (isPaused || isRecordingVoice || isPlaying) return null
        val now = clock.now()
        silenceUntilMillis?.let { if (now < it) return null }
        val elapsedSec = ((now - started) / 1_000L).toInt()
        if (elapsedSec < scheduling.initialDelaySec) return null
        lastPlayedMillis?.let {
            val sinceLastSec = ((now - it) / 1_000L).toInt()
            if (sinceLastSec < nextIntervalSec) return null
        }
        return nextPrompt(elapsedSec)
    }

    private fun phaseFor(elapsedSec: Int): Phase = when {
        elapsedSec < context.settlingSec -> Phase.Settling
        elapsedSec >= context.closingSec -> Phase.Closing
        else -> Phase.Deepening
    }

    private fun nextPrompt(elapsedSec: Int): VoiceGuidePrompt? {
        val phaseName = phaseFor(elapsedSec).name.lowercase()
        val phaseFiltered = prompts.filter { it.phase == null || it.phase == phaseName }
        val pool = phaseFiltered.ifEmpty { prompts }
        // Prefer unplayed within the phase pool.
        pool.firstOrNull { it.id !in played }?.let { return it }
        // Fallback: first unplayed in the full sorted list.
        prompts.firstOrNull { it.id !in played }?.let { return it }
        // Cycle: clear history, return the phase-pool's first prompt.
        played.clear()
        return pool.firstOrNull()
    }
}

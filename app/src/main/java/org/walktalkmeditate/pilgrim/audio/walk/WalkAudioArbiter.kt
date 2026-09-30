// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.honor.WayVoicePlaybackListener
import org.walktalkmeditate.pilgrim.audio.honor.WayVoicePlayer
import org.walktalkmeditate.pilgrim.audio.soundscape.WayVoiceSoundscapeDuck
import org.walktalkmeditate.pilgrim.data.whisper.WhisperDefinition
import org.walktalkmeditate.pilgrim.data.whisper.WhisperPlayer
import org.walktalkmeditate.pilgrim.walk.honor.HonorExternalGates
import org.walktalkmeditate.pilgrim.walk.honor.HonorGatePort
import org.walktalkmeditate.pilgrim.walk.honor.SoundscapeDuckPort
import org.walktalkmeditate.pilgrim.walk.honor.WayVoiceListener
import org.walktalkmeditate.pilgrim.walk.honor.WayVoicePort

/**
 * The UI process's two audio gates as `:tracker` last heard them: a guide
 * [prompt] sounding (iOS `VoiceGuidePlayer.isPlaying`), and a talk or
 * reply [recording].
 */
data class UiAudioGates(
    val prompt: Boolean = false,
    val recording: Boolean = false,
)

/**
 * Where [WalkAudioArbiter] reads the UI's gates. U18's UI gate model
 * implements it; the Binder tokens, sequence ids, and the re-send after a
 * gate generation bump live there, not here.
 *
 * - [gates] is a level: a prompt replacing another keeps
 *   [UiAudioGates.prompt] true, as iOS re-checks `isPlaying` on every end
 *   signal before it releases anything (`WayVoicePlayer.swift:184-190@7c200bf`).
 * - Publish a prompt before its first sound (spec, placement table, "Voice guide").
 * - One value that ends a prompt and starts a recording is applied prompt
 *   first, as iOS's recording stops the prompt before the recording gate
 *   closes (the correction to iOS C-D1, pilgrim-ios #101).
 * - Until the UI re-sends after a revival, report both held.
 */
interface UiAudioGateSource {

    val gates: StateFlow<UiAudioGates>
}

/**
 * The walk's audio order in `:tracker` (plan U18; parity spec C §2–§6,
 * corrections 11 and 12): a guide prompt outranks a Way voice, which
 * outranks a whisper. iOS has no arbiter, only each lower player checking
 * the higher ones at its own entry; this is the same set of rules, with
 * the guide heard across the process line through [UiAudioGateSource].
 *
 * | When | The Way voice | A whisper | The soundscape |
 * |---|---|---|---|
 * | a voice is played while a prompt sounds | parked in one slot, newest wins | — | untouched |
 * | a voice starts | — | an audible one is cut, a parked one stays | ducked to 0.15 over 0.5 s, unless the run holds it already |
 * | a whisper comes while a prompt sounds or a voice is loaded | — | parked in one slot, newest wins, no expiry, no distance | — |
 * | a prompt starts | a sounding voice pauses, held by the guide; its duck is handed over | an audible one is cut, a parked one dropped | stays ducked |
 * | a prompt ends | the parked voice starts, else a guide-held voice resumes | then a parked one plays, if no voice is loaded | the handed-over duck is restored, and re-taken by a voice that starts or resumes |
 * | a voice resumes | — | — | re-ducked if its duck was given away, unless a prompt sounds (iOS C-D3) |
 * | a run of voices ends | — | a parked one plays | restored over 0.5 s to the level captured at the run's start |
 *
 * A run ends when the session reports the player empty (a stop, or
 * [restoreAfterWayVoice] after an end) and no voice follows within
 * [RUN_SETTLE_MILLIS]: iOS's whisper sink reads the Way voice's falling
 * edge one main-queue hop later, after the next voice of a run has
 * started (`AudioPriorityQueue.swift:22-35@7c200bf`), and Android keeps
 * the soundscape ducked across that chained start (spec C §6.2).
 *
 * Walker plays (card, chip, scrub, reply) share the one entry, so only a
 * prompt parks them; the engine's gates never do (spec C §4.4). The
 * arbiter also publishes the engine's two outside gates ([HonorGatePort]):
 * the UI's recording, and a whisper actually playing here.
 *
 * A whisper reaches the slot only once its file is on disk, and a cut
 * stops only the audible one: a whisper still downloading when a voice or
 * a prompt starts parks when its download lands, as iOS's does
 * (`WhisperPlayer.swift:142-156@7c200bf`).
 *
 * Every step runs on [scope], the main thread in production, so the
 * players' state is read where it changes. Only `:tracker` with the
 * release flag on builds it.
 */
@Singleton
class WalkAudioArbiter internal constructor(
    private val voicePlayer: WayVoicePlayer,
    private val soundscape: WayVoiceSoundscapeDuck,
    private val whisperPlayer: WhisperPlayer,
    uiGates: UiAudioGateSource,
    private val scope: CoroutineScope,
) : WayVoicePort, SoundscapeDuckPort, HonorGatePort {

    @Inject
    constructor(
        voicePlayer: WayVoicePlayer,
        soundscape: WayVoiceSoundscapeDuck,
        whisperPlayer: WhisperPlayer,
        uiGates: UiAudioGateSource,
    ) : this(
        voicePlayer = voicePlayer,
        soundscape = soundscape,
        whisperPlayer = whisperPlayer,
        uiGates = uiGates,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private var ui: UiAudioGates = uiGates.gates.value
    private var whisperAudible: Boolean = whisperPlayer.isAnyChannelPlaying.value
    private val externalGates = MutableStateFlow(HonorExternalGates(ui.recording, whisperAudible))

    override val gates: StateFlow<HonorExternalGates> = externalGates.asStateFlow()

    /** The play the player holds: iOS `player != nil`. */
    private var loaded: Loaded? = null

    /** iOS `pending`: the one voice waiting behind a prompt. */
    private var parked: Request? = null

    /** iOS `pausedByGuide`: only a prompt's own pause, which any later pause supersedes. */
    private var pausedByGuide = false

    /** iOS `WayVoicePlayer.preDuckVolume`: the voice owns the duck, ducked from this level. */
    private var voiceBefore: Float? = null

    /** What a voice handed a starting prompt (iOS `pauseForGuide()`'s return): the guide owns the duck. */
    private var guideBefore: Float? = null

    private var runEnding: Job? = null

    /** iOS `pendingWhisperURL`. */
    private var parkedWhisper: WhisperDefinition? = null

    init {
        scope.launch { uiGates.gates.collect { onUiGates(it) } }
        scope.launch { whisperPlayer.isAnyChannelPlaying.collect { onWhisperAudible(it) } }
    }

    /** [gain] is the session's, 1 or ambience's 0.5, of the voice level (iOS `voiceVolume(for:)`). */
    override fun play(file: File, gain: Float, listener: WayVoiceListener) = onArbiter {
        playVoice(Request(file, WAY_VOICE_VOLUME * gain, listener))
    }

    /** iOS `playReply(url:)` (`ActiveWalkViewModel+Replies.swift:65-75@7c200bf`): the voice is given up, the reply at full level. */
    override fun playReply(file: File, listener: WayVoiceListener) = onArbiter {
        stopVoice()
        playVoice(Request(file, WAY_VOICE_VOLUME, listener))
    }

    /**
     * iOS `pause()` (`WayVoicePlayer.swift:66-73@7c200bf`): the caller's
     * word from here on, so a prompt ending never resumes it. A voice parked
     * behind a prompt is not touched, and starts when the prompt ends, even
     * inside the gate that paused it (iOS C-D1, pilgrim-ios #101).
     */
    override fun pause() = onArbiter {
        pausedByGuide = false
        if (loaded != null) voicePlayer.pause()
    }

    /**
     * iOS `resume()`, whoever paused the voice before: a gate reopening
     * resumes a voice the walker paused (iOS C-D6, pilgrim-ios #101).
     */
    override fun resume() = onArbiter { resumeVoice() }

    override fun stop() = onArbiter { stopVoice() }

    /** A no-op on a voice parked behind a prompt, as iOS's is with no player loaded. */
    override fun seek(fraction: Double) = onArbiter {
        if (loaded != null) voicePlayer.seek(fraction)
    }

    override fun setRate(rate: Float) = onArbiter { voicePlayer.setRate(rate) }

    /** The duck lands when the voice actually starts, never while it waits behind a prompt (spec C §6.1). */
    override fun duckForWayVoice() = Unit

    /** The session's word that the player is empty: the run ends unless a voice follows. */
    override fun restoreAfterWayVoice() = onArbiter {
        if (loaded == null) endRunSoon()
    }

    /**
     * A whisper from `:tracker`'s autoplay. iOS's `WhisperPlayer.play`
     * downloads first and only then calls `AudioPriorityQueue.playWhisper`
     * (`AudioPriorityQueue.swift:45-52@7c200bf`), so the slot decides when
     * the file lands.
     */
    fun requestWhisper(definition: WhisperDefinition) {
        whisperPlayer.fetch(definition) { landed -> onArbiter { admitWhisper(landed) } }
    }

    private fun admitWhisper(definition: WhisperDefinition) {
        if (ui.prompt || voiceHoldsWhispers()) {
            parkedWhisper = definition
        } else {
            parkedWhisper = null
            whisperPlayer.play(definition)
        }
    }

    private fun playVoice(request: Request) {
        if (ui.prompt) {
            parked = request
            return
        }
        startVoice(request)
    }

    /** iOS `start(url:volume:)` (`WayVoicePlayer.swift:145-178@7c200bf`). */
    private fun startVoice(request: Request) {
        parked = null
        pausedByGuide = false
        runEnding?.cancel()
        runEnding = null
        // `interruptForWayVoice()`: an audible whisper is gone, never re-parked;
        // a parked one outlives the whole run (`AudioPriorityQueue.swift:75-84@7c200bf`).
        whisperPlayer.cut()
        if (voiceBefore == null) takeDuck()
        val next = Loaded(request.listener)
        loaded = next
        voicePlayer.play(request.file, request.volume, next)
    }

    private fun takeDuck() {
        voiceBefore = soundscape.targetVolume
        soundscape.holdDuck(WAY_VOICE_DUCK_LEVEL)
    }

    /** iOS `resume()` (`WayVoicePlayer.swift:75-87@7c200bf`). */
    private fun resumeVoice() {
        if (loaded == null) return
        // While a prompt sounds, the guide owns the duck: iOS skips it here and
        // plays anyway, over the prompt, and nothing re-ducks once the prompt
        // restores the soundscape (iOS C-D3, pilgrim-ios #101).
        if (voiceBefore == null && !ui.prompt) takeDuck()
        voicePlayer.resume()
    }

    /** iOS `stop()`: the parked voice goes too, and nothing is reported. */
    private fun stopVoice() {
        parked = null
        pausedByGuide = false
        if (loaded != null) {
            loaded = null
            voicePlayer.stop()
        }
        endRunSoon()
    }

    /** iOS `finish(notify: true)`; the duck and a parked whisper wait for the session's turn. */
    private fun voiceLeft(play: Loaded, report: (WayVoiceListener) -> Unit) {
        if (loaded !== play) return
        loaded = null
        pausedByGuide = false
        report(play.listener)
    }

    private fun endRunSoon() {
        if (runEnding?.isActive == true) return
        runEnding = scope.launch {
            delay(RUN_SETTLE_MILLIS)
            runEnding = null
            endRun()
        }
    }

    /**
     * The rest of iOS `finish` (`WayVoicePlayer.swift:219-237@7c200bf`), then
     * the whisper's wait on the Way voice's falling edge. Nothing here clears
     * a parked whisper, so one parked at Finish plays after the walk (iOS
     * C-D2, pilgrim-ios #103).
     */
    private fun endRun() {
        if (loaded != null) return
        voiceBefore?.let { before ->
            voiceBefore = null
            soundscape.releaseDuck(before)
        }
        releaseParkedWhisper()
    }

    private fun onUiGates(next: UiAudioGates) {
        val before = ui
        ui = next
        if (!before.prompt && next.prompt) promptStarted()
        if (before.prompt && !next.prompt) promptEnded()
        publishGates()
    }

    /** iOS `VoiceGuidePlayer.startPlayback` (`VoiceGuidePlayer.swift:32-46@7c200bf`), before the prompt's first sound. */
    private fun promptStarted() {
        // `interruptForVoiceGuide()` (`AudioPriorityQueue.swift:66-73@7c200bf`).
        parkedWhisper = null
        whisperPlayer.cut()
        handOverToGuide()
    }

    /** iOS `pauseForGuide()` (`WayVoicePlayer.swift:89-107@7c200bf`). */
    private fun handOverToGuide() {
        if (loaded == null || pausedByGuide) return
        if (voicePlayer.isPlaying) {
            voicePlayer.pause()
            pausedByGuide = true
        }
        // Both ducks are the same level, so the soundscape stays where it is.
        voiceBefore?.let { before ->
            voiceBefore = null
            guideBefore = before
        }
    }

    /**
     * The guide's restore (`VoiceGuidePlayer.swift:108-114@7c200bf`), then
     * iOS `guideDidFinish()` (`WayVoicePlayer.swift:184-207@7c200bf`), and
     * only then the whisper: the Way voice goes first (spec C §2.3).
     */
    private fun promptEnded() {
        guideBefore?.let { inherited ->
            guideBefore = null
            soundscape.releaseDuck(inherited)
        }
        val waiting = parked
        if (waiting != null) {
            startVoice(waiting)
        } else if (pausedByGuide) {
            pausedByGuide = false
            resumeVoice()
        }
        releaseParkedWhisper()
    }

    private fun releaseParkedWhisper() {
        val whisper = parkedWhisper ?: return
        if (ui.prompt || voiceHoldsWhispers()) return
        parkedWhisper = null
        whisperPlayer.play(whisper)
    }

    /** iOS `isPlayingWayVoice`, true while a loaded voice is paused or held too, and between the voices of one run. */
    private fun voiceHoldsWhispers(): Boolean = loaded != null || runEnding?.isActive == true

    private fun onWhisperAudible(audible: Boolean) {
        whisperAudible = audible
        publishGates()
    }

    private fun publishGates() {
        externalGates.value = HonorExternalGates(recording = ui.recording, externalAudio = whisperAudible)
    }

    private fun onArbiter(step: () -> Unit) {
        scope.launch { step() }
    }

    private class Request(val file: File, val volume: Float, val listener: WayVoiceListener)

    /** One play; the player's report counts only while it is still [loaded]. */
    private inner class Loaded(val listener: WayVoiceListener) : WayVoicePlaybackListener {

        override fun onEnded() = onArbiter { voiceLeft(this@Loaded) { it.onFinished() } }

        override fun onFailed() = onArbiter { voiceLeft(this@Loaded) { it.onFailed() } }
    }

    companion object {
        /** iOS `voiceGuideVolume`'s default (`UserPreferences.swift:57@7c200bf`); a constant until pilgrim-android #258. */
        const val WAY_VOICE_VOLUME = 0.8f

        /** iOS `voiceGuideDuckLevel`'s default (`UserPreferences.swift:58@7c200bf`); a constant until pilgrim-android #258. */
        const val WAY_VOICE_DUCK_LEVEL = 0.15f

        /** Long enough for the session's next play in the same turn, short enough to go unheard. */
        const val RUN_SETTLE_MILLIS = 250L
    }
}

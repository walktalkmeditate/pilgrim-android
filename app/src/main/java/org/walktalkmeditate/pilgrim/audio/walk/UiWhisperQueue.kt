// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuideOrchestrator
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.whisper.WhisperDefinition
import org.walktalkmeditate.pilgrim.data.whisper.WhisperPlayer

/** Whether `:tracker`'s player holds a Way voice, as the session last persisted it. */
internal fun HonorDao.wayVoiceLoaded(): Flow<Boolean> =
    observeWalkInProgressAudio().map { it?.playingMomentId != null }.distinctUntilChanged()

/**
 * The UI process's whisper slot, the counterpart of the one in
 * [WalkAudioArbiter]: a whisper the walker starts here (a pin tap, the
 * placement confirmation, Seek's reveal) goes through iOS's one-slot queue
 * like every in-walk whisper on iOS (parity spec C §5, resolution 3).
 *
 * - Parked, newest winning, while this process's guide prompt sounds or
 *   the Way voice `:tracker`'s player holds, as the session last
 *   persisted it (playing, paused, or held by the guide); it plays once
 *   both are quiet, with no expiry and no distance check.
 * - A prompt starting cuts the audible whisper and drops the parked one;
 *   a Way voice starting cuts the audible one and keeps the parked one.
 * - A whisper still downloading lands in the slot when its file arrives.
 *
 * The placement sheet's preview never comes here: iOS plays it on its own
 * channel, unqueued. With the release flag off every play goes straight to
 * the player and nothing is observed.
 *
 * The persisted Way-voice state has no row for "your reply", which iOS's
 * player also holds whispers behind; a whisper started here during a reply
 * is not parked.
 */
@Singleton
class UiWhisperQueue internal constructor(
    private val whisperPlayer: WhisperPlayer,
    private val releaseFlags: ReleaseFlags,
    promptSounding: Flow<Boolean>,
    wayVoiceLoaded: Flow<Boolean>,
    private val scope: CoroutineScope,
) {

    @Inject
    constructor(
        whisperPlayer: WhisperPlayer,
        releaseFlags: ReleaseFlags,
        voiceGuide: VoiceGuideOrchestrator,
        honorDao: HonorDao,
    ) : this(
        whisperPlayer = whisperPlayer,
        releaseFlags = releaseFlags,
        promptSounding = voiceGuide.promptSounding,
        wayVoiceLoaded = honorDao.wayVoiceLoaded(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private var prompt = false
    private var wayVoice = false
    private var parked: WhisperDefinition? = null

    init {
        if (releaseFlags.honor) {
            scope.launch { promptSounding.collect { onPrompt(it) } }
            scope.launch { wayVoiceLoaded.collect { onWayVoice(it) } }
        }
    }

    fun play(definition: WhisperDefinition) {
        if (!releaseFlags.honor) {
            whisperPlayer.play(definition)
            return
        }
        whisperPlayer.fetch(definition) { landed -> scope.launch { land(landed) } }
    }

    private fun land(definition: WhisperDefinition) {
        if (prompt || wayVoice) {
            parked = definition
        } else {
            parked = null
            whisperPlayer.play(definition)
        }
    }

    private fun onPrompt(sounding: Boolean) {
        val before = prompt
        prompt = sounding
        if (!before && sounding) {
            parked = null
            whisperPlayer.cut()
        }
        if (before && !sounding) release()
    }

    private fun onWayVoice(loaded: Boolean) {
        val before = wayVoice
        wayVoice = loaded
        if (!before && loaded) whisperPlayer.cut()
        if (before && !loaded) release()
    }

    private fun release() {
        val whisper = parked ?: return
        if (prompt || wayVoice) return
        parked = null
        whisperPlayer.play(whisper)
    }

    internal companion object {
        /** Plays at once, as with the flag off: the default for view models built without Hilt. */
        fun unqueued(whisperPlayer: WhisperPlayer) = UiWhisperQueue(
            whisperPlayer = whisperPlayer,
            releaseFlags = object : ReleaseFlags {
                override val honor = false
            },
            promptSounding = emptyFlow(),
            wayVoiceLoaded = emptyFlow(),
            scope = CoroutineScope(SupervisorJob()),
        )
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.TalkRecordingActive
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuidePromptGate
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.seek.SeekDao
import org.walktalkmeditate.pilgrim.data.whisper.WhisperPlayer
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher

/** When the UI owes `:tracker` its gates again: a new walk in progress, or its session's bumped gate generation. */
data class UiAudioGateRefresh(val walkId: Long, val gateGeneration: Long?)

internal fun HonorDao.uiAudioGateRefreshes(): Flow<UiAudioGateRefresh?> =
    observeWalkInProgressAudio().map { walk -> walk?.let { UiAudioGateRefresh(it.walkId, it.gateGeneration) } }

/**
 * [uiAudioGateRefreshes], with a seek walk's session generation where the
 * walk has no Honor session: Seek in `:tracker` reads the same gates, and
 * bumps its own generation at each session start and revival (plan U25).
 */
internal fun uiAudioGateRefreshes(honorDao: HonorDao, seekDao: SeekDao): Flow<UiAudioGateRefresh?> =
    combine(honorDao.uiAudioGateRefreshes(), seekDao.observeLiveSessionKey()) { walk, seek ->
        walk?.let {
            val seekGeneration = seek?.takeIf { key -> key.walkId == it.walkId }?.gateGeneration
            it.copy(gateGeneration = it.gateGeneration ?: seekGeneration)
        }
    }

/**
 * The UI process's side of the walk audio gates (plan U18). One observer
 * of the guide's prompt level, which the orchestrator reports before its
 * player starts a prompt, one of the recorder's recording flag, which
 * falls on every stop (the walk-end auto-stop included), and one of the
 * whisper player's either-channel flag, which Seek's sonar in `:tracker`
 * reads (plan U25); each change goes to `:tracker`'s [UiAudioGate] as a
 * gate intent.
 *
 * - A start carries a fresh Binder, which `:tracker` links to its death
 *   to hear this process die; it is kept here while the gate holds.
 * - Numbers rise across UI restarts: the boot clock both processes share
 *   is their floor, so a restarted UI never repeats a number the tracker
 *   has seen.
 * - Every gate is sent again, with fresh Binders, whenever the walk in
 *   progress or its session's gate generation changes (read from Room,
 *   the tracker's only way to the UI), which `:tracker` bumps at every
 *   session start and revival, an honor walk's or a seek walk's.
 *
 * With the release flag off nothing is ever sent.
 */
@Singleton
class UiAudioGatePublisher internal constructor(
    private val releaseFlags: ReleaseFlags,
    private val recording: StateFlow<Boolean>,
    private val refreshes: Flow<UiAudioGateRefresh?>,
    private val send: (kind: UiAudioGateKind, held: Boolean, seq: Long, token: IBinder?) -> Unit,
    private val bootNanos: () -> Long,
    private val scope: CoroutineScope,
    private val whisper: Flow<Boolean> = flowOf(false),
) : VoiceGuidePromptGate {

    @Inject
    constructor(
        releaseFlags: ReleaseFlags,
        @TalkRecordingActive recording: StateFlow<@JvmSuppressWildcards Boolean>,
        honorDao: HonorDao,
        seekDao: SeekDao,
        walkActionPublisher: WalkActionPublisher,
        whisperPlayer: WhisperPlayer,
    ) : this(
        releaseFlags = releaseFlags,
        recording = recording,
        refreshes = uiAudioGateRefreshes(honorDao, seekDao),
        send = walkActionPublisher::publishUiAudioGate,
        bootNanos = SystemClock::elapsedRealtimeNanos,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        whisper = whisperPlayer.isAnyChannelPlaying,
    )

    private val started = AtomicBoolean(false)
    private val lock = Any()
    private val current = UiAudioGateKind.entries.associateWith { false }.toMutableMap()
    private val sent = UiAudioGateKind.entries.associateWith { false }.toMutableMap()
    private val tokens = mutableMapOf<UiAudioGateKind, IBinder>()
    private var lastSeq = 0L

    /** Once per UI process, before the guide can play; a no-op with the release flag off. */
    fun start() {
        if (!releaseFlags.honor || !started.compareAndSet(false, true)) return
        scope.launch { recording.collect { held -> onChange(UiAudioGateKind.RECORDING, held) } }
        scope.launch { whisper.collect { held -> onChange(UiAudioGateKind.WHISPER, held) } }
        scope.launch { refreshes.filterNotNull().distinctUntilChanged().collect { resend() } }
    }

    override fun onPromptLevel(sounding: Boolean) {
        if (!releaseFlags.honor) return
        onChange(UiAudioGateKind.PROMPT, sounding)
    }

    private fun onChange(kind: UiAudioGateKind, held: Boolean) {
        synchronized(lock) {
            current[kind] = held
            if (sent[kind] != held) publish(kind, held)
        }
    }

    /** The prompt first, as `:tracker` would apply one value that carries it and the recording. */
    private fun resend() {
        synchronized(lock) {
            UiAudioGateKind.entries.forEach { kind -> publish(kind, current.getValue(kind)) }
        }
    }

    private fun publish(kind: UiAudioGateKind, held: Boolean) {
        val token = if (held) Binder() else null
        if (token != null) tokens[kind] = token else tokens.remove(kind)
        sent[kind] = held
        lastSeq = maxOf(lastSeq + 1, bootNanos())
        send(kind, held, lastSeq, token)
    }
}

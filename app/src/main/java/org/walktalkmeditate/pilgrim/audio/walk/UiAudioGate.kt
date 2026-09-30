// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
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

/** The UI process's two audio gates, by their names on the gate intent. */
enum class UiAudioGateKind(val wireName: String) {
    PROMPT("prompt"),
    RECORDING("recording"),
    ;

    companion object {
        fun fromWire(name: String?): UiAudioGateKind? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * One gate intent as `:tracker` reads it: [kind] started ([held]) or
 * ended, numbered [seq] by the UI, and on a start the Binder the UI made
 * for it.
 */
data class UiAudioGateSignal(
    val kind: UiAudioGateKind,
    val held: Boolean,
    val seq: Long,
    val token: IBinder?,
)

/**
 * `:tracker`'s record of the UI's guide prompt and recording, which
 * [WalkAudioArbiter] reads (plan U18, "Gates via Binder death links from
 * single observers").
 *
 * - **A start carries a Binder.** The gate is linked to that Binder's
 *   death, so a UI process that dies mid-prompt or mid-take lets go of it.
 *   A Binder already dead when it is linked, a start that outlived its UI,
 *   clears the gate at once, as does a start that carries none.
 * - **An end**, a dead Binder, or a newer start replaces what the gate
 *   held. A signal numbered at or below the gate's last one is stale and
 *   changes nothing.
 * - **Unknown until the UI answers.** A fresh process, and each walk
 *   pipeline start ([holdUntilRefreshed]), knows nothing of the UI: both
 *   gates read held until each gate's next signal, or until
 *   [refreshWaitMillis] passes with none, since a dead UI sends nothing
 *   and holds no prompt and no take. The UI answers when the session's
 *   gate generation, bumped at each session start and revival, changes.
 * - **A take that starts behind a prompt** is reported with the prompt's
 *   end, in one value, which the arbiter applies prompt first: on iOS a
 *   recording stops the prompt before its own gate closes
 *   (`VoiceGuideManagement.swift:110-116@7c200bf`; spec C D1 as corrected).
 *   The prompt holds every voice and whisper meanwhile.
 *
 * The service never hands it a redelivered intent: after a `:tracker`
 * kill the OS replays old starts whose Binders may still be alive though
 * their gates ended long ago.
 */
@Singleton
class UiAudioGate internal constructor(
    private val scope: CoroutineScope,
    private val refreshWaitMillis: Long,
) : UiAudioGateSource {

    @Inject
    constructor() : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        refreshWaitMillis = REFRESH_WAIT_MILLIS,
    )

    private enum class Phase { UNKNOWN, HELD, OPEN }

    private class Gate(val kind: UiAudioGateKind) {
        var phase = Phase.UNKNOWN
        var seq = 0L
        var token: IBinder? = null
        var death: IBinder.DeathRecipient? = null
    }

    private val lock = Any()
    private val prompt = Gate(UiAudioGateKind.PROMPT)
    private val recording = Gate(UiAudioGateKind.RECORDING)
    private var recordingBehindPrompt = false
    private var refreshWait: Job? = null
    private val _gates = MutableStateFlow(UiAudioGates(prompt = true, recording = true))

    override val gates: StateFlow<UiAudioGates> = _gates.asStateFlow()

    init {
        holdUntilRefreshed()
    }

    /** Both gates unknown, so held, until the UI's next signal for each or [refreshWaitMillis]. */
    fun holdUntilRefreshed() {
        synchronized(lock) {
            for (gate in listOf(prompt, recording)) {
                unlink(gate)
                gate.phase = Phase.UNKNOWN
            }
            recordingBehindPrompt = false
            publish()
            refreshWait?.cancel()
            refreshWait = scope.launch {
                delay(refreshWaitMillis)
                openUnanswered()
            }
        }
    }

    fun apply(signal: UiAudioGateSignal) {
        synchronized(lock) {
            val gate = if (signal.kind == UiAudioGateKind.PROMPT) prompt else recording
            if (signal.seq <= gate.seq) return
            gate.seq = signal.seq
            unlink(gate)
            val token = signal.token
            val held = signal.held && token != null && link(gate, token)
            move(gate, if (held) Phase.HELD else Phase.OPEN)
        }
    }

    private fun link(gate: Gate, token: IBinder): Boolean {
        val death = IBinder.DeathRecipient { onDied(gate, token) }
        return try {
            token.linkToDeath(death, 0)
            gate.token = token
            gate.death = death
            true
        } catch (e: RemoteException) {
            Log.i(TAG, "${gate.kind.wireName} gate's UI is gone at link time: cleared")
            false
        }
    }

    private fun unlink(gate: Gate) {
        val token = gate.token ?: return
        val death = gate.death ?: return
        gate.token = null
        gate.death = null
        token.unlinkToDeath(death, 0)
    }

    private fun onDied(gate: Gate, token: IBinder) {
        synchronized(lock) {
            if (gate.token !== token) return
            gate.token = null
            gate.death = null
            Log.i(TAG, "${gate.kind.wireName} gate's UI died: cleared")
            move(gate, Phase.OPEN)
        }
    }

    private fun openUnanswered() {
        synchronized(lock) {
            val unanswered = listOf(prompt, recording).filter { it.phase == Phase.UNKNOWN }
            if (unanswered.isEmpty()) return
            unanswered.forEach { it.phase = Phase.OPEN }
            Log.i(TAG, "no UI answer in ${refreshWaitMillis}ms: ${unanswered.joinToString { it.kind.wireName }} open")
            publish()
        }
    }

    private fun move(gate: Gate, next: Phase) {
        val before = gate.phase
        gate.phase = next
        if (gate === recording && before == Phase.OPEN && next == Phase.HELD && prompt.phase == Phase.HELD) {
            recordingBehindPrompt = true
        }
        if (recording.phase != Phase.HELD || prompt.phase != Phase.HELD) recordingBehindPrompt = false
        publish()
    }

    private fun publish() {
        _gates.value = UiAudioGates(
            prompt = prompt.phase != Phase.OPEN,
            recording = recording.phase != Phase.OPEN && !recordingBehindPrompt,
        )
    }

    companion object {
        private const val TAG = "UiAudioGate"

        /**
         * Covers `:tracker`'s own start (the restore, a 1 s bound on the last
         * fix, the session's prepare that bumps the generation) plus a Room
         * invalidation round trip to a live UI and its intent back, with room
         * to spare; a dead UI costs the Way at most this long.
         */
        const val REFRESH_WAIT_MILLIS = 3_000L
    }
}

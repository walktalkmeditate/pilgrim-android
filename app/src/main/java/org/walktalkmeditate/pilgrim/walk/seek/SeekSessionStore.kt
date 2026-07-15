// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.seek

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.walktalkmeditate.pilgrim.domain.seek.SeekChain
import org.walktalkmeditate.pilgrim.domain.seek.SeekTint

/**
 * The seek setup's finished output, waiting for the orchestrator (U9)
 * to pick it up when the walk starts. iOS has no analogue — the same
 * view model that runs the setup also boots the engine
 * (`ActiveWalkViewModel+Seek.swift:72-119@c1745e8`); Android splits
 * setup (U8) from engine lifecycle (U9), and this is the seam between
 * them.
 */
data class SeekPendingSession(
    /** GPS-locked clearing chain generated from the first ≤50 m fix. */
    val chain: SeekChain,
    /** The duration the walker chose on the setup sheet. */
    val durationMinutes: Int,
    /** Celestial fog tint + gateway line, or null on an ordinary sky. */
    val tint: SeekTint?,
    /** Moment the seed was drawn (chain provenance, pairs with the
     *  SEEK_MODE event's timestamp on the walk itself). */
    val seededAtEpochMillis: Long,
    /**
     * The intention voiced during setup (already one voice in the
     * chain's seed). Carried so a reroll can re-ask with the same
     * intention and a new moment — iOS reads `vm.intention` at
     * `seekAnewRequested` (`ActiveWalkViewModel+Seek.swift:196-210
     * @c1745e8`); the orchestrator has no VM, so the session carries it
     * (U9 port spec D7).
     */
    val intention: String? = null,
)

/**
 * Dumb pending-session holder — deliberately knows nothing about the
 * engine. Written by [org.walktalkmeditate.pilgrim.ui.seek.SeekSetupViewModel]
 * when the GPS lock generates the chain; cleared on setup cancel, on a
 * fresh setup, when the setup surface dies without a walk, and on every
 * walk terminal transition ([org.walktalkmeditate.pilgrim.walk.WalkLifecycleObserver]).
 * The U9 orchestrator reads [pending] when a seek walk starts. UI
 * process only.
 */
@Singleton
class SeekSessionStore @Inject constructor() {

    private val _pending = MutableStateFlow<SeekPendingSession?>(null)
    val pending: StateFlow<SeekPendingSession?> = _pending.asStateFlow()

    fun set(session: SeekPendingSession) {
        _pending.value = session
    }

    fun clear() {
        _pending.value = null
    }
}

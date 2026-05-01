// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Subscribes to the walk-state flow and fires [BellPlaying.play] on
 * every USER-INITIATED Meditating boundary transition:
 *  - Active → Meditating (user tapped Meditate — start bell)
 *  - Meditating → Active (user tapped Done — end bell)
 *  - Meditating → Finished (walk finished during meditation — end bell)
 *
 * Discards its first collection — observing the CURRENT state of a
 * `@Singleton` controller at app-init time is not a transition (it
 * is always cold-process `Idle` because
 * [org.walktalkmeditate.pilgrim.walk.WalkController] initializes its
 * state flow to `Idle`).
 *
 * **Restore-path suppression.** After the first-emission skip lands on
 * `Idle`, `HomeScreen`'s resume-check may call `restoreActiveWalk()`,
 * which writes a restored state (possibly `Meditating`) directly into
 * the state flow. The observer would see `Idle → Meditating` and fire
 * a spurious "welcome back" bell — a bell the user did not trigger.
 * The guard below treats `Idle → Meditating` as a restore path, not a
 * user-initiated boundary. User-initiated `MeditateStart` dispatches
 * always originate from `Active` (you can't meditate without first
 * starting a walk), so `Active → Meditating` and `Idle → Meditating`
 * are domain-distinguishable.
 *
 * Instantiated eagerly at app start via `PilgrimApp.onCreate`'s
 * `@Inject` reference — without that reference, Hilt is lazy and the
 * observer's `init` block never runs.
 *
 * Same bell asset fires for both directions (start and end), matching
 * iOS's MVP behavior. See the design spec for the single-asset rationale.
 *
 * **Per-event bell selection (deferred).** Stage 10-B Chunk B persists
 * `meditationStartBellId` and `meditationEndBellId` so a user's choice
 * survives across launches AND a `.pilgrim` ZIP can round-trip with
 * iOS. The runtime path that resolves those ids to an [AudioAsset]
 * + plays the matching file is NOT yet wired — [BellPlayer] is
 * hardcoded to the bundled `R.raw.bell` resource. Generalizing
 * BellPlayer to accept an asset (similar to
 * [org.walktalkmeditate.pilgrim.audio.soundscape.SoundscapePlayer.play])
 * is intentionally deferred to a future PR; it requires a
 * bell-specific file store + download orchestration that doesn't
 * fit the Stage 10-B scope. Until then, both bell-id prefs persist
 * silently and the bundled bell plays for every meditation
 * boundary.
 */
@Singleton
class MeditationBellObserver @Inject constructor(
    // `@JvmSuppressWildcards` is required because Kotlin's
    // `StateFlow<WalkState>` compiles to Java's
    // `StateFlow<? extends WalkState>` — Dagger sees the producer-site
    // wildcard on the parameter but the provider declares an invariant
    // `StateFlow<WalkState>`, so without the suppression the bindings
    // don't match and the app fails to compose at Hilt-gen time.
    @MeditationObservedWalkState walkState: StateFlow<@JvmSuppressWildcards WalkState>,
    bellPlayer: BellPlaying,
    private val soundsPreferences: SoundsPreferencesRepository,
    @MeditationBellScope scope: CoroutineScope,
) {
    init {
        scope.launch {
            var lastStateClass: KClass<out WalkState>? = null
            walkState.collect { state ->
                val curr = state::class
                val prev = lastStateClass
                lastStateClass = curr
                // First emission is the CURRENT state of a @Singleton
                // at app init — always `Idle` because WalkController
                // initializes there. Skip.
                if (prev == null) return@collect
                val wasMeditating = prev == WalkState.Meditating::class
                val isMeditating = curr == WalkState.Meditating::class
                // Suppress the bell on the restore path. After the
                // Idle first-emission is consumed, `restoreActiveWalk`
                // may write a resumed `Meditating` state; that's not a
                // user-initiated boundary — silent resume. User-
                // initiated `MeditateStart` always comes from Active.
                val isRestoreIntoMeditating =
                    prev == WalkState.Idle::class && isMeditating
                if (wasMeditating != isMeditating && !isRestoreIntoMeditating) {
                    // Stage 10-B master sounds toggle: short-circuit if user has muted.
                    if (soundsPreferences.soundsEnabled.value) {
                        // iOS-faithful: meditation start/end bells pair a
                        // `.medium` haptic when `bellHapticEnabled` is on
                        // (BellPlayer.swift:29-31; SoundManagement.swift:43
                        // forwards `withHaptic: hapticEnabled`). Stage 12-C
                        // moves haptic coupling to the player layer; the
                        // observer simply requests `withHaptic = true` and
                        // BellPlayer gates internally on the user pref.
                        bellPlayer.play(scale = 1.0f, withHaptic = true)
                    }
                }
            }
        }
    }
}

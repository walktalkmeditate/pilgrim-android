// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * iOS. See the design spec for the single-asset rationale.
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
                    bellPlayer.play()
                }
            }
        }
    }
}

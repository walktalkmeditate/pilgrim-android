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
 * every Meditating boundary transition:
 *  - Active → Meditating (start bell)
 *  - Meditating → Active (end bell)
 *  - Meditating → Finished (end bell — walk finished during meditation)
 *
 * Discards its first collection — observing the CURRENT state of a
 * `@Singleton` controller at app-init time is not a transition (it's
 * cold-process Idle, or a restored Meditating session). Only real,
 * observed transitions ring the bell. Same first-emission-skip
 * pattern as Stage 5-A's `hasSeenMeditating` latch.
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
                // at app init — not a transition. Skip regardless of
                // whether it's Idle (cold start) or Meditating
                // (restored session).
                if (prev == null) return@collect
                val wasMeditating = prev == WalkState.Meditating::class
                val isMeditating = curr == WalkState.Meditating::class
                if (wasMeditating != isMeditating) {
                    bellPlayer.play()
                }
            }
        }
    }
}

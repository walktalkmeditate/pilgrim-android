// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.walk.BellTrigger

/**
 * Subscribes to [WalkController.bellTriggers] and plays the bundled
 * bell for every user-initiated walk / meditation boundary:
 *  - [BellTrigger.WalkStart]       — start of a new walk
 *  - [BellTrigger.WalkEnd]         — finish of the walk
 *  - [BellTrigger.MeditationStart] — user tapped Meditate
 *  - [BellTrigger.MeditationEnd]   — user tapped Done
 *
 * The trigger SharedFlow is fire-and-forget (no replay, no state),
 * so a cold-start subscription never replays past events — that's
 * exactly the property the iOS `SoundManagement.onWalkStart()`
 * pattern relies on. The restore path writes directly into
 * [WalkController.state] without going through `startWalk()` /
 * `startMeditation()`, so it doesn't emit a trigger and the observer
 * stays silent on resume.
 *
 * Per-event id (`walkStartBellId`, `walkEndBellId`,
 * `meditationStartBellId`, `meditationEndBellId`) gates whether the
 * bell fires at all — `null` (user picked "None") suppresses the
 * strike. First-install seeds live in
 * `org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesSeeder`.
 *
 * Instantiated eagerly at app start via `PilgrimApp.onCreate`'s
 * `@Inject` reference — without that reference, Hilt is lazy and the
 * observer's `init` block never runs.
 *
 * Same bell asset fires for every trigger (single bundled
 * `R.raw.bell`); per-id asset routing is deferred to the bell-pack
 * download epic.
 */
@Singleton
class MeditationBellObserver @Inject constructor(
    // `@JvmSuppressWildcards` mirrors the precedent on
    // `@MeditationObservedWalkState walkState: StateFlow<...WalkState>` —
    // Kotlin's covariant generic compiles to a Java wildcard that
    // doesn't match Dagger's invariant binding produced by
    // `provideBellTriggers`.
    bellTriggers: SharedFlow<@JvmSuppressWildcards BellTrigger>,
    private val bellPlayer: BellPlaying,
    private val soundsPreferences: SoundsPreferencesRepository,
    @MeditationBellScope scope: CoroutineScope,
) {
    init {
        scope.launch {
            bellTriggers.collect { trigger ->
                if (!soundsPreferences.soundsEnabled.value) return@collect
                val bellId = when (trigger) {
                    BellTrigger.WalkStart -> soundsPreferences.walkStartBellId.value
                    BellTrigger.WalkEnd -> soundsPreferences.walkEndBellId.value
                    BellTrigger.MeditationStart -> soundsPreferences.meditationStartBellId.value
                    BellTrigger.MeditationEnd -> soundsPreferences.meditationEndBellId.value
                }
                if (bellId == null) return@collect
                // Pass the picked id; BellPlayer plays the downloaded
                // file when available, else falls back to bundled.
                bellPlayer.play(bellId = bellId, scale = 1.0f, withHaptic = true)
            }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal exposing the user's bell-haptic preference. This is
 * a SECONDARY toggle (within Sound Settings) that gates the haptic
 * accompaniment to a bell strike — NOT the master haptic gate (that's
 * [LocalSoundsEnabled], which the existing UI haptic call sites
 * consume).
 *
 * The provider sits at MainActivity.setContent level next to
 * [LocalSoundsEnabled]. Default `true` keeps a fresh-install user
 * matching iOS behavior; if no provider is reached (preview / headless
 * test), the value falls back to the iOS default.
 *
 * **Currently unused.** No production call site reads this yet —
 * `MeditationBellObserver` only plays the audio bell, with no haptic
 * counterpart on Android (iOS uses a `UIImpactFeedbackGenerator`
 * adjacent to the bell play). Wiring the bell-haptic alongside the
 * bell sound at meditation start/end lands in a future PR. The pref
 * is persisted now (Stage 10-B Chunk B) so the user setting survives
 * a `.pilgrim` ZIP round-trip with iOS regardless.
 *
 * Use (future):
 * ```
 * val bellHaptic = LocalBellHapticEnabled.current
 * if (bellHaptic) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
 * ```
 */
val LocalBellHapticEnabled = compositionLocalOf { true }

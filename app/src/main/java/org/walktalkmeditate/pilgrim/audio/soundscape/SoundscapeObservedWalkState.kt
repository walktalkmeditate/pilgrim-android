// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import javax.inject.Qualifier

/**
 * Qualifier for the read-only [kotlinx.coroutines.flow.StateFlow] of
 * walk state observed by [SoundscapeOrchestrator]. Separate from
 * `@MeditationObservedWalkState` (Stage 5-B) and
 * `@VoiceGuideObservedWalkState` (Stage 5-E) for clarity — one
 * qualifier per audio consumer keeps test wiring crisp (one fake
 * `StateFlow` per observer, no cross-contamination).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SoundscapeObservedWalkState

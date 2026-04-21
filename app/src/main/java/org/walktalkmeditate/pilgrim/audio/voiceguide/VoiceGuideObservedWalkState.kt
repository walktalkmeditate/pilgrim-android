// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import javax.inject.Qualifier

/**
 * Qualifier for the read-only [kotlinx.coroutines.flow.StateFlow] of
 * walk state observed by [VoiceGuideOrchestrator]. Separate from
 * `@MeditationObservedWalkState` (Stage 5-B) for clarity — same
 * underlying value (`WalkController.state`), different audio
 * consumer. Keeping distinct qualifiers per consumer makes tests
 * easier to wire (one fake `StateFlow` per orchestrator/observer,
 * no cross-contamination).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideObservedWalkState

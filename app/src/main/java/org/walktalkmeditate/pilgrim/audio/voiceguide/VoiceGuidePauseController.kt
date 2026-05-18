// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow read+control surface the Active Walk UI needs from
 * [VoiceGuideOrchestrator] for the in-walk play/pause control (iOS
 * parity `VoiceGuideManagement`, `ActiveWalkView.swift:433-443`).
 *
 * `WalkViewModel` depends on this interface — not the full
 * orchestrator with its manifest / file-store / player graph — so VM
 * unit tests can drop in a trivial fake. Mirrors the codebase's
 * narrow-interface convention (`@VoiceGuideObservedWalkState`,
 * `@MeditationObservedWalkState`).
 */
interface VoiceGuidePauseController {
    /** Name of the active guide pack, or null when no guide is running. */
    val activePackName: StateFlow<String?>

    /** True while the user has paused the guide. */
    val isPaused: StateFlow<Boolean>

    /** Suspend the guide (stop in-flight prompt, schedule nothing). */
    fun pause()

    /** Resume scheduling new prompts. */
    fun resume()
}

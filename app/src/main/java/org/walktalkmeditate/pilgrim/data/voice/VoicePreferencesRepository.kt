// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * Voice-related user preferences. All keys match iOS UserDefaults names verbatim
 * for `.pilgrim` ZIP cross-platform parity.
 *
 * StateFlows are `SharingStarted.Eagerly` because the orchestrator and walk-finalize
 * observer read `.value` synchronously from background contexts (no UI subscriber).
 * `WhileSubscribed` would silently return defaults in those paths.
 */
interface VoicePreferencesRepository {
    val voiceGuideEnabled: StateFlow<Boolean>
    val autoTranscribe: StateFlow<Boolean>
    suspend fun setVoiceGuideEnabled(enabled: Boolean)
    suspend fun setAutoTranscribe(enabled: Boolean)
}

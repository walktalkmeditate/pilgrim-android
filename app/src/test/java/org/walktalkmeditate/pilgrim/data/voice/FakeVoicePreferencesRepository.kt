// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [VoicePreferencesRepository]. Used by Settings card +
 * downstream consumer (orchestrator, walk-finalize observer) tests to vary prefs
 * without a real DataStore. Default values mirror iOS production defaults so a
 * fresh `FakeVoicePreferencesRepository()` matches a fresh-install user.
 */
class FakeVoicePreferencesRepository(
    initialVoiceGuideEnabled: Boolean = false,
    initialAutoTranscribe: Boolean = false,
) : VoicePreferencesRepository {
    private val _voiceGuideEnabled = MutableStateFlow(initialVoiceGuideEnabled)
    private val _autoTranscribe = MutableStateFlow(initialAutoTranscribe)

    override val voiceGuideEnabled: StateFlow<Boolean> = _voiceGuideEnabled.asStateFlow()
    override val autoTranscribe: StateFlow<Boolean> = _autoTranscribe.asStateFlow()

    override suspend fun setVoiceGuideEnabled(enabled: Boolean) {
        _voiceGuideEnabled.value = enabled
    }

    override suspend fun setAutoTranscribe(enabled: Boolean) {
        _autoTranscribe.value = enabled
    }
}

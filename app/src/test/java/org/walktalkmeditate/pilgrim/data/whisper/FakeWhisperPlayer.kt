// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository

open class FakeWhisperPlayer(
    context: Context = ApplicationProvider.getApplicationContext(),
    soundsPreferences: SoundsPreferencesRepository = FakeSoundsPreferencesRepository(),
) : WhisperPlayer(
    context = context,
    httpClient = OkHttpClient(),
    soundsPreferences = soundsPreferences,
) {
    var playCalls: Int = 0
    var previewCalls: Int = 0
    var stopCalls: Int = 0

    private val state = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = state.asStateFlow()

    override fun play(definition: WhisperDefinition) { playCalls += 1 }
    override fun preview(definition: WhisperDefinition) {
        previewCalls += 1
        state.value = true
    }
    override fun stop() {
        stopCalls += 1
        state.value = false
    }
}

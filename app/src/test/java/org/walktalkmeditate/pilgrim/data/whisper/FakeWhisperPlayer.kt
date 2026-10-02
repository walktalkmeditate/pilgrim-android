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

/**
 * Counts calls without touching the network or a MediaPlayer. A [fetch]
 * lands at once unless [holdFetches] is set, when it waits in
 * [heldFetches] for the test to land it, as a download still in flight does.
 */
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
    @Volatile var cutCalls: Int = 0
    var holdFetches: Boolean = false
    val heldFetches: MutableList<() -> Unit> = mutableListOf()

    private val state = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = state.asStateFlow()

    override fun play(definition: WhisperDefinition) { playCalls += 1 }
    override fun fetch(definition: WhisperDefinition, onLanded: (WhisperDefinition) -> Unit) {
        if (holdFetches) heldFetches += { onLanded(definition) } else onLanded(definition)
    }
    override fun cut() { cutCalls += 1 }
    override fun preview(definition: WhisperDefinition) {
        previewCalls += 1
        state.value = true
    }
    override fun stop() {
        stopCalls += 1
        state.value = false
    }
    override fun stopPreviewOnly() {
        stopCalls += 1
        state.value = false
    }

    /** Lands every held fetch, oldest first, as their downloads finish. */
    fun landHeldFetches() {
        val landing = heldFetches.toList()
        heldFetches.clear()
        landing.forEach { it() }
    }
}

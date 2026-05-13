// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

open class FakeWhisperManifestService(
    initialManifest: WhisperManifest? = WhisperManifest(
        version = 1,
        whispers = WhisperCategory.entries.map { cat ->
            WhisperDefinition(
                id = "fake-${cat.apiValue}",
                title = cat.apiValue,
                category = cat,
                audioFileName = "fake-${cat.apiValue}",
                durationSec = 5.0,
                retiredAt = null,
            )
        },
    ),
) : WhisperManifestService(
    httpClient = OkHttpClient(),
    json = Json,
) {
    private val state = MutableStateFlow(initialManifest)
    override val manifest: StateFlow<WhisperManifest?> = state.asStateFlow()

    override suspend fun refresh(): Boolean {
        // Already initialized in constructor; pretend the fetch succeeded.
        return state.value != null
    }

    override fun placeableCategories(): Set<WhisperCategory> =
        state.value?.whispers?.filter { it.isActive }?.map { it.category }?.toSet() ?: emptySet()

    override fun randomWhisper(category: WhisperCategory): WhisperDefinition? =
        state.value?.whispers?.firstOrNull { it.isActive && it.category == category }
}

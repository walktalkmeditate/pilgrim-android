// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-scoped observer that watches [VoiceGuideCatalogRepository.packStates]
 * for pack transitions into [VoiceGuidePackState.Downloaded] and
 * calls [VoiceGuideSelectionRepository.selectIfUnset] so the first
 * pack to finish downloading becomes the selected pack
 * automatically. Matches iOS's `onSelectOnCompletion` UX — users
 * don't have to manually pick after a download.
 *
 * [start] must be called once from `PilgrimApp.onCreate` (following
 * the `MeditationBellObserver` pattern from Stage 5-B). Subscription
 * lives for the process.
 */
@Singleton
class VoiceGuideDownloadObserver @Inject constructor(
    private val catalog: VoiceGuideCatalogRepository,
    private val selection: VoiceGuideSelectionRepository,
    @VoiceGuideCatalogScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { observe() }
    }

    private suspend fun observe() {
        var previous: Map<String, VoiceGuidePackState> = emptyMap()
        catalog.packStates.collect { list ->
            val current = list.associateBy { it.pack.id }
            current.forEach { (id, state) ->
                val prev = previous[id]
                val becameDownloaded =
                    state is VoiceGuidePackState.Downloaded &&
                        prev != null &&
                        prev !is VoiceGuidePackState.Downloaded
                if (becameDownloaded) {
                    selection.selectIfUnset(id)
                }
            }
            previous = current
        }
    }
}

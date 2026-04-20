// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.voiceguide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideCatalogRepository
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePack
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePackState

/**
 * ViewModel for the voice-guide pack list. Passthrough to
 * [VoiceGuideCatalogRepository.packStates] — all the state
 * composition logic lives in the repository so both picker and
 * detail can share it.
 */
@HiltViewModel
class VoiceGuidePickerViewModel @Inject constructor(
    private val catalog: VoiceGuideCatalogRepository,
) : ViewModel() {

    init {
        // Kick off a manifest refresh on screen open. No-op if one is
        // already in flight (deduped by the manifest service's CAS).
        // First-run users with an empty cache see packs arrive as soon
        // as the network responds.
        catalog.refreshManifest()
    }

    val packStates: StateFlow<List<VoiceGuidePackState>> = catalog.packStates

    fun download(packId: String) = catalog.download(packId)
    fun retry(packId: String) = catalog.retry(packId)
    fun cancel(packId: String) = catalog.cancel(packId)

    fun select(packId: String) {
        viewModelScope.launch { catalog.select(packId) }
    }

    fun deselect() {
        viewModelScope.launch { catalog.deselect() }
    }

    fun delete(pack: VoiceGuidePack) {
        viewModelScope.launch { catalog.delete(pack) }
    }
}

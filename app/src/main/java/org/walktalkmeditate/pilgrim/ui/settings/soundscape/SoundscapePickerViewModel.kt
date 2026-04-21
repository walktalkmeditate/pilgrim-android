// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.soundscape

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeCatalogRepository
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeState

/**
 * ViewModel for the soundscape picker. Passthrough to
 * [SoundscapeCatalogRepository.soundscapeStates]. Single-screen
 * picker (no detail page like voice guides) so the tap/long-press
 * actions live here rather than in a navigated screen.
 *
 * Tap semantics per state:
 *  - NotDownloaded → enqueue download
 *  - Downloading → no-op (indicator shows progress)
 *  - Downloaded unselected → select
 *  - Downloaded selected → deselect
 *  - Failed → retry
 *
 * Long-press on any row surfaces a delete option in the UI; the VM
 * action is [onRowDelete], which is only honored for Downloaded or
 * Failed rows (no-op otherwise).
 */
@HiltViewModel
class SoundscapePickerViewModel @Inject constructor(
    private val catalog: SoundscapeCatalogRepository,
) : ViewModel() {

    init {
        // Kick a manifest refresh on screen open. No-op when one's
        // already in flight (CAS-deduped in the service).
        catalog.refreshManifest()
    }

    val soundscapeStates: StateFlow<List<SoundscapeState>> = catalog.soundscapeStates

    fun onRowTap(state: SoundscapeState) {
        when (state) {
            is SoundscapeState.NotDownloaded -> catalog.download(state.asset.id)
            is SoundscapeState.Failed -> catalog.retry(state.asset.id)
            is SoundscapeState.Downloading -> Unit
            is SoundscapeState.Downloaded -> viewModelScope.launch {
                if (state.isSelected) catalog.deselect() else catalog.select(state.asset.id)
            }
        }
    }

    fun onRowDelete(state: SoundscapeState) {
        viewModelScope.launch {
            when (state) {
                is SoundscapeState.Downloaded -> catalog.delete(state.asset)
                is SoundscapeState.Failed -> catalog.delete(state.asset)
                else -> Unit
            }
        }
    }
}

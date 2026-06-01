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
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher

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
    private val actionPublisher: WalkActionPublisher,
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
            is SoundscapeState.Downloaded -> {
                // Cross-process bridge: the catalog's `select`/`deselect`
                // writes to DataStore in the UI process. DataStore is
                // single-process by default, so :tracker's orchestrator
                // (which reads `selectedSoundscapeId` for its mid-session
                // swap decision in `observe()`) never sees the UI write.
                // The `actionPublisher` Intent hop is the live notification
                // — same pattern WalkSoundscapeUiController.select() uses
                // for the walk-time path.
                //
                // The Intent fires BEFORE the suspend DataStore write so
                // a viewModelScope-cancellation race (user swipes away
                // mid-coroutine — Stage 9-B) or a rare DataStore IOException
                // can't strand :tracker on the old soundscape. Persistence
                // is "for next time" and naturally re-syncs from the
                // picker's catalog flow on the next open.
                //
                // Deselect uses `clearSoundscapeSelection()`, NOT
                // `setSoundscapeEnabled(false)`. The manual toggle only
                // gates Active/Paused; Meditating's auto-play predicate
                // is `enabled && effectiveId != null`, which stays true
                // (DataStore-mirrored `selectedAssetId` still holds the
                // old id cross-process). Only the orchestrator's
                // `Selection.cleared` flag masks the predicate.
                if (state.isSelected) {
                    actionPublisher.clearSoundscapeSelection()
                    viewModelScope.launch { catalog.deselect() }
                } else {
                    actionPublisher.selectSoundscape(state.asset.id)
                    viewModelScope.launch { catalog.select(state.asset.id) }
                }
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

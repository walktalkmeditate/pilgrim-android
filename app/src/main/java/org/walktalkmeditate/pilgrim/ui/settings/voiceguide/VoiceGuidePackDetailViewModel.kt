// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.voiceguide

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideCatalogRepository
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePackState

/**
 * Detail screen for one voice-guide pack. [packId] is supplied via
 * navigation args. Derives a single [VoiceGuidePackState] from the
 * catalog repository; wraps it in [UiState] so the screen can
 * render Loading / Loaded / NotFound explicitly (same shape as
 * `WalkSummaryViewModel`).
 */
@HiltViewModel
class VoiceGuidePackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: VoiceGuideCatalogRepository,
) : ViewModel() {
    private val packId: String = requireNotNull(savedStateHandle[ARG_PACK_ID]) {
        "VoiceGuidePackDetailViewModel requires $ARG_PACK_ID nav argument"
    }

    val uiState: StateFlow<UiState> = catalog.packStates
        .map { list -> list.firstOrNull { it.pack.id == packId } }
        .map { state -> if (state == null) UiState.NotFound else UiState.Loaded(state) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState.Loading)

    fun download() = catalog.download(packId)
    fun retry() = catalog.retry(packId)
    fun cancel() = catalog.cancel(packId)

    fun select() {
        viewModelScope.launch { catalog.select(packId) }
    }

    fun deselect() {
        viewModelScope.launch { catalog.deselect() }
    }

    fun delete() {
        val loaded = uiState.value as? UiState.Loaded ?: return
        viewModelScope.launch { catalog.delete(loaded.state.pack) }
    }

    sealed class UiState {
        data object Loading : UiState()
        data class Loaded(val state: VoiceGuidePackState) : UiState()
        data object NotFound : UiState()
    }

    companion object {
        const val ARG_PACK_ID = "packId"
    }
}

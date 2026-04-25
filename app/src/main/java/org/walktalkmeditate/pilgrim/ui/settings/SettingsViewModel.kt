// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats

/**
 * Stage 8-B: ViewModel for the Settings screen surfaces — currently
 * just the collective-counter display + opt-in toggle. Passthrough
 * to [CollectiveRepository]; no UI-only state lives here.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val collectiveRepository: CollectiveRepository,
) : ViewModel() {

    val stats: StateFlow<CollectiveStats?> = collectiveRepository.stats
    val optIn: StateFlow<Boolean> = collectiveRepository.optIn

    fun setOptIn(value: Boolean) {
        viewModelScope.launch { collectiveRepository.setOptIn(value) }
    }

    fun fetchOnAppear() {
        viewModelScope.launch { collectiveRepository.fetchIfStale() }
    }
}

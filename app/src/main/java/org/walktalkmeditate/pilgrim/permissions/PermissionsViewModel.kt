// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val repository: PermissionsRepository,
) : ViewModel() {

    val onboardingComplete: StateFlow<Boolean> = repository.onboardingComplete.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    val batteryExemptionAsked: StateFlow<Boolean> = repository.batteryExemptionAsked.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun markOnboardingComplete() {
        viewModelScope.launch {
            repository.markOnboardingComplete()
        }
    }

    fun markBatteryExemptionAsked() {
        viewModelScope.launch {
            repository.markBatteryExemptionAsked()
        }
    }
}

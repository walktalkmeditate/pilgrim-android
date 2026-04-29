// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore
import org.walktalkmeditate.pilgrim.permissions.PermissionStatus

data class PermissionsCardState(
    val location: PermissionStatus,
    val microphone: PermissionStatus,
    val motion: PermissionStatus,
)

@HiltViewModel
class PermissionsCardViewModel @Inject constructor(
    private val checks: LivePermissionChecks,
    private val askedFlags: AskedFlagSource,
) : ViewModel() {

    private val _state = MutableStateFlow(compute())
    val state: StateFlow<PermissionsCardState> = _state.asStateFlow()

    /**
     * Re-read live permission state. Composable calls this on
     * `Lifecycle.Event.ON_RESUME` (mirrors iOS willEnterForegroundNotification).
     */
    fun refresh() {
        _state.value = compute()
    }

    /**
     * Permission-request callback hook. Marks the key as asked and
     * recomputes — flips a NotDetermined row to Granted (user said yes)
     * or Denied (user said no).
     */
    fun onPermissionResult(key: PermissionAskedStore.Key) {
        viewModelScope.launch {
            askedFlags.markAsked(key)
            _state.value = compute()
        }
    }

    private fun compute(): PermissionsCardState = PermissionsCardState(
        location = resolve(checks.isLocationGranted(), PermissionAskedStore.Key.Location),
        microphone = resolve(checks.isMicrophoneGranted(), PermissionAskedStore.Key.Microphone),
        motion = resolve(checks.isMotionGranted(), PermissionAskedStore.Key.Motion),
    )

    private fun resolve(granted: Boolean, key: PermissionAskedStore.Key): PermissionStatus =
        when {
            granted -> PermissionStatus.Granted
            askedFlags.isAsked(key) -> PermissionStatus.Denied
            else -> PermissionStatus.NotDetermined
        }
}

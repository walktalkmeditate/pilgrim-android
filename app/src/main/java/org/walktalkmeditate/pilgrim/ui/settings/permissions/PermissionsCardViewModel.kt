// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    /**
     * Bumped on `Lifecycle.Event.ON_RESUME` and after every permission
     * dialog result so [state] re-reads the live `checkSelfPermission`
     * snapshot. No DataStore work happens on the recompose thread —
     * the asked flags compose via Flow.
     */
    private val refreshTick = MutableStateFlow(0)

    val state: StateFlow<PermissionsCardState> = combine(
        refreshTick,
        askedFlags.asked(PermissionAskedStore.Key.Location),
        askedFlags.asked(PermissionAskedStore.Key.Microphone),
        askedFlags.asked(PermissionAskedStore.Key.Motion),
    ) { _, lAsked, mAsked, motionAsked ->
        PermissionsCardState(
            location = resolve(checks.isLocationGranted(), lAsked),
            microphone = resolve(checks.isMicrophoneGranted(), mAsked),
            motion = resolve(checks.isMotionGranted(), motionAsked),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_KEEPALIVE_MS),
        initialValue = PermissionsCardState(
            location = PermissionStatus.NotDetermined,
            microphone = PermissionStatus.NotDetermined,
            motion = PermissionStatus.NotDetermined,
        ),
    )

    fun refresh() {
        refreshTick.value += 1
    }

    fun onPermissionResult(key: PermissionAskedStore.Key) {
        viewModelScope.launch {
            askedFlags.markAsked(key)
            refreshTick.value += 1
        }
    }

    private fun resolve(granted: Boolean, asked: Boolean): PermissionStatus = when {
        granted -> PermissionStatus.Granted
        asked -> PermissionStatus.Denied
        else -> PermissionStatus.NotDetermined
    }

    private companion object {
        const val SUBSCRIPTION_KEEPALIVE_MS = 5_000L
    }
}

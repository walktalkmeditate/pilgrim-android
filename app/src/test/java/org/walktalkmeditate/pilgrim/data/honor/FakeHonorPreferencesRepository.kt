// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [HonorPreferencesRepository]; the default matches iOS's (voices on). */
class FakeHonorPreferencesRepository(voicesEnabled: Boolean = true) : HonorPreferencesRepository {
    private val _voicesEnabled = MutableStateFlow(voicesEnabled)
    override val voicesEnabled: StateFlow<Boolean> = _voicesEnabled.asStateFlow()

    override suspend fun setVoicesEnabled(value: Boolean) {
        _voicesEnabled.value = value
    }

    override suspend fun awaitVoicesEnabled(): Boolean = _voicesEnabled.value
}

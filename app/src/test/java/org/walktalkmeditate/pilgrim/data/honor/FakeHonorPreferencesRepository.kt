// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [HonorPreferencesRepository]; the defaults match iOS's (voices on, no offline note said yet). */
class FakeHonorPreferencesRepository(
    voicesEnabled: Boolean = true,
    pilgrimageOfflineNoteShown: Boolean = false,
) : HonorPreferencesRepository {
    private val _voicesEnabled = MutableStateFlow(voicesEnabled)
    override val voicesEnabled: StateFlow<Boolean> = _voicesEnabled.asStateFlow()

    @Volatile
    var pilgrimageOfflineNoteShown: Boolean = pilgrimageOfflineNoteShown
        private set

    /** How many times the overview wrote the offline note down. */
    val offlineNoteWrites = AtomicInteger(0)

    override suspend fun setVoicesEnabled(value: Boolean) {
        _voicesEnabled.value = value
    }

    override suspend fun awaitVoicesEnabled(): Boolean = _voicesEnabled.value

    override suspend fun awaitPilgrimageOfflineNoteShown(): Boolean = pilgrimageOfflineNoteShown

    override suspend fun setPilgrimageOfflineNoteShown() {
        offlineNoteWrites.incrementAndGet()
        pilgrimageOfflineNoteShown = true
    }
}

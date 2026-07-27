// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Test double for [WhisperModelDownloadScheduler]. Records
 * [ensureEnqueued]/[retry]/[setCellularOverride] calls so runner tests
 * pin the model-absent self-heal (U10) and sheet-VM tests pin the
 * override/retry plumbing (U11) without WorkManager. [work] doubles as
 * the [ModelDownloadWorkSource] emission channel for store composition.
 */
class FakeWhisperModelDownloadScheduler : WhisperModelDownloadScheduler {

    var ensureEnqueuedCalls = 0
        private set
    var retryCalls = 0
        private set
    val cellularOverrideCalls = mutableListOf<Boolean>()

    val cellularOverride = MutableStateFlow(false)
    val work = MutableStateFlow<ModelDownloadWork?>(null)

    override suspend fun ensureEnqueued() {
        ensureEnqueuedCalls++
    }

    override suspend fun retry() {
        retryCalls++
    }

    override suspend fun setCellularOverride(enabled: Boolean) {
        cellularOverrideCalls += enabled
        cellularOverride.value = enabled
    }

    override fun observeCellularOverride(): Flow<Boolean> = cellularOverride

    override fun observe(): Flow<ModelDownloadWork?> = work
}

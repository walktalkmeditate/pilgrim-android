// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test double for [WhisperModelDownloadScheduler]. Records
 * [ensureEnqueued] calls so runner tests pin the model-absent
 * self-heal (U10) without WorkManager.
 */
class FakeWhisperModelDownloadScheduler : WhisperModelDownloadScheduler {

    var ensureEnqueuedCalls = 0
        private set
    var retryCalls = 0
        private set

    override suspend fun ensureEnqueued() {
        ensureEnqueuedCalls++
    }

    override suspend fun retry() {
        retryCalls++
    }

    override suspend fun setCellularOverride(enabled: Boolean) = Unit

    override fun observeCellularOverride(): Flow<Boolean> = flowOf(false)

    override fun observe(): Flow<ModelDownloadWork?> = flowOf(null)
}

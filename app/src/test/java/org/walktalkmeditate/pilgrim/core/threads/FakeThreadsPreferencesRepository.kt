// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [ThreadsPreferencesRepository]. Default
 * [initialThreadsAfterWalks] mirrors the production default (true) so a
 * fresh instance matches a fresh install.
 */
class FakeThreadsPreferencesRepository(
    initialThreadsAfterWalks: Boolean = true,
    initialImportGeneration: Int = 0,
) : ThreadsPreferencesRepository {
    private val _threadsAfterWalks = MutableStateFlow(initialThreadsAfterWalks)
    override val threadsAfterWalks: StateFlow<Boolean> = _threadsAfterWalks.asStateFlow()

    private val _importGeneration = MutableStateFlow(initialImportGeneration)
    override val importGeneration: StateFlow<Int> = _importGeneration.asStateFlow()

    var moonLineClearedCalls: Int = 0
        private set

    private var completedAtVersion: Int? = null
    private var completedAtImportGeneration: Int = 0
    private var checkpoint: BackfillCheckpoint = BackfillCheckpoint.EMPTY
    private var moonLineLastLunationIndexValue: Int? = null

    override suspend fun setThreadsAfterWalks(enabled: Boolean) {
        _threadsAfterWalks.value = enabled
    }

    override suspend fun bumpImportGeneration() {
        _importGeneration.value += 1
    }

    override suspend fun clearMoonLineIndex() {
        moonLineClearedCalls++
        moonLineLastLunationIndexValue = null
    }

    override suspend fun moonLineLastLunationIndex(): Int? = moonLineLastLunationIndexValue

    override suspend fun setMoonLineLastLunationIndex(index: Int) {
        moonLineLastLunationIndexValue = index
    }

    override suspend fun backfillCompletedAtVersion(): Int? = completedAtVersion

    override suspend fun backfillCompletedAtImportGeneration(): Int = completedAtImportGeneration

    override suspend fun setBackfillCompleted(version: Int, atImportGeneration: Int) {
        completedAtVersion = version
        completedAtImportGeneration = atImportGeneration
    }

    override suspend fun clearBackfillCompleted() {
        completedAtVersion = null
        completedAtImportGeneration = 0
    }

    override suspend fun backfillCheckpoint(): BackfillCheckpoint = checkpoint

    override suspend fun setBackfillCheckpoint(checkpoint: BackfillCheckpoint) {
        this.checkpoint = checkpoint
    }

    override suspend fun clearBackfillCheckpoint() {
        checkpoint = BackfillCheckpoint.EMPTY
    }
}

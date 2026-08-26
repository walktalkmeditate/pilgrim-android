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

    override suspend fun setThreadsAfterWalks(enabled: Boolean) {
        _threadsAfterWalks.value = enabled
    }

    override suspend fun bumpImportGeneration() {
        _importGeneration.value += 1
    }

    override suspend fun clearMoonLineIndex() {
        moonLineClearedCalls++
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [DataStore]<[Preferences]> for unit tests.
 *
 * Replaces file-backed
 * [androidx.datastore.preferences.core.PreferenceDataStoreFactory] in
 * tests. The real factory runs a single-writer actor on a real
 * dispatcher and does disk I/O; awaiting its emissions through a
 * StateFlow under a real-wall-clock timeout (or Turbine) flakes on
 * CPU-starved CI runners — the "ci-realtime-withtimeout" family
 * (`SoundscapeSelectionRepositoryTest`,
 * `WalkSummaryViewModelLightReadingGateTest`, and siblings).
 *
 * Backed by a [MutableStateFlow], so [data] emits synchronously on the
 * collector's dispatcher and [updateData] / `edit` complete inline.
 * Tests stay fully deterministic under `runTest` virtual time — no
 * `Dispatchers.Default`, no `withContext(realTimeDispatcher())`, no
 * generous timeouts. A single instance shared across two repository
 * constructions models persistence across process restart.
 */
class FakePreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)
    private val writeMutex = Mutex()

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = writeMutex.withLock {
        val updated = transform(state.value)
        state.value = updated
        updated
    }
}

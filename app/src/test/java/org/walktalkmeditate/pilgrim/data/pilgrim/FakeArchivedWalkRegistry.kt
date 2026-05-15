// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory fake for unit tests. No DataStore, no DI scope — direct
 * StateFlow + map for synchronous assertions.
 */
class FakeArchivedWalkRegistry(
    initial: Map<String, Double> = emptyMap(),
) : ArchivedWalkRegistry {
    private val state = MutableStateFlow(initial.toMutableMap().toMap())

    override val archivedRegistry: StateFlow<Map<String, Double>> = state.asStateFlow()

    override fun isArchived(uuid: String): Boolean = state.value.containsKey(uuid)

    override fun snapshot(): Map<String, Double> = state.value

    override suspend fun markArchived(uuid: String, archivedAtEpoch: Double) {
        state.value = state.value.toMutableMap().apply { put(uuid, archivedAtEpoch) }
    }

    override suspend fun unmarkArchived(uuid: String) {
        state.value = state.value.toMutableMap().apply { remove(uuid) }
    }

    override suspend fun clear() {
        state.value = emptyMap()
    }
}

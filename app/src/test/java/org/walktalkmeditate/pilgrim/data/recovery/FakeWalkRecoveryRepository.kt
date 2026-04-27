// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.recovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [WalkRecoveryRepository] — backed by a
 * `MutableStateFlow` so unit tests can stand it up without DataStore.
 */
class FakeWalkRecoveryRepository(
    initialId: Long? = null,
) : WalkRecoveryRepository {

    private val _id = MutableStateFlow(initialId)

    override val recoveredWalkId: StateFlow<Long?> = _id.asStateFlow()

    override fun markRecoveredBlocking(walkId: Long) {
        _id.value = walkId
    }

    override suspend fun clearRecovered() {
        _id.value = null
    }
}

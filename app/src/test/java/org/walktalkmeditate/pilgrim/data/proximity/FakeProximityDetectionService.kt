// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.proximity

import android.location.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test fake for [ProximityDetectionService]. Doesn't subscribe to
 * any flow; emits events on demand via [emit] for tests that
 * exercise the proximity-notification UI path.
 */
open class FakeProximityDetectionService : ProximityDetectionService() {
    private val _events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<ProximityEvent> = _events.asSharedFlow()

    var lastTargets: Set<ProximityTarget> = emptySet()
    var stopListeningCalls = 0
    var suppressedTargets = mutableListOf<String>()

    override fun updateTargets(newTargets: Set<ProximityTarget>) {
        lastTargets = newTargets
    }

    override fun bindToLocation(locations: Flow<Location?>) {
        // No-op: tests don't need a real location subscription.
    }

    override suspend fun stopListening() {
        stopListeningCalls += 1
    }

    override suspend fun suppressTarget(id: String) {
        suppressedTargets += id
    }

    override suspend fun resetSession() {
        // No-op: dedup not modeled in this fake.
    }

    /** Emit a proximity event from a test. */
    suspend fun emit(event: ProximityEvent) {
        _events.emit(event)
    }
}

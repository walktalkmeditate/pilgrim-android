// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Dedicated real-wall-clock dispatcher for tests that must await a
 * real-I/O-backed flow (file-backed DataStore, MockWebServer) from
 * inside `runTest`'s virtual time, via
 * `withContext(TestRealTimeDispatcher.instance) { withTimeout(N) { ... } }`.
 *
 * Replaces `Dispatchers.Default.limitedParallelism(1)`, which is only
 * a *view* of the shared `Dispatchers.Default` pool. Gradle runs test
 * classes in parallel; on a CPU-starved CI runner the Default pool
 * (size == CPU count, often 2) saturates with sibling test classes'
 * coroutines, so the awaiter coroutine cannot be scheduled and the
 * real-clock `withTimeout` expires even though the awaited value is
 * already available. This is the root of the ci-realtime-withtimeout
 * flake family (`SoundscapeSelectionRepositoryTest`,
 * `WalkSummaryViewModelTest`, the VoiceGuide/Soundscape catalog +
 * selection repos, `HemisphereRepositoryTest`, and siblings).
 *
 * A cached pool of dedicated daemon threads is never starved by
 * Default-pool contention — each awaiter gets its own thread on
 * demand. Semantics are otherwise identical (real wall clock, same
 * timeout), so this is a drop-in replacement with no logic change.
 */
object TestRealTimeDispatcher {
    val instance: CoroutineDispatcher =
        Executors.newCachedThreadPool { r ->
            Thread(r, "test-realtime-await").apply { isDaemon = true }
        }.asCoroutineDispatcher()
}

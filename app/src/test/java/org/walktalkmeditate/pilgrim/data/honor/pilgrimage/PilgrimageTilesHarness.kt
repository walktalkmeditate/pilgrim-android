// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue

/**
 * The world of iOS `PilgrimageTilesManagerTests` (`:10-24@7c200bf`): a
 * fresh fake loader, an in-memory calibration store for iOS's throwaway
 * `UserDefaults` suite, and a manager over them. The manager's main thread
 * is [main], a `StandardTestDispatcher` on the test's own thread, so work
 * it posts or resumes runs only when a test calls `runCurrent()`, and the
 * fake is driven on that same thread, as production's hopped callbacks are
 * (spec D §C2.T).
 */
internal class PilgrimageTilesHarness {

    private val scheduler = TestCoroutineScheduler()
    val main = StandardTestDispatcher(scheduler)

    /**
     * What escaped the manager's posted work into its scope's handler, which
     * production's logs. [run] fails a test that leaves anything here, so a
     * test that expects an escape clears it once it has asserted it.
     */
    val escaped = CopyOnWriteArrayList<Throwable>()

    /** The manager's own scope, the app scope's stand-in; cancelled and joined at the end of [run]. */
    val scope = CoroutineScope(SupervisorJob() + main + recordingHandler(escaped))

    val loader = FakeTileRegionLoader()
    val calibration = InMemoryTilesCalibration()

    /** iOS's `isWalkActive` closure is [FakeWalkSignals.screenUp]; [FakeWalkSignals.active] is Android's walk row. */
    val signals = FakeWalkSignals()

    val manager = PilgrimageTilesManager(loader, calibration, signals, main, scope)

    fun run(body: suspend TestScope.() -> Unit) = runTest(main) {
        try {
            body()
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
        assertTrue("escaped the manager's posted work: $escaped", escaped.isEmpty())
    }

    /** Runs what the manager's thread has queued: posted hooks, resumed loads. */
    fun drain() = scheduler.runCurrent()

    /** iOS's `untilPending()`: the manager's thread drained, and a load waiting for the test to complete it. */
    fun untilPending() {
        drain()
        assertTrue("the save to reach a load", loader.hasPendingWork)
    }

    companion object {
        /** What a save threw, or a failed test if it ended normally. */
        suspend fun Deferred<Unit>.failure(): PilgrimageError {
            try {
                await()
            } catch (failure: PilgrimageException) {
                return failure.error
            }
            throw AssertionError("expected the save to fail")
        }
    }
}

/** Production's scope handler logs; a test's records, so a test can say what escaped. */
internal fun recordingHandler(escaped: MutableList<Throwable>) = CoroutineExceptionHandler { _, failure -> escaped += failure }

/** iOS's throwaway `UserDefaults` suite. */
internal class InMemoryTilesCalibration : PilgrimageTilesCalibration {
    private val values = ConcurrentHashMap<String, Long>()

    override suspend fun stored(routeId: String): Long? = values[routeId]

    override fun store(routeId: String, bytesPerPack: Long) {
        values[routeId] = bytesPerPack
    }
}

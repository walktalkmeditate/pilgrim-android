// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.Clock

/**
 * The listed Ways a Begin is starting a walk on right now, between its
 * Start and the live session row `:tracker` writes for it: the expiry
 * sweep leaves them whole, as it leaves a live session's (shared-walk
 * spec correction 9). UI process only, as Begin is.
 */
@Singleton
class HonorBeginsInFlight @Inject constructor() {

    private val holds = mutableMapOf<String, Int>()

    /** Runs [block] with [wayId] held; two Begins on one Way each hold it. */
    suspend fun <T> holding(wayId: String, block: suspend () -> T): T {
        synchronized(holds) { holds[wayId] = (holds[wayId] ?: 0) + 1 }
        try {
            return block()
        } finally {
            synchronized(holds) {
                val left = (holds[wayId] ?: 1) - 1
                if (left > 0) holds[wayId] = left else holds.remove(wayId)
            }
        }
    }

    fun wayIds(): Set<String> = synchronized(holds) { holds.keys.toSet() }
}

/**
 * Runs iOS's expiry sweep ([WayStore.sweepExpired], shared-walk spec S3
 * §12–§14) and cancels the gather of every Way it touched, so no late
 * delivery lands in a folder it just removed. Every Way a live Honor
 * session names (a walk on, or one whose Honor step is still pending)
 * and every Begin's in flight is held back whole: iOS never meets a live
 * walk in a sweep, and Android can, when the UI process restarts while
 * `:tracker` walks on.
 *
 * Its triggers are iOS's: the Ways sheet appearing, and Settings → Ways
 * loading and after each delete there. At launch it runs at the end of
 * `HonorFinalizer.runAtLaunch`, after recovery and the finalize retry
 * have written every link they can, where iOS races recovery (spec
 * correction 10, pilgrim-ios #115; a dated R5 divergence at the gate).
 * The Data card doesn't sweep.
 */
@Singleton
class WaySweeper internal constructor(
    private val store: WayStore,
    private val heldWayIds: suspend () -> Set<String>,
    private val cancelGather: (wayId: String) -> Unit,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        store: WayStore,
        honorDao: HonorDao,
        begins: HonorBeginsInFlight,
        downloader: Provider<WayMediaDownloader>,
        clock: Clock,
    ) : this(
        store = store,
        heldWayIds = { honorDao.liveSessionWayIds().toSet() + begins.wayIds() },
        cancelGather = { downloader.get().cancel(it) },
        clock = clock,
        ioDispatcher = Dispatchers.IO,
    )

    /** @return the ids the sweep retired, their gathers already cancelled. */
    suspend fun sweep(): List<String> {
        val touched = withContext(ioDispatcher) {
            store.sweepExpired(Instant.ofEpochMilli(clock.now()), held = heldWayIds())
        }
        touched.forEach(cancelGather)
        return touched
    }
}

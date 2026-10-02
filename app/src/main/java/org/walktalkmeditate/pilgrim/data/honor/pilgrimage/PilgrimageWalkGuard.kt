// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import javax.inject.Inject
import javax.inject.Provider
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.honor.HonorBeginsInFlight
import org.walktalkmeditate.pilgrim.honor.HonorLinkRouter
import org.walktalkmeditate.pilgrim.walk.honor.HonorFinalizer

/**
 * What the package guard reads: iOS's one clause, and the three the
 * process split adds (pilgrimage-stage spec P2 §2, §10 item 7, A-1). iOS
 * refuses a package change while `activeWalkViewModel` is set, the walk
 * screen from Begin to the end of its save. Android's walk and its Honor
 * step outlive that screen in `:tracker`, so a walk row still open, a live
 * Honor session row, and a Begin not yet at its session row refuse too.
 */
interface PilgrimageWalkSignals {

    /** iOS's own clause: the walk screen up, pre-Start included, for any walk mode. */
    fun walkScreenUp(): Boolean

    /** A walk row still open: `:tracker` walking, as after the UI process was reclaimed. */
    suspend fun walkActive(): Boolean

    /**
     * The Way ids of every live Honor session row: a walk on, or a finished
     * one whose Honor step is still pending. Also what [org.walktalkmeditate.pilgrim.data.honor.WayStore.retireMany]
     * keeps as walked (P2 A-1, gap 2).
     */
    suspend fun liveSessionWayIds(): Set<String>

    /** A Begin between its Start and the session row `:tracker` writes for it. */
    fun beginInFlight(): Boolean

    /** [HonorFinalizer.finalizePending]: the finished walks' pending Honor steps, run once more. */
    suspend fun finalizePending()
}

/**
 * Whether a package change is refused with "finish your walk first". Live
 * rows that are the only reason are a finished walk's step still to run,
 * which iOS can never have (its step is synchronous), so it runs once and
 * the guard reads again rather than blocking until the next launch (P2 A-1).
 * Any other clause refuses at once, without it.
 */
internal suspend fun PilgrimageWalkSignals.refusesForAWalk(): Boolean {
    if (walkOn()) return true
    if (liveSessionWayIds().isEmpty()) return false
    finalizePending()
    return walkOn() || liveSessionWayIds().isNotEmpty()
}

private suspend fun PilgrimageWalkSignals.walkOn(): Boolean = walkScreenUp() || beginInFlight() || walkActive()

/**
 * The guard's inputs in the UI process, the only one that builds the
 * package manager. The router and the finalizer are reached through
 * providers: the router is the nav host's and Main's, and the finalizer
 * reaches the manager for its launch work.
 */
class UiPilgrimageWalkSignals @Inject constructor(
    private val router: Provider<HonorLinkRouter>,
    private val walks: WalkRepository,
    private val honorDao: HonorDao,
    private val begins: HonorBeginsInFlight,
    private val finalizer: Provider<HonorFinalizer>,
) : PilgrimageWalkSignals {

    override fun walkScreenUp(): Boolean = router.get().walkScreenUp

    override suspend fun walkActive(): Boolean = walks.getActiveWalk() != null

    override suspend fun liveSessionWayIds(): Set<String> = honorDao.liveSessionWayIds().toSet()

    override fun beginInFlight(): Boolean = begins.wayIds().isNotEmpty()

    override suspend fun finalizePending() {
        finalizer.get().finalizePending()
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The media download's WorkManager end, held by the test: each gather
 * gets a work the test moves through its states, as WorkManager would.
 */
class FakeWayMediaDownloadScheduler : WayMediaDownloadScheduler {

    data class Gather(val wayId: String, val replace: Boolean, val work: MutableStateFlow<WayMediaWork?>)

    val gathers = mutableListOf<Gather>()
    val cancels = mutableListOf<String>()

    /** Thrown by the next gathers instead of enqueueing, as WorkManager's full or broken database throws. */
    var failGather: Exception? = null

    /** Runs as each cancel is asked for. */
    var onCancel: (wayId: String) -> Unit = {}

    override suspend fun gather(wayId: String, replace: Boolean): Flow<WayMediaWork?> {
        failGather?.let { throw it }
        val work = MutableStateFlow<WayMediaWork?>(WayMediaWork(WayMediaWork.State.WAITING, report = null))
        gathers += Gather(wayId, replace, work)
        return work
    }

    override fun cancel(wayId: String) {
        cancels += wayId
        onCancel(wayId)
    }

    /** The latest gather's work for [wayId]. */
    fun work(wayId: String): MutableStateFlow<WayMediaWork?> = gathers.last { it.wayId == wayId }.work

    fun report(wayId: String, state: WayMediaWork.State, report: WayMediaReport?) {
        work(wayId).value = WayMediaWork(state, report)
    }
}

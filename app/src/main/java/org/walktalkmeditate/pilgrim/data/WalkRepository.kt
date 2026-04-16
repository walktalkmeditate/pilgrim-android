// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.dao.ActivityIntervalDao
import org.walktalkmeditate.pilgrim.data.dao.AltitudeSampleDao
import org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao
import org.walktalkmeditate.pilgrim.data.dao.WaypointDao
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.Waypoint

@Singleton
class WalkRepository @Inject constructor(
    private val database: PilgrimDatabase,
    private val walkDao: WalkDao,
    private val routeDao: RouteDataSampleDao,
    private val altitudeDao: AltitudeSampleDao,
    private val walkEventDao: WalkEventDao,
    private val activityIntervalDao: ActivityIntervalDao,
    private val waypointDao: WaypointDao,
) {
    fun observeAllWalks(): Flow<List<Walk>> = walkDao.observeAll()

    suspend fun getActiveWalk(): Walk? = walkDao.getActive()

    suspend fun getWalk(id: Long): Walk? = walkDao.getById(id)

    suspend fun startWalk(startTimestamp: Long, intention: String? = null): Walk {
        val draft = Walk(startTimestamp = startTimestamp, intention = intention)
        val id = walkDao.insert(draft)
        return draft.copy(id = id)
    }

    suspend fun finishWalk(walk: Walk, endTimestamp: Long) {
        walkDao.update(walk.copy(endTimestamp = endTimestamp))
    }

    /**
     * Finalize by id under a single Room transaction: reads the current
     * row and writes back the end_timestamp atomically. Returns `false`
     * if the walk row is gone by the time finalize runs (e.g., user
     * deleted the walk from another surface mid-finish). Prefer this over
     * the read+update two-call pattern from [getWalk] + [finishWalk].
     */
    suspend fun finishWalkAtomic(walkId: Long, endTimestamp: Long): Boolean =
        database.withTransaction {
            val walk = walkDao.getById(walkId) ?: return@withTransaction false
            walkDao.update(walk.copy(endTimestamp = endTimestamp))
            true
        }

    suspend fun updateWalk(walk: Walk) {
        walkDao.update(walk)
    }

    suspend fun deleteWalk(walk: Walk) {
        walkDao.delete(walk)
    }

    suspend fun recordLocation(sample: RouteDataSample): Long = routeDao.insert(sample)

    suspend fun recordLocations(samples: List<RouteDataSample>) = routeDao.insertAll(samples)

    suspend fun locationSamplesFor(walkId: Long): List<RouteDataSample> = routeDao.getForWalk(walkId)

    suspend fun recordAltitude(sample: AltitudeSample): Long = altitudeDao.insert(sample)

    suspend fun altitudeSamplesFor(walkId: Long): List<AltitudeSample> = altitudeDao.getForWalk(walkId)

    suspend fun recordEvent(event: WalkEvent): Long = walkEventDao.insert(event)

    suspend fun eventsFor(walkId: Long): List<WalkEvent> = walkEventDao.getForWalk(walkId)

    suspend fun recordActivityInterval(interval: ActivityInterval): Long = activityIntervalDao.insert(interval)

    suspend fun activityIntervalsFor(walkId: Long): List<ActivityInterval> = activityIntervalDao.getForWalk(walkId)

    suspend fun addWaypoint(waypoint: Waypoint): Long = waypointDao.insert(waypoint)

    suspend fun waypointsFor(walkId: Long): List<Waypoint> = waypointDao.getForWalk(walkId)
}

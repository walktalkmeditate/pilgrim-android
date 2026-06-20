// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

data class WalkFirstLatitude(val walkId: Long, val latitude: Double)

@Dao
interface RouteDataSampleDao {
    @Insert
    suspend fun insert(sample: RouteDataSample): Long

    @Insert
    suspend fun insertAll(samples: List<RouteDataSample>)

    @Query("SELECT * FROM route_data_samples WHERE walk_id = :walkId ORDER BY timestamp ASC, id ASC")
    suspend fun getForWalk(walkId: Long): List<RouteDataSample>

    /** Live-updating flow for the Active Walk map polyline. */
    @Query("SELECT * FROM route_data_samples WHERE walk_id = :walkId ORDER BY timestamp ASC")
    fun observeForWalk(walkId: Long): Flow<List<RouteDataSample>>

    @Query("SELECT COUNT(*) FROM route_data_samples WHERE walk_id = :walkId")
    suspend fun countForWalk(walkId: Long): Int

    @Query(
        "SELECT * FROM route_data_samples WHERE walk_id = :walkId " +
            "ORDER BY timestamp DESC LIMIT 1",
    )
    suspend fun getLastForWalk(walkId: Long): RouteDataSample?

    @Query(
        "SELECT * FROM route_data_samples WHERE walk_id = :walkId " +
            "ORDER BY timestamp ASC, id ASC LIMIT 1",
    )
    suspend fun getFirstForWalk(walkId: Long): RouteDataSample?

    /**
     * First-sample latitude for every walk, in one query — used to compute
     * each walk's location hemisphere for milestone season detection
     * without an N+1 over [getFirstForWalk]. The correlated subquery picks
     * the earliest `(timestamp, id)` row per walk — the SAME row
     * [getFirstForWalk] returns, so the milestone and seal paths never
     * disagree on a walk whose first samples share a timestamp.
     */
    @Query(
        "SELECT walk_id AS walkId, latitude FROM route_data_samples WHERE id = (" +
            "SELECT id FROM route_data_samples r WHERE r.walk_id = route_data_samples.walk_id " +
            "ORDER BY timestamp ASC, id ASC LIMIT 1)",
    )
    suspend fun firstLatitudePerWalk(): List<WalkFirstLatitude>

    @Query("DELETE FROM route_data_samples WHERE walk_id = :walkId")
    suspend fun deleteByWalkId(walkId: Long): Int
}

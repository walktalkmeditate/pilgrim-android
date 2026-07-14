// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.Waypoint

/** Projection row for [WaypointDao.iconsPerWalk]. */
data class WaypointIconRow(val walkId: Long, val icon: String?)

@Dao
interface WaypointDao {
    @Insert
    suspend fun insert(waypoint: Waypoint): Long

    @Query("SELECT * FROM waypoints WHERE walk_id = :walkId ORDER BY timestamp ASC")
    suspend fun getForWalk(walkId: Long): List<Waypoint>

    @Query("SELECT * FROM waypoints WHERE walk_id = :walkId ORDER BY timestamp ASC")
    fun observeForWalk(walkId: Long): Flow<List<Waypoint>>

    @Query("SELECT COUNT(*) FROM waypoints WHERE walk_id = :walkId")
    fun observeCountForWalk(walkId: Long): Flow<Int>

    /**
     * Every icon-carrying waypoint's (walkId, icon), in one query —
     * the goshuin book counts seek arrivals across ALL walks per load,
     * so this replaces N per-walk fetches (same no-N+1 rule as
     * `RouteDataSampleDao.firstLatitudePerWalk`). Iconless waypoints
     * can never be arrivals, so they're filtered at the source.
     */
    @Query("SELECT walk_id AS walkId, icon FROM waypoints WHERE icon IS NOT NULL")
    suspend fun iconsPerWalk(): List<WaypointIconRow>

    @Query("DELETE FROM waypoints WHERE walk_id = :walkId")
    suspend fun deleteByWalkId(walkId: Long): Int
}

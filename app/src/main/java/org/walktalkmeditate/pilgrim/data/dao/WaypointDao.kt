// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.walktalkmeditate.pilgrim.data.entity.Waypoint

@Dao
interface WaypointDao {
    @Insert
    suspend fun insert(waypoint: Waypoint): Long

    @Query("SELECT * FROM waypoints WHERE walk_id = :walkId ORDER BY timestamp ASC")
    suspend fun getForWalk(walkId: Long): List<Waypoint>
}

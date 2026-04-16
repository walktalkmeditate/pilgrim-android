// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval

@Dao
interface ActivityIntervalDao {
    @Insert
    suspend fun insert(interval: ActivityInterval): Long

    @Insert
    suspend fun insertAll(intervals: List<ActivityInterval>)

    @Query("SELECT * FROM activity_intervals WHERE walk_id = :walkId ORDER BY start_timestamp ASC")
    suspend fun getForWalk(walkId: Long): List<ActivityInterval>
}

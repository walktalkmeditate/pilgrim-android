// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent

@Dao
interface WalkEventDao {
    @Insert
    suspend fun insert(event: WalkEvent): Long

    @Query("SELECT * FROM walk_events WHERE walk_id = :walkId ORDER BY timestamp ASC")
    suspend fun getForWalk(walkId: Long): List<WalkEvent>
}

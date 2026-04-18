// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

@Dao
interface RouteDataSampleDao {
    @Insert
    suspend fun insert(sample: RouteDataSample): Long

    @Insert
    suspend fun insertAll(samples: List<RouteDataSample>)

    @Query("SELECT * FROM route_data_samples WHERE walk_id = :walkId ORDER BY timestamp ASC")
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
}

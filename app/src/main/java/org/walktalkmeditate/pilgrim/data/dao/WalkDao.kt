// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.Walk

@Dao
interface WalkDao {
    @Insert
    suspend fun insert(walk: Walk): Long

    @Update
    suspend fun update(walk: Walk)

    @Delete
    suspend fun delete(walk: Walk)

    @Query("DELETE FROM walks WHERE id = :walkId")
    suspend fun deleteById(walkId: Long)

    @Query("UPDATE walks SET intention = :intention WHERE id = :walkId")
    suspend fun updateIntention(walkId: Long, intention: String?)

    @Query("SELECT * FROM walks WHERE id = :id")
    suspend fun getById(id: Long): Walk?

    @Query("SELECT * FROM walks WHERE end_timestamp IS NULL ORDER BY start_timestamp DESC LIMIT 1")
    suspend fun getActive(): Walk?

    @Query("SELECT * FROM walks ORDER BY start_timestamp DESC")
    fun observeAll(): Flow<List<Walk>>

    @Query("SELECT * FROM walks ORDER BY start_timestamp DESC")
    suspend fun getAll(): List<Walk>

    @Query("SELECT uuid FROM walks")
    suspend fun getAllUuids(): List<String>

    @Query(
        "SELECT * FROM walks WHERE end_timestamp IS NOT NULL " +
            "ORDER BY end_timestamp DESC LIMIT 1",
    )
    suspend fun getMostRecentFinished(): Walk?

    @Query(
        "SELECT * FROM walks WHERE end_timestamp IS NOT NULL " +
            "ORDER BY end_timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun getRecentFinished(limit: Int): List<Walk>
}

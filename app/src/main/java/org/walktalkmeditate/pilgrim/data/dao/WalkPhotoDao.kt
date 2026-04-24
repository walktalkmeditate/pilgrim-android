// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

@Dao
interface WalkPhotoDao {
    @Insert
    suspend fun insert(photo: WalkPhoto): Long

    @Insert
    suspend fun insertAll(photos: List<WalkPhoto>): List<Long>

    @Delete
    suspend fun delete(photo: WalkPhoto)

    @Query("DELETE FROM walk_photos WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM walk_photos WHERE id = :id")
    suspend fun getById(id: Long): WalkPhoto?

    @Query("SELECT * FROM walk_photos WHERE walk_id = :walkId ORDER BY pinned_at ASC, id ASC")
    suspend fun getForWalk(walkId: Long): List<WalkPhoto>

    /**
     * Live-updating flow for the Reliquary grid. Emits a new List on
     * every insert/delete that touches the given walk's photos.
     */
    @Query("SELECT * FROM walk_photos WHERE walk_id = :walkId ORDER BY pinned_at ASC, id ASC")
    fun observeForWalk(walkId: Long): Flow<List<WalkPhoto>>

    @Query("SELECT COUNT(*) FROM walk_photos WHERE walk_id = :walkId")
    suspend fun countForWalk(walkId: Long): Int

    /**
     * Count rows referencing [photoUri] across ALL walks. Used by
     * `WalkRepository.unpinPhoto` to decide whether the persistable
     * URI grant is safe to release: grants are shared app-wide, so
     * releasing while another walk still pins the same URI would
     * tombstone that walk's tile on cold start.
     */
    @Query("SELECT COUNT(*) FROM walk_photos WHERE photo_uri = :photoUri")
    suspend fun countByPhotoUri(photoUri: String): Int
}

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
     * Stage 10-I: live total of pinned photos across ALL walks. Drives
     * the export-confirmation sheet's "≈X photos" line so the user
     * sees an accurate count without an explicit re-query whenever
     * they pin/unpin a photo on another screen.
     */
    @Query("SELECT COUNT(*) FROM walk_photos")
    fun observeAllCount(): Flow<Int>

    /**
     * Count rows referencing [photoUri] across ALL walks. Used by
     * `WalkRepository.unpinPhoto` to decide whether the persistable
     * URI grant is safe to release: grants are shared app-wide, so
     * releasing while another walk still pins the same URI would
     * tombstone that walk's tile on cold start.
     */
    @Query("SELECT COUNT(*) FROM walk_photos WHERE photo_uri = :photoUri")
    suspend fun countByPhotoUri(photoUri: String): Int

    /**
     * Stage 7-B: write ML Kit analysis result back to a row. Per-id
     * `@Query` rather than `@Update` on the full row so a concurrent
     * writer updating other columns can't be clobbered.
     */
    @Query(
        "UPDATE walk_photos SET " +
            "top_label = :label, " +
            "top_label_confidence = :confidence, " +
            "analyzed_at = :analyzedAt " +
            "WHERE id = :id",
    )
    suspend fun updateAnalysis(
        id: Long,
        label: String?,
        confidence: Double?,
        analyzedAt: Long,
    )

    /**
     * Stage 7-B: rows still awaiting analysis for a walk, ordered the
     * same way the grid reads so the analyzer processes photos in
     * pin-order. [PhotoAnalysisRunner] drives the batch off this.
     */
    @Query(
        "SELECT * FROM walk_photos " +
            "WHERE walk_id = :walkId AND analyzed_at IS NULL " +
            "ORDER BY pinned_at ASC, id ASC",
    )
    suspend fun getPendingAnalysisForWalk(walkId: Long): List<WalkPhoto>
}

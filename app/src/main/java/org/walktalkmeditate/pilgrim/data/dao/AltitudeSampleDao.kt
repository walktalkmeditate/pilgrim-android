// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample

@Dao
interface AltitudeSampleDao {
    @Insert
    suspend fun insert(sample: AltitudeSample): Long

    @Insert
    suspend fun insertAll(samples: List<AltitudeSample>)

    @Query("SELECT * FROM altitude_samples WHERE walk_id = :walkId ORDER BY timestamp ASC")
    suspend fun getForWalk(walkId: Long): List<AltitudeSample>

    /**
     * Cross-process Flow of altitude samples for [walkId]. Backs the
     * active walk screen's live ascent display: the `:tracker`
     * process inserts samples via [WalkEffect.PersistLocation];
     * multi-instance Room invalidation re-emits this flow in the UI
     * process so the stats sheet can recompute ascend on every new
     * sample.
     */
    @Query("SELECT * FROM altitude_samples WHERE walk_id = :walkId ORDER BY timestamp ASC")
    fun observeForWalk(walkId: Long): Flow<List<AltitudeSample>>
}

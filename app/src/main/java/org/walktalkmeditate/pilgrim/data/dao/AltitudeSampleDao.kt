// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample

@Dao
interface AltitudeSampleDao {
    @Insert
    suspend fun insert(sample: AltitudeSample): Long

    @Insert
    suspend fun insertAll(samples: List<AltitudeSample>)

    @Query("SELECT * FROM altitude_samples WHERE walk_id = :walkId ORDER BY timestamp ASC")
    suspend fun getForWalk(walkId: Long): List<AltitudeSample>
}

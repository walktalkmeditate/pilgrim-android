// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent

@Dao
interface WalkEventDao {
    @Insert
    suspend fun insert(event: WalkEvent): Long

    @Query("SELECT * FROM walk_events WHERE walk_id = :walkId ORDER BY timestamp ASC")
    suspend fun getForWalk(walkId: Long): List<WalkEvent>

    /**
     * Observe walk-lifecycle events for [walkId] in chronological
     * order. Backs [UiWalkController]'s reactive state derivation:
     * the UI process replays PAUSED/RESUMED/MEDITATION_START/END
     * timestamps to compute the current state without sharing the
     * `:tracker` process's in-memory reducer state. Multi-instance
     * Room invalidation re-emits when the tracker inserts a new
     * event.
     */
    @Query("SELECT * FROM walk_events WHERE walk_id = :walkId ORDER BY timestamp ASC")
    fun observeForWalk(walkId: Long): Flow<List<WalkEvent>>

    /**
     * Ids of all walks carrying at least one event of the given type,
     * in one query. Backs the journal's seek-glyph flag (a seek walk
     * is marked by one `SEEK_MODE` event) — iOS `fetchSeekWalkIDs`
     * bulk-fetch rule: never fault per-walk event lists to answer a
     * whole-journal question. [eventTypeName] is the enum name string
     * exactly as the converter persists it.
     */
    @Query("SELECT DISTINCT walk_id FROM walk_events WHERE event_type = :eventTypeName")
    suspend fun walkIdsWithEvent(eventTypeName: String): List<Long>

    @Query("DELETE FROM walk_events WHERE walk_id = :walkId")
    suspend fun deleteByWalkId(walkId: Long): Int
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

data class WalkFirstLatitude(val walkId: Long, val latitude: Double)

/**
 * U9: one row of [RouteDataSampleDao.routeSamplesNear] — the Android
 * equivalent of iOS `routeFixNear`'s bounded ±90s fetch (parity spec
 * `docs/parity/2026-08-25-threads-senses-port.md`). [horizontalAccuracyMeters]
 * is projected as-is (nullable) — `qualifies()` in `core/threads/DossierSenses.kt`
 * re-checks accuracy downstream; there is no SQL-side accuracy filter.
 */
data class RouteSampleWindowRow(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Float?,
)

@Dao
interface RouteDataSampleDao {
    @Insert
    suspend fun insert(sample: RouteDataSample): Long

    @Insert
    suspend fun insertAll(samples: List<RouteDataSample>)

    @Query("SELECT * FROM route_data_samples WHERE walk_id = :walkId ORDER BY timestamp ASC, id ASC")
    suspend fun getForWalk(walkId: Long): List<RouteDataSample>

    /**
     * Live-updating flow for the Active Walk map polyline. `id ASC` tiebreaks
     * equal timestamps so the order is deterministic across emissions (matches
     * [getForWalk]) — the live-route incremental mapping relies on a stable
     * prefix between successive emissions.
     */
    @Query("SELECT * FROM route_data_samples WHERE walk_id = :walkId ORDER BY timestamp ASC, id ASC")
    fun observeForWalk(walkId: Long): Flow<List<RouteDataSample>>

    @Query("SELECT COUNT(*) FROM route_data_samples WHERE walk_id = :walkId")
    suspend fun countForWalk(walkId: Long): Int

    @Query(
        "SELECT * FROM route_data_samples WHERE walk_id = :walkId " +
            "ORDER BY timestamp DESC LIMIT 1",
    )
    suspend fun getLastForWalk(walkId: Long): RouteDataSample?

    @Query(
        "SELECT * FROM route_data_samples WHERE walk_id = :walkId " +
            "ORDER BY timestamp ASC, id ASC LIMIT 1",
    )
    suspend fun getFirstForWalk(walkId: Long): RouteDataSample?

    /**
     * First-sample latitude for every walk, in one query — used to compute
     * each walk's location hemisphere for milestone season detection
     * without an N+1 over [getFirstForWalk]. The correlated subquery picks
     * the earliest `(timestamp, id)` row per walk — the SAME row
     * [getFirstForWalk] returns, so the milestone and seal paths never
     * disagree on a walk whose first samples share a timestamp.
     */
    @Query(
        "SELECT walk_id AS walkId, latitude FROM route_data_samples WHERE id = (" +
            "SELECT id FROM route_data_samples r WHERE r.walk_id = route_data_samples.walk_id " +
            "ORDER BY timestamp ASC, id ASC LIMIT 1)",
    )
    suspend fun firstLatitudePerWalk(): List<WalkFirstLatitude>

    @Query("DELETE FROM route_data_samples WHERE walk_id = :walkId")
    suspend fun deleteByWalkId(walkId: Long): Int

    /**
     * U9: every route sample within `[windowStart, windowEnd]` (both
     * inclusive), across ALL walks — NOT scoped to a single `walk_id`,
     * matching iOS `routeFixNear`'s own unscoped fetch (a recording's
     * nearest fix could theoretically resolve from a neighboring walk's
     * samples at the same wall-clock instant). Callers pass a ±90s
     * window built from [org.walktalkmeditate.pilgrim.core.threads.DossierSenses.HYGIENE_MAX_GAP_SECONDS] —
     * never a hand-copied literal. Capped at 240 rows (~1Hz logging
     * yields far fewer inside a 180s window in practice); ordered
     * ascending so the cap never bites the earliest candidates.
     */
    @Query(
        "SELECT timestamp, latitude, longitude, horizontal_accuracy AS horizontalAccuracyMeters " +
            "FROM route_data_samples WHERE timestamp BETWEEN :windowStart AND :windowEnd " +
            "ORDER BY timestamp ASC LIMIT 240",
    )
    suspend fun routeSamplesNear(windowStart: Long, windowEnd: Long): List<RouteSampleWindowRow>
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * U7: [WalkDao.getWalkLite]'s raw Room projection shape — see
 * [org.walktalkmeditate.pilgrim.data.dao.RecordingWalkLiteRow] (the
 * recording-joined equivalent) for the `toWalkLite()` conversion this
 * mirrors.
 */
data class WalkLiteRow(
    val walkId: Long,
    val startTimestamp: Long,
    val intention: String?,
    val weatherCondition: String?,
) {
    fun toWalkLite(): org.walktalkmeditate.pilgrim.core.threads.WalkLite =
        org.walktalkmeditate.pilgrim.core.threads.WalkLite(
            walkId = walkId,
            startedAt = java.time.Instant.ofEpochMilli(startTimestamp),
            intention = intention,
            weatherCondition = weatherCondition,
        )
}

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

    @Query("UPDATE walks SET favicon = :favicon WHERE id = :walkId")
    suspend fun updateFavicon(walkId: Long, favicon: String?)

    @Query(
        "UPDATE walks SET distance_meters = :distanceMeters, " +
            "meditation_seconds = :meditationSeconds WHERE id = :id",
    )
    suspend fun updateAggregates(id: Long, distanceMeters: Double?, meditationSeconds: Long?)

    @Query(
        "UPDATE walks SET weather_condition = :condition, " +
            "weather_temperature = :temperature, weather_humidity = :humidity, " +
            "weather_wind_speed = :windSpeed WHERE id = :id",
    )
    suspend fun updateWeather(
        id: Long,
        condition: String?,
        temperature: Double?,
        humidity: Double?,
        windSpeed: Double?,
    )

    @Query("UPDATE walks SET steps = :steps WHERE id = :id")
    suspend fun updateSteps(id: Long, steps: Int?)

    @Query("SELECT * FROM walks WHERE id = :id")
    suspend fun getById(id: Long): Walk?

    @Query("SELECT * FROM walks WHERE end_timestamp IS NULL ORDER BY start_timestamp DESC LIMIT 1")
    suspend fun getActive(): Walk?

    /**
     * Observe the in-progress walk row (`end_timestamp IS NULL`) as a
     * cross-process Flow. Backs [UiWalkController]'s state derivation
     * in the UI process — the `:tracker` process inserts the row and
     * updates it; multi-instance Room invalidation re-emits here.
     * Emits null when no walk is in progress.
     */
    @Query("SELECT * FROM walks WHERE end_timestamp IS NULL ORDER BY start_timestamp DESC LIMIT 1")
    fun observeActive(): Flow<Walk?>

    @Query("SELECT * FROM walks ORDER BY start_timestamp DESC")
    fun observeAll(): Flow<List<Walk>>

    @Query("SELECT * FROM walks ORDER BY start_timestamp DESC")
    suspend fun getAll(): List<Walk>

    @Query("SELECT uuid FROM walks")
    suspend fun getAllUuids(): List<String>

    @Query("SELECT * FROM walks WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): Walk?

    @Query("DELETE FROM walks WHERE uuid IN (:uuids)")
    suspend fun deleteByUuids(uuids: List<String>): Int

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

    /**
     * Walks finished BEFORE the given start timestamp, capped to
     * [limit] most recent (DESC by end time). Used by Walk Summary's
     * milestone-callout chain so re-opening an older walk's summary
     * doesn't include later walks in the past-totals comparison.
     * Verbatim port of iOS `WalkSummaryView.swift:436` predicate.
     */
    @Query(
        "SELECT * FROM walks WHERE end_timestamp IS NOT NULL " +
            "AND start_timestamp < :currentStart " +
            "ORDER BY end_timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun getRecentFinishedBefore(currentStart: Long, limit: Int): List<Walk>

    /**
     * U7: bounded [WalkLiteRow] projection for one walk — the source of
     * [ThreadsDossierBuilder][org.walktalkmeditate.pilgrim.core.threads.ThreadsDossierBuilder]'s
     * `MemoKey.intention` field (BEH-36) and the current walk's own
     * [org.walktalkmeditate.pilgrim.core.threads.WalkLite] when it isn't
     * otherwise reachable through the recording→walk join (e.g. a walk
     * with no recordings yet).
     */
    @Query(
        "SELECT id AS walkId, start_timestamp AS startTimestamp, intention, " +
            "weather_condition AS weatherCondition FROM walks WHERE id = :walkId",
    )
    suspend fun getWalkLite(walkId: Long): WalkLiteRow?
}

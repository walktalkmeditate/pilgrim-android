// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.proximity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * iOS parity `PendingPlacement.swift@db4196e` — one queued placement
 * that failed at POST time and will be retried when the geo cache
 * re-fetches. `payload` carries the JSON body WITHOUT coordinates;
 * `latitude` + `longitude` are merged in at replay time so the
 * stored lat/lon always wins (matches iOS `injectCoordinates`).
 *
 * Wire `type` value uses lowercase strings (`whisper` / `stone`).
 * Note "stone" not "cairn" — matches iOS even though the POST
 * endpoint is `/api/cairns`.
 */
@Serializable
data class PendingPlacement(
    val type: PlacementType,
    val latitude: Double,
    val longitude: Double,
    val payload: String,
    @SerialName("timestamp") val timestampMs: Long,
) {
    @Serializable
    enum class PlacementType {
        @SerialName("whisper") Whisper,
        @SerialName("stone") Stone,
    }

    companion object {
        const val TTL_MS: Long = 7L * 24 * 3600 * 1000
        const val MAX_QUEUE: Int = 50
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CollectiveCounterDelta(
    val walks: Int = 0,
    @SerialName("distance_km") val distanceKm: Double = 0.0,
    @SerialName("meditation_min") val meditationMin: Int = 0,
    @SerialName("talk_min") val talkMin: Int = 0,
) {
    fun isEmpty(): Boolean = walks == 0 &&
        distanceKm == 0.0 &&
        meditationMin == 0 &&
        talkMin == 0

    operator fun plus(other: CollectiveCounterDelta) = CollectiveCounterDelta(
        walks = walks + other.walks,
        distanceKm = distanceKm + other.distanceKm,
        meditationMin = meditationMin + other.meditationMin,
        talkMin = talkMin + other.talkMin,
    )

    operator fun minus(other: CollectiveCounterDelta) = CollectiveCounterDelta(
        walks = walks - other.walks,
        distanceKm = distanceKm - other.distanceKm,
        meditationMin = meditationMin - other.meditationMin,
        talkMin = talkMin - other.talkMin,
    )

    /**
     * Clamp to the backend's per-POST caps (counter.ts:38-41). The
     * worker silently clamps any oversize value to its cap and
     * returns OK regardless. If the client subtracted the unclamped
     * value from pending on Success, the residual would silently
     * vanish. Always POST the clamped value and subtract the
     * clamped value from pending — leaves the overflow in pending
     * for the next walk.
     */
    fun clampToBackendCaps() = CollectiveCounterDelta(
        walks = walks.coerceIn(0, CollectiveConfig.MAX_WALKS_PER_POST),
        distanceKm = distanceKm.coerceIn(0.0, CollectiveConfig.MAX_DISTANCE_KM_PER_POST),
        meditationMin = meditationMin.coerceIn(0, CollectiveConfig.MAX_DURATION_MIN_PER_POST),
        talkMin = talkMin.coerceIn(0, CollectiveConfig.MAX_DURATION_MIN_PER_POST),
    )
}

data class CollectiveWalkSnapshot(
    val distanceKm: Double,
    val meditationMin: Int,
    val talkMin: Int,
)

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
}

data class CollectiveWalkSnapshot(
    val distanceKm: Double,
    val meditationMin: Int,
    val talkMin: Int,
)

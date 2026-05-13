// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.location.Location
import org.walktalkmeditate.pilgrim.data.cairn.CachedCairn
import org.walktalkmeditate.pilgrim.data.cairn.CairnTier
import org.walktalkmeditate.pilgrim.data.whisper.CachedWhisper
import org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory

/**
 * iOS parity `ActiveWalkView.swift:602-659@db4196e` — proximity-pin
 * visibility filter. Pure function so it's unit-testable without
 * standing up Mapbox.
 *
 * Filter chain (verbatim iOS order):
 *  1. 2000m visibility radius — drop any pin farther than that
 *  2. Whisper-only: skip if category can't be resolved
 *  3. Sort all surviving candidates by distance, closest first
 *  4. 30-cap + 15m same-type separation in a single pass:
 *      - hard cap at 30 total
 *      - for each candidate, drop if within 15m of an
 *        already-accepted same-type pin (closer wins via sort order)
 *
 * The output List preserves accepted insertion order (closer first).
 */
object ProximityPinFilter {

    const val VISIBILITY_RADIUS_M = 2_000.0
    const val MAX_VISIBLE_PINS = 30
    const val SAME_TYPE_SEPARATION_M = 15.0

    sealed class Pin {
        abstract val id: String
        abstract val latitude: Double
        abstract val longitude: Double
        // Reviewer-flagged: do NOT add per-frame fields (e.g.
        // `isNearby`) to these data classes. `distinctUntilChanged`
        // on the upstream pin flow uses structural equality — a
        // field that flips with GPS jitter (continuously crossing
        // any threshold) would defeat the gate and trigger 1Hz
        // Mapbox `deleteAll` + 30-bitmap allocation churn for every
        // tick the user is near a pin.
        data class Whisper(
            override val id: String,
            override val latitude: Double,
            override val longitude: Double,
            val category: WhisperCategory,
        ) : Pin()
        data class Cairn(
            override val id: String,
            override val latitude: Double,
            override val longitude: Double,
            val tier: CairnTier,
            val stoneCount: Int,
        ) : Pin()
    }

    fun build(
        whispers: List<CachedWhisper>,
        cairns: List<CachedCairn>,
        userLatitude: Double,
        userLongitude: Double,
    ): List<Pin> {
        data class Candidate(val pin: Pin, val distance: Double)
        val candidates = mutableListOf<Candidate>()
        val out = FloatArray(1)

        for (w in whispers) {
            Location.distanceBetween(
                userLatitude, userLongitude, w.latitude, w.longitude, out,
            )
            val dist = out[0].toDouble()
            if (dist > VISIBILITY_RADIUS_M) continue
            val cat = w.resolvedCategory ?: continue
            candidates += Candidate(
                Pin.Whisper(
                    id = w.id,
                    latitude = w.latitude,
                    longitude = w.longitude,
                    category = cat,
                ),
                dist,
            )
        }
        for (c in cairns) {
            Location.distanceBetween(
                userLatitude, userLongitude, c.latitude, c.longitude, out,
            )
            val dist = out[0].toDouble()
            if (dist > VISIBILITY_RADIUS_M) continue
            candidates += Candidate(
                Pin.Cairn(
                    id = c.id,
                    latitude = c.latitude,
                    longitude = c.longitude,
                    tier = c.tier,
                    stoneCount = c.stoneCount,
                ),
                dist,
            )
        }
        candidates.sortBy { it.distance }

        val accepted = mutableListOf<Pin>()
        for (cand in candidates) {
            if (accepted.size >= MAX_VISIBLE_PINS) break
            val sameTypeTooClose = accepted.any { other ->
                if (other::class != cand.pin::class) return@any false
                Location.distanceBetween(
                    cand.pin.latitude, cand.pin.longitude,
                    other.latitude, other.longitude, out,
                )
                out[0].toDouble() < SAME_TYPE_SEPARATION_M
            }
            if (!sameTypeTooClose) accepted += cand.pin
        }
        return accepted
    }
}

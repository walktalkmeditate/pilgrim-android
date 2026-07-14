// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import kotlin.math.abs

/**
 * What the map should show for a seek: fog over the active clearing, faint
 * halos over found ones, and nothing at all for unrevealed clearings so the
 * chain's count stays hidden. Pure state — rendering lives in
 * `ui/walk/map/SeekFogRenderer.kt`. Port spec
 * `docs/parity/2026-07-14-port-seek-fog-u6.md` (SeekFogModel.swift@c1745e8);
 * the crescent field arrives with U7.
 */
data class SeekFogState(
    val circles: List<FogCircle>,
    /**
     * Celestial override for the active fog's color (turning or full moon);
     * null renders the default fog grey. Fixed per walk. Halos keep dawn.
     */
    val tintHex: String? = null,
) {

    data class FogCircle(
        val id: String,
        val center: SeekPoint,
        val radiusMeters: Double,
        /** 0 = dissolved (arrived/halo), 1..N = distance buckets, thicker far. */
        val opacityBucket: Int,
        /** Found clearings keep a faint persistent halo after their reveal. */
        val isHalo: Boolean,
    )

    /**
     * The active clearing's current bucket — callers feed this back into
     * the next [SeekFogModel.fogState] call so hysteresis has a reference.
     */
    val activeFogBucket: Int?
        get() = circles.firstOrNull { !it.isHalo }?.opacityBucket
}

/**
 * One pulse of the seek heartbeat as the map sees it: the token advances
 * per pulse, and alignment/closeness shape the crescent's flare (U7) the
 * same way they already shape the ping and the haptic.
 */
data class SeekPulseVisual(
    val token: Int,
    val aligned: Boolean,
    val closeness: Double,
) {
    companion object {
        val NONE = SeekPulseVisual(token = 0, aligned = false, closeness = 0.0)
    }
}

object SeekFogModel {

    /**
     * Bucket k covers distances below boundary k (ascending); anything at or
     * beyond the last boundary — or with no fix yet — is the thickest bucket.
     */
    val DISTANCE_BUCKET_BOUNDARIES_METERS = listOf(150.0, 300.0, 600.0, 1200.0)

    /**
     * A fix must land this fraction beyond a boundary before an adjacent
     * bucket change applies, so GPS jitter on the line cannot thrash writes.
     */
    const val HYSTERESIS_FRACTION = 0.1

    val BUCKET_OPACITIES = listOf(0.25, 0.35, 0.45, 0.55, 0.65)
    const val HALO_OPACITY = 0.12
    const val DISSOLVED_OPACITY = 0.0

    val farthestBucket: Int
        get() = DISTANCE_BUCKET_BOUNDARIES_METERS.size + 1

    fun fogState(
        chain: SeekChain,
        activeIndex: Int,
        phase: SeekEnginePhase,
        distanceToActiveMeters: Double?,
        previousActiveBucket: Int? = null,
        tintHex: String? = null,
    ): SeekFogState {
        val count = chain.clearings.size
        if (count == 0) return SeekFogState(circles = emptyList())
        val clampedActive = activeIndex.coerceIn(0, count - 1)
        val haloCount =
            if (phase == SeekEnginePhase.COMPLETE) clampedActive + 1 else clampedActive

        val circles = buildList {
            for (index in 0 until haloCount) {
                val clearing = chain.clearings[index]
                add(
                    SeekFogState.FogCircle(
                        id = fogCircleId(index),
                        center = clearing.center,
                        radiusMeters = clearing.radiusMeters,
                        opacityBucket = 0,
                        isHalo = true,
                    ),
                )
            }
            if (phase != SeekEnginePhase.COMPLETE) {
                val clearing = chain.clearings[clampedActive]
                val bucket = if (phase == SeekEnginePhase.GUIDING) {
                    bucketApplyingHysteresis(
                        distanceMeters = distanceToActiveMeters,
                        currentBucket = previousActiveBucket,
                    )
                } else {
                    0
                }
                add(
                    SeekFogState.FogCircle(
                        id = fogCircleId(clampedActive),
                        center = clearing.center,
                        radiusMeters = clearing.radiusMeters,
                        opacityBucket = bucket,
                        isHalo = false,
                    ),
                )
            }
        }
        return SeekFogState(circles = circles, tintHex = tintHex)
    }

    fun opacityBucket(distanceMeters: Double?): Int {
        if (distanceMeters == null) return farthestBucket
        DISTANCE_BUCKET_BOUNDARIES_METERS.forEachIndexed { index, boundary ->
            if (distanceMeters < boundary) return index + 1
        }
        return farthestBucket
    }

    /**
     * Adjacent-bucket changes only apply once the fix is a margin past the
     * shared boundary; jumps of 2+ buckets (reroll, first fix) apply as-is.
     */
    fun bucketApplyingHysteresis(distanceMeters: Double?, currentBucket: Int?): Int {
        val raw = opacityBucket(distanceMeters)
        if (currentBucket == null || currentBucket !in 1..farthestBucket ||
            distanceMeters == null || raw == currentBucket
        ) {
            return raw
        }
        if (abs(raw - currentBucket) != 1) return raw
        val boundary = DISTANCE_BUCKET_BOUNDARIES_METERS[minOf(raw, currentBucket) - 1]
        val margin = boundary * HYSTERESIS_FRACTION
        return if (raw < currentBucket) {
            if (distanceMeters <= boundary - margin) raw else currentBucket
        } else {
            if (distanceMeters >= boundary + margin) raw else currentBucket
        }
    }

    fun opacity(bucket: Int, isHalo: Boolean): Double {
        if (isHalo) return HALO_OPACITY
        if (bucket < 1) return DISSOLVED_OPACITY
        return BUCKET_OPACITIES[minOf(bucket, BUCKET_OPACITIES.size) - 1]
    }

    fun fogCircleId(clearingIndex: Int): String = "seek-fog-$clearingIndex"
}

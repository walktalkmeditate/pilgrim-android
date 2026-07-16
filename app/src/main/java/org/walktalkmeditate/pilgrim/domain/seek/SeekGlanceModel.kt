// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Relative direction to the active clearing, derived from course over
 * ground — direction of travel, never compass (iOS `SeekDirectionHint`,
 * `SeekGlance.swift:8-13@c1745e8`). The enum name is the intent-extra
 * wire form; the rendered words live in `strings.xml`.
 */
enum class SeekDirectionHint { AHEAD, LEFT, RIGHT, BEHIND }

/**
 * Coarse notification glance (iOS `SeekGlanceState`,
 * `SeekGlance.swift:15-19@c1745e8`). Structural equality is
 * load-bearing: it is the UI-side publish-on-change key AND the
 * tracker-side notify throttle key.
 */
data class SeekGlanceState(
    val distanceBucketMeters: Int,
    val directionHint: SeekDirectionHint?,
    val isComplete: Boolean,
)

/**
 * Pure glance derivation shared by the orchestrator (UI process) and
 * the notification factory tests. Port of `SeekGlanceModel`
 * (`SeekGlance.swift:35-86@c1745e8`); full contract in
 * `docs/parity/2026-07-14-port-seek-glance-u10.md`.
 */
object SeekGlanceModel {

    const val BUCKET_WIDTH_METERS = 100.0
    const val MAX_BUCKET_METERS = 2000

    /**
     * Below this speed the course is stale noise, not a direction of
     * travel — the hint hides rather than mislead (iOS AE7).
     */
    const val STATIONARY_SPEED_FLOOR_METERS_PER_SECOND = 0.4
    const val AHEAD_CONE_HALF_ANGLE_DEGREES = 45.0
    const val BEHIND_CONE_HALF_ANGLE_DEGREES = 135.0

    fun glance(
        distanceToActiveMeters: Double?,
        courseDegrees: Double?,
        speedMetersPerSecond: Double?,
        bearingToClearingDegrees: Double?,
        phase: SeekEnginePhase,
    ): SeekGlanceState? {
        if (phase == SeekEnginePhase.COMPLETE) {
            return SeekGlanceState(distanceBucketMeters = 0, directionHint = null, isComplete = true)
        }
        val distance = distanceToActiveMeters ?: return null

        // The `>= 0` course gate is iOS's invalid-course sentinel; the
        // Android pipeline already maps invalid courses to null (U3
        // spec D7) — kept as a defensive double-gate.
        val hint = if (
            courseDegrees != null && courseDegrees >= 0 &&
            speedMetersPerSecond != null &&
            speedMetersPerSecond >= STATIONARY_SPEED_FLOOR_METERS_PER_SECOND &&
            bearingToClearingDegrees != null
        ) {
            directionHint(courseDegrees, bearingToClearingDegrees)
        } else {
            null
        }
        return SeekGlanceState(
            distanceBucketMeters = distanceBucket(distance),
            directionHint = hint,
            isComplete = false,
        )
    }

    fun distanceBucket(meters: Double): Int {
        val clamped = max(0.0, meters)
        val bucket = floor(clamped / BUCKET_WIDTH_METERS).toInt() * BUCKET_WIDTH_METERS.toInt()
        return min(bucket, MAX_BUCKET_METERS)
    }

    fun directionHint(courseDegrees: Double, bearingDegrees: Double): SeekDirectionHint {
        val delta = normalizedDelta(course = courseDegrees, bearing = bearingDegrees)
        if (abs(delta) <= AHEAD_CONE_HALF_ANGLE_DEGREES) return SeekDirectionHint.AHEAD
        if (abs(delta) >= BEHIND_CONE_HALF_ANGLE_DEGREES) return SeekDirectionHint.BEHIND
        return if (delta > 0) SeekDirectionHint.RIGHT else SeekDirectionHint.LEFT
    }

    private fun normalizedDelta(course: Double, bearing: Double): Double =
        ((bearing - course + 540.0) % 360.0) - 180.0
}

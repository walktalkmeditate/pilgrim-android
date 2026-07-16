// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import kotlin.math.max
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Stillness detection for the seek reveal ritual, ported from the iOS
 * detector's displacement-only degraded path — the committed Android
 * baseline (no step-delta or motion-activity votes; port spec D3,
 * docs/parity/2026-07-14-port-seek-engine-u3.md). Net displacement is
 * measured across accuracy-gated fixes from an anchor: staying under the
 * threshold with at least two good fixes reads as still; crossing it is
 * a veto that re-anchors at the newest fix, so a walker who stops after
 * moving can settle into a fresh window. Because displacement carries
 * the vote alone, the window runs lengthened by the same multiplier iOS
 * applies when Motion & Fitness is denied.
 */
class SeekStillnessDetector(baseWindowMillis: Long) {

    enum class Update {
        NONE,
        BEGAN,
        COMPLETED,
    }

    val windowDurationMillis: Long =
        (baseWindowMillis * DISPLACEMENT_WINDOW_MULTIPLIER).toLong()

    private var isMonitoring = false
    private var isSuspended = false
    private var hasCompleted = false
    private var stillSinceMillis: Long? = null

    private var anchorFix: SeekPoint? = null
    private var lastGoodFix: SeekPoint? = null
    private var goodFixCount = 0
    private var maxDisplacementMeters = 0.0

    fun start() {
        isMonitoring = true
    }

    fun stop() {
        isMonitoring = false
        stillSinceMillis = null
    }

    /**
     * Suspension freezes detection entirely; the window restarts from zero
     * on resume — a paused walk banks no partial stillness credit.
     */
    fun suspend() {
        if (isSuspended) return
        isSuspended = true
        stillSinceMillis = null
    }

    fun resume() {
        if (!isSuspended) return
        isSuspended = false
        resetSignals()
    }

    fun recordLocation(point: LocationPoint) {
        val accuracy = point.horizontalAccuracyMeters ?: return
        if (accuracy < 0 || accuracy > ACCURACY_GATE_METERS) return
        val fix = SeekPoint(latitude = point.latitude, longitude = point.longitude)
        lastGoodFix = fix
        val anchor = anchorFix
        if (anchor != null) {
            maxDisplacementMeters =
                max(maxDisplacementMeters, SeekChainGenerator.distance(anchor, fix))
        } else {
            anchorFix = fix
        }
        goodFixCount += 1
    }

    fun evaluate(atMillis: Long): Update {
        if (!isMonitoring || isSuspended || hasCompleted) return Update.NONE

        if (!assessStillness()) {
            stillSinceMillis = null
            return Update.NONE
        }
        val since = stillSinceMillis ?: run {
            stillSinceMillis = atMillis
            return Update.BEGAN
        }
        if (atMillis - since < windowDurationMillis) return Update.NONE
        hasCompleted = true
        return Update.COMPLETED
    }

    private fun assessStillness(): Boolean {
        val (still, veto) = consumeDisplacement()
        if (veto) return false
        return still
    }

    private fun consumeDisplacement(): Pair<Boolean, Boolean> {
        if (maxDisplacementMeters >= DISPLACEMENT_THRESHOLD_METERS) {
            anchorFix = lastGoodFix
            goodFixCount = if (lastGoodFix == null) 0 else 1
            maxDisplacementMeters = 0.0
            return false to true
        }
        return (goodFixCount >= 2) to false
    }

    private fun resetSignals() {
        anchorFix = null
        lastGoodFix = null
        goodFixCount = 0
        maxDisplacementMeters = 0.0
    }

    companion object {
        const val DISPLACEMENT_THRESHOLD_METERS = 15.0
        const val ACCURACY_GATE_METERS = 50.0
        const val DISPLACEMENT_WINDOW_MULTIPLIER = 1.5
    }
}

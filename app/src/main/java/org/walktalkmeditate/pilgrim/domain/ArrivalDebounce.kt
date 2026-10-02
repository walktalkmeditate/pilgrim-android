// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * Consecutive-inside-fix arrival gate shared by Seek and Honor (iOS
 * `ArrivalDebounce.swift@7c200bf`). Fixes worse than the accuracy gate
 * neither advance nor reset the count: a momentary multipath fix must not
 * erase honest progress, and must never fake it. A fix with no accuracy
 * (Android only) is treated as worse than the gate.
 */
class ArrivalDebounce(
    val requiredFixes: Int,
    val accuracyMeters: Double,
) {
    var consecutiveInside: Int = 0
        private set

    /** True on the fix that completes the count, and on every inside fix after it until [reset]. */
    fun register(distance: Double, radius: Double, accuracy: Double?): Boolean {
        if (accuracy == null || !(accuracy >= 0 && accuracy <= accuracyMeters)) return false
        consecutiveInside = if (distance <= radius) consecutiveInside + 1 else 0
        return consecutiveInside >= requiredFixes
    }

    fun reset() {
        consecutiveInside = 0
    }

    /** A revived session picks the run up where the killed process left it. */
    fun restore(consecutiveInside: Int) {
        this.consecutiveInside = consecutiveInside.coerceAtLeast(0)
    }
}

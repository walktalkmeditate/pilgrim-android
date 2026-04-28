// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.units

/**
 * Master units toggle. Maps to iOS's per-measurement-type prefs
 * (`distanceMeasurementType`, `altitudeMeasurementType`, etc.) which
 * iOS flips in lockstep via `applyUnitSystem(metric:)`. Android
 * takes a single source of truth: the persisted distance unit drives
 * altitude / speed / temperature display via format-time derivation.
 *
 * Storage values match iOS `MeasurementUserPreference<UnitLength>`
 * symbol strings:
 *   - Metric  → "kilometers"
 *   - Imperial → "miles"
 *
 * iOS's `distanceMeasurementType` UserDefaults key stores the symbol
 * verbatim ("kilometers" / "miles"). The Android key matches.
 */
enum class UnitSystem {
    Metric,
    Imperial;

    /** iOS-faithful storage symbol. */
    fun storageValue(): String = when (this) {
        Metric -> "kilometers"
        Imperial -> "miles"
    }

    companion object {
        val DEFAULT: UnitSystem = Metric

        fun fromStorageValue(stored: String?): UnitSystem = when (stored) {
            "kilometers" -> Metric
            "miles" -> Imperial
            else -> DEFAULT
        }
    }
}

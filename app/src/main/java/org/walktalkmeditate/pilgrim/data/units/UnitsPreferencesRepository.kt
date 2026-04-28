// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.units

import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the user's distance-unit choice. Single source of truth —
 * altitude/speed/temperature units derive from this at format time
 * (avoids 4 unused prefs Android doesn't need but iOS persists).
 *
 * Mirrors iOS's `UserPreferences.distanceMeasurementType` key exactly
 * for `.pilgrim` ZIP cross-platform round-trip.
 */
interface UnitsPreferencesRepository {
    val distanceUnits: StateFlow<UnitSystem>
    suspend fun setDistanceUnits(value: UnitSystem)
}

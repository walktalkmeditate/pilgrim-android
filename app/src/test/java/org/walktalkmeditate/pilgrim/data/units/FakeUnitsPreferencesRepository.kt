// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.units

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [UnitsPreferencesRepository]. Used by
 * format-time consumer tests (UnitFormatter, summary cards, light-
 * reading) to vary the unit toggle without a real DataStore.
 *
 * Default mirrors iOS production default (Metric) so a parameterless
 * instance matches a fresh-install user.
 */
class FakeUnitsPreferencesRepository(
    initial: UnitSystem = UnitSystem.Metric,
) : UnitsPreferencesRepository {

    private val _distanceUnits = MutableStateFlow(initial)
    override val distanceUnits: StateFlow<UnitSystem> = _distanceUnits.asStateFlow()

    override suspend fun setDistanceUnits(value: UnitSystem) {
        _distanceUnits.value = value
    }
}

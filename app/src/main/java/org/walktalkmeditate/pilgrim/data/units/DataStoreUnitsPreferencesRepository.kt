// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.units

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * DataStore-backed [UnitsPreferencesRepository]. Eagerly starts the
 * StateFlow so format-time consumers (UnitFormatter helper, summary
 * cards, light-reading) can read `.value` synchronously without a
 * one-frame default-value flash on cold start.
 *
 * Reuses the shared `pilgrim_prefs` DataStore. The single key —
 * `distanceMeasurementType` — matches iOS UserDefaults verbatim for
 * cross-platform `.pilgrim` ZIP round-trip.
 */
@Singleton
class DataStoreUnitsPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @UnitsPreferencesScope private val scope: CoroutineScope,
) : UnitsPreferencesRepository {

    override val distanceUnits: StateFlow<UnitSystem> = dataStore.data
        .catch { t ->
            Log.w(TAG, "units datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { UnitSystem.fromStorageValue(it[KEY_DISTANCE_UNITS]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, UnitSystem.DEFAULT)

    override suspend fun setDistanceUnits(value: UnitSystem) {
        dataStore.edit { it[KEY_DISTANCE_UNITS] = value.storageValue() }
    }

    private companion object {
        const val TAG = "UnitsPrefs"
        // iOS UserDefaults key — matches verbatim for cross-platform
        // .pilgrim ZIP round-trip. iOS stores the UnitLength symbol
        // ("kilometers" / "miles") as the value, NOT the bool flip.
        val KEY_DISTANCE_UNITS = stringPreferencesKey("distanceMeasurementType")
    }
}

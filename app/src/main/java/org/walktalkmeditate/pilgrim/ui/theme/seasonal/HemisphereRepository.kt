// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.location.LocationSource

/**
 * Lazily resolves + persists the device's hemisphere. Defaults to
 * [Hemisphere.Northern] until (a) a real location is observed and
 * [refreshFromLocationIfNeeded] is called, or (b) the user sets an
 * explicit override via [setOverride].
 *
 * Once cached, subsequent app launches read the value without
 * touching the location subsystem — the DataStore key survives
 * process restart. A user who travels across the equator can call
 * [setOverride] to flip the stored value; automatic re-inference
 * does not clobber an explicit override.
 *
 * Matches the iOS `UserPreferences.hemisphereOverride` + first-walk
 * inference behavior. See the Stage 3-D design spec for context.
 */
@Singleton
class HemisphereRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val locationSource: LocationSource,
    @HemisphereRepositoryScope private val scope: CoroutineScope,
) {
    /**
     * Current best-guess hemisphere. Backed by DataStore; emits
     * [Hemisphere.Northern] as the initial value before the first
     * DataStore read completes and whenever no override has been
     * cached yet.
     *
     * `WhileSubscribed` (not Eagerly) matches [HomeViewModel]'s
     * pattern: unit tests that don't subscribe don't keep a
     * never-completing DataStore reader alive; DataStore failures
     * can recover on the next subscription (an Eagerly collector
     * that dies on a disk error wouldn't restart).
     */
    val hemisphere: StateFlow<Hemisphere> =
        dataStore.data
            .map { prefs ->
                when (prefs[KEY_HEMISPHERE]) {
                    INT_SOUTHERN -> Hemisphere.Southern
                    else -> Hemisphere.Northern
                }
            }
            .distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
                initialValue = Hemisphere.Northern,
            )

    /** Explicit user override. Persists; survives re-inference. */
    suspend fun setOverride(hemisphere: Hemisphere) {
        dataStore.edit { it[KEY_HEMISPHERE] = hemisphere.toInt() }
    }

    /**
     * Try to infer the hemisphere from the last-known location. No-op
     * if a value is already cached OR no location is available. Safe
     * to call concurrently — the read/check/write all happen inside
     * a single [dataStore.edit] transaction so two racing callers
     * can't both clobber an override in flight.
     */
    suspend fun refreshFromLocationIfNeeded() {
        val location = locationSource.lastKnownLocation() ?: return
        val inferred = Hemisphere.fromLatitude(location.latitude)
        dataStore.edit { prefs ->
            // Only write on the unknown → known transition. Preserves
            // a user's explicit setOverride() against a late-arriving
            // auto-inference.
            if (prefs[KEY_HEMISPHERE] == null) {
                prefs[KEY_HEMISPHERE] = inferred.toInt()
            }
        }
    }

    private fun Hemisphere.toInt(): Int = when (this) {
        Hemisphere.Northern -> INT_NORTHERN
        Hemisphere.Southern -> INT_SOUTHERN
    }

    private companion object {
        val KEY_HEMISPHERE = intPreferencesKey("hemisphere")
        const val INT_NORTHERN = 0
        const val INT_SOUTHERN = 1
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists first-run onboarding progress. Runtime permission grant
 * state itself is not cached — callers check live via
 * `ContextCompat.checkSelfPermission` at the moment of need, because
 * the user can revoke from system Settings at any time and cached
 * state would go stale. This repo only tracks "did the user finish
 * our onboarding flow once" and "have we asked about battery
 * exemption yet".
 */
@Singleton
class PermissionsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val onboardingComplete: Flow<Boolean> =
        dataStore.data.map { it[KEY_ONBOARDING_COMPLETE] ?: false }

    val batteryExemptionAsked: Flow<Boolean> =
        dataStore.data.map { it[KEY_BATTERY_EXEMPTION_ASKED] ?: false }

    suspend fun markOnboardingComplete() {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETE] = true }
    }

    suspend fun markBatteryExemptionAsked() {
        dataStore.edit { it[KEY_BATTERY_EXEMPTION_ASKED] = true }
    }

    suspend fun resetForTesting() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_BATTERY_EXEMPTION_ASKED = booleanPreferencesKey("battery_exemption_asked")
    }
}

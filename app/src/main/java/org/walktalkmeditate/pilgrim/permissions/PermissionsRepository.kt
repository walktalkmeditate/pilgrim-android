// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
) : PermissionRitualStore {
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

    // #43 grant ritual — one persisted flag per permission. Read-and-mark
    // happens inside a single `edit` transform so two rapid grant events
    // can't both read "not yet played" and both fire the bell; `edit`
    // completes the transform before returning, so reading `fired` after it
    // observes the write. A DataStore IO failure (full/unavailable storage)
    // fails closed on the bell rather than crashing onboarding — the caller
    // runs us in a viewModelScope.launch with no exception handler.
    override suspend fun consumeBellGrant(
        permission: PermissionRitual.Permission,
        soundsEnabled: Boolean,
    ): Boolean {
        var fired = false
        try {
            dataStore.edit { prefs ->
                val key = bellPlayedKey(permission)
                val alreadyPlayed = prefs[key] ?: false
                if (PermissionRitual.shouldPlayBell(
                        granted = true,
                        soundsEnabled = soundsEnabled,
                        alreadyPlayed = alreadyPlayed,
                    )
                ) {
                    prefs[key] = true
                    fired = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "consumeBellGrant write failed; skipping bell", t)
            return false
        }
        return fired
    }

    override suspend fun hasPlayedBell(permission: PermissionRitual.Permission): Boolean =
        dataStore.data.first()[bellPlayedKey(permission)] ?: false

    private fun bellPlayedKey(permission: PermissionRitual.Permission) =
        booleanPreferencesKey("permission_bell_played.${permission.key}")

    private companion object {
        const val TAG = "PermissionsRepository"
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_BATTERY_EXEMPTION_ASKED = booleanPreferencesKey("battery_exemption_asked")
    }
}

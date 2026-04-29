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
 * Persists a boolean per permission: "have we ever asked the system
 * dialog for this one?" Used by [PermissionsCardViewModel] to
 * disambiguate Android's two-state `checkSelfPermission` (granted /
 * not granted) into iOS's three pre-restricted states (Granted /
 * NotDetermined / Denied).
 *
 * Set to true after a successful [androidx.activity.result.ActivityResultLauncher]
 * callback regardless of grant outcome — once the system dialog has
 * dismissed, "not determined" no longer applies.
 */
@Singleton
class PermissionAskedStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    enum class Key(internal val storeKey: String) {
        Location("permissions_asked_location"),
        Microphone("permissions_asked_microphone"),
        Motion("permissions_asked_motion"),
    }

    fun askedFlow(key: Key): Flow<Boolean> =
        dataStore.data.map { it[booleanPreferencesKey(key.storeKey)] ?: false }

    suspend fun markAsked(key: Key) {
        dataStore.edit { it[booleanPreferencesKey(key.storeKey)] = true }
    }
}

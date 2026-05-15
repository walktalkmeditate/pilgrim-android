// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.onboarding

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
class DataStoreOnboardingPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @OnboardingPreferencesScope private val scope: CoroutineScope,
) : OnboardingPreferencesRepository {

    override val welcomeCompleted: StateFlow<Boolean> = dataStore.data
        .catch { t ->
            Log.w(TAG, "onboarding datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { it[KEY_WELCOME_COMPLETED] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun markWelcomeCompleted() {
        dataStore.edit { it[KEY_WELCOME_COMPLETED] = true }
    }

    private companion object {
        const val TAG = "OnboardingPrefs"
        val KEY_WELCOME_COMPLETED = booleanPreferencesKey("welcome_completed")
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OnboardingPreferencesScope

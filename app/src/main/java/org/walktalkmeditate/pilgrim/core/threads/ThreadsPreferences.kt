// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.di.ThreadsPreferencesDataStore

/**
 * Thought Threads preferences (R12 toggle sovereignty + import/backfill
 * hygiene). All threads-feature units gate on [threadsAfterWalks] before
 * doing any analysis, backfill, or dossier work.
 *
 * Eagerly-started StateFlows (matching [org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository])
 * so the transcription runner and analyzer can read `.value` synchronously
 * from background contexts with no UI subscriber.
 */
interface ThreadsPreferencesRepository {
    val threadsAfterWalks: StateFlow<Boolean>

    /**
     * Bumped once per successful `.pilgrim` import (U5). U6's backfill
     * sweep compares this against its own last-seen value to decide
     * whether imported recordings need a fresh pass — the counter is the
     * whole contract; this unit does not consume it.
     */
    val importGeneration: StateFlow<Int>

    suspend fun setThreadsAfterWalks(enabled: Boolean)

    suspend fun bumpImportGeneration()

    /**
     * Clears the moon-line-last-reported key (U9 owns reading/writing it
     * during normal operation). Part of the internal full-wipe API's
     * three-step order — see [ThreadsFullWipe].
     */
    suspend fun clearMoonLineIndex()
}

@Singleton
class DataStoreThreadsPreferencesRepository @Inject constructor(
    @ThreadsPreferencesDataStore private val dataStore: DataStore<Preferences>,
    @ThreadsPreferencesScope private val scope: CoroutineScope,
) : ThreadsPreferencesRepository {

    override val threadsAfterWalks: StateFlow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[THREADS_AFTER_WALKS] ?: DEFAULT_THREADS_AFTER_WALKS }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_THREADS_AFTER_WALKS)

    override val importGeneration: StateFlow<Int> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[IMPORT_GENERATION] ?: 0 }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    override suspend fun setThreadsAfterWalks(enabled: Boolean) {
        dataStore.edit { it[THREADS_AFTER_WALKS] = enabled }
    }

    override suspend fun bumpImportGeneration() {
        dataStore.edit { prefs -> prefs[IMPORT_GENERATION] = (prefs[IMPORT_GENERATION] ?: 0) + 1 }
    }

    override suspend fun clearMoonLineIndex() {
        dataStore.edit { it.remove(MOON_LINE_LAST_LUNATION_INDEX) }
    }

    private companion object {
        // Verbatim iOS UserDefaults key name (UserPreferences.swift) —
        // naming discipline only; DataStore and UserDefaults never
        // interop directly.
        val THREADS_AFTER_WALKS = booleanPreferencesKey("threadsAfterWalks")

        // Android-original: iOS's ThreadsBackfill.generation is an
        // in-memory Int (meaningless across process death, Open question
        // 12). Android persists its own import-triggered epoch instead.
        val IMPORT_GENERATION = intPreferencesKey("threadsImportGeneration")

        // Verbatim iOS UserDefaults key name (ThreadsDossierBuilder.swift,
        // DAT-52) — reserved here (U5) so the full-wipe hygiene sweep has
        // a name to clear; U9 owns the read/write touch points.
        val MOON_LINE_LAST_LUNATION_INDEX = intPreferencesKey("threadsMoonLineLastLunationIndex")

        const val DEFAULT_THREADS_AFTER_WALKS = true
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ThreadsPreferencesScope

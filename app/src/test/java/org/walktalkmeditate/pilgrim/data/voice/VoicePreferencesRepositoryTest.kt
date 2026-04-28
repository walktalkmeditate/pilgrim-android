// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoicePreferencesRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "voice-prefs-test-${UUID.randomUUID()}"
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        storeFile = File(context.filesDir, "datastore/$name.preferences_pb")
        storeFile.parentFile?.mkdirs()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
    }

    @After
    fun tearDown() {
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun `defaults — voiceGuideEnabled false, autoTranscribe false on fresh install`() = runTest {
        val repo = DataStoreVoicePreferencesRepository(dataStore, scope)
        // First read: no pre-existing keys → fresh-install branch.
        assertFalse(repo.voiceGuideEnabled.value)
        assertFalse(repo.autoTranscribe.value)
    }

    @Test
    fun `setVoiceGuideEnabled persists across instances`() = runTest {
        val repo1 = DataStoreVoicePreferencesRepository(dataStore, scope)
        repo1.setVoiceGuideEnabled(true)
        // Allow Eagerly StateFlow to observe the write.
        runBlocking { kotlinx.coroutines.delay(50) }
        val repo2 = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(50) }
        assertTrue(repo2.voiceGuideEnabled.value)
    }

    @Test
    fun `setAutoTranscribe persists across instances`() = runTest {
        val repo1 = DataStoreVoicePreferencesRepository(dataStore, scope)
        repo1.setAutoTranscribe(true)
        runBlocking { kotlinx.coroutines.delay(50) }
        val repo2 = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(50) }
        assertTrue(repo2.autoTranscribe.value)
    }

    @Test
    fun `autoTranscribe migration — fresh install seeds false`() = runTest {
        val repo = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(50) }
        assertFalse(repo.autoTranscribe.value)
        // Persisted seed: re-read confirms.
        val repo2 = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(50) }
        assertFalse(repo2.autoTranscribe.value)
    }

    @Test
    fun `autoTranscribe migration — upgrade with appearance_mode set seeds true`() = runTest {
        // Pre-seed: simulate a Stage 9.5-E user who has appearance_mode written.
        dataStore.edit { it[stringPreferencesKey("appearance_mode")] = "system" }
        runBlocking { kotlinx.coroutines.delay(50) }
        val repo = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(100) }
        assertTrue(repo.autoTranscribe.value)
    }

    @Test
    fun `awaitAutoTranscribe returns disk-loaded value, bypasses Eagerly seed`() = runTest {
        // Pre-seed disk with autoTranscribe=true, then construct a fresh
        // repo (simulates process-restart). awaitAutoTranscribe should
        // return the disk value even before the StateFlow has had time
        // to load — that's the whole point of the suspend variant.
        dataStore.edit { it[booleanPreferencesKey("autoTranscribe")] = true }
        val repo = DataStoreVoicePreferencesRepository(dataStore, scope)
        // Don't wait for StateFlow — call awaitAutoTranscribe immediately.
        assertTrue(repo.awaitAutoTranscribe())
    }

    @Test
    fun `autoTranscribe migration — explicit user write preserved`() = runTest {
        // Pre-seed: previous user explicitly set autoTranscribe=false (overriding any seed).
        dataStore.edit { it[booleanPreferencesKey("autoTranscribe")] = false }
        // Also pre-seed an upgrade-indicator key — without the explicit write,
        // the migration would try to seed true. The explicit write must win.
        dataStore.edit { it[stringPreferencesKey("appearance_mode")] = "system" }
        runBlocking { kotlinx.coroutines.delay(50) }
        val repo = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(100) }
        assertFalse(repo.autoTranscribe.value)
    }
}

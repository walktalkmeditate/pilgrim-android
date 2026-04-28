// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideSelectionRepositoryMigrationTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        storeFile = File(context.filesDir, "datastore/vgsr-test-${UUID.randomUUID()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
    }

    @After
    fun tearDown() {
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun `legacy snake_case key value is read on first construction`() = runTest {
        dataStore.edit { it[stringPreferencesKey("selected_voice_guide_pack_id")] = "pack-a" }
        runBlocking { delay(50) }
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        runBlocking { delay(100) }
        assertEquals("pack-a", repo.selectedPackId.value)
    }

    @Test
    fun `legacy key copied into new key after migration`() = runTest {
        dataStore.edit { it[stringPreferencesKey("selected_voice_guide_pack_id")] = "pack-b" }
        runBlocking { delay(50) }
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        runBlocking { delay(100) }
        val prefs = dataStore.data.first()
        assertEquals("pack-b", prefs[stringPreferencesKey("selectedVoiceGuidePackId")])
    }

    @Test
    fun `new key takes precedence when both are present`() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("selected_voice_guide_pack_id")] = "old"
            prefs[stringPreferencesKey("selectedVoiceGuidePackId")] = "new"
        }
        runBlocking { delay(50) }
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        runBlocking { delay(100) }
        assertEquals("new", repo.selectedPackId.value)
    }

    @Test
    fun `select writes only to new camelCase key`() = runTest {
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        repo.select("pack-c")
        runBlocking { delay(50) }
        val prefs = dataStore.data.first()
        assertEquals("pack-c", prefs[stringPreferencesKey("selectedVoiceGuidePackId")])
    }

    @Test
    fun `fresh install — both keys absent yields null`() = runTest {
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        runBlocking { delay(100) }
        assertNull(repo.selectedPackId.value)
    }
}

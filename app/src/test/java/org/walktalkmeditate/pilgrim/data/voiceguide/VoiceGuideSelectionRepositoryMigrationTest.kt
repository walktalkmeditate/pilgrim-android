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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    /**
     * Wall-clock poll for an async repo state change. The fixed `delay(100)`
     * pattern this replaced flaked on slow CI runners (the migration's
     * `dataStore.edit` coroutine occasionally takes >100ms there). Polling
     * with a generous deadline is deterministic across machines.
     */
    private suspend fun awaitSelectedPackId(
        repo: VoiceGuideSelectionRepository,
        expected: String?,
    ) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (repo.selectedPackId.value != expected) {
                    delay(25)
                }
            }
        }
    }

    private suspend fun awaitDataStoreKey(key: String, expected: String?) {
        val prefKey = stringPreferencesKey(key)
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (dataStore.data.first()[prefKey] != expected) {
                    delay(25)
                }
            }
        }
    }

    @Test
    fun `legacy snake_case key value is read on first construction`() = runTest {
        dataStore.edit { it[stringPreferencesKey("selected_voice_guide_pack_id")] = "pack-a" }
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        awaitSelectedPackId(repo, "pack-a")
        assertEquals("pack-a", repo.selectedPackId.value)
    }

    @Test
    fun `legacy key copied into new key after migration`() = runTest {
        dataStore.edit { it[stringPreferencesKey("selected_voice_guide_pack_id")] = "pack-b" }
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        awaitDataStoreKey("selectedVoiceGuidePackId", "pack-b")
        val prefs = dataStore.data.first()
        assertEquals("pack-b", prefs[stringPreferencesKey("selectedVoiceGuidePackId")])
    }

    @Test
    fun `new key takes precedence when both are present`() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("selected_voice_guide_pack_id")] = "old"
            prefs[stringPreferencesKey("selectedVoiceGuidePackId")] = "new"
        }
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        awaitSelectedPackId(repo, "new")
        assertEquals("new", repo.selectedPackId.value)
    }

    @Test
    fun `select writes only to new camelCase key`() = runTest {
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        repo.select("pack-c")
        awaitDataStoreKey("selectedVoiceGuidePackId", "pack-c")
        val prefs = dataStore.data.first()
        assertEquals("pack-c", prefs[stringPreferencesKey("selectedVoiceGuidePackId")])
    }

    @Test
    fun `fresh install — both keys absent yields null`() = runTest {
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        // Give the Eagerly StateFlow a moment to emit; on fresh install
        // the value should remain null. Use a brief real-time wait so
        // the negative assertion is meaningful.
        runBlocking { delay(200) }
        assertNull(repo.selectedPackId.value)
    }
}

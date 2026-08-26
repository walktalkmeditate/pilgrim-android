// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
class ThreadsPreferencesTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "threads-prefs-test-${UUID.randomUUID()}"
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
    fun `threadsAfterWalks defaults to true on a fresh install`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertTrue(repo.threadsAfterWalks.value)
    }

    @Test
    fun `importGeneration defaults to zero on a fresh install`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertEquals(0, repo.importGeneration.value)
    }

    @Test
    fun `setThreadsAfterWalks persists across instances`() = runTest {
        val repo1 = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo1.setThreadsAfterWalks(false)
        runBlocking { kotlinx.coroutines.delay(50) }
        val repo2 = DataStoreThreadsPreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(50) }
        assertFalse(repo2.threadsAfterWalks.value)
    }

    @Test
    fun `bumpImportGeneration increments by exactly one per call`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.bumpImportGeneration()
        runBlocking { kotlinx.coroutines.delay(50) }
        assertEquals(1, repo.importGeneration.value)
        repo.bumpImportGeneration()
        runBlocking { kotlinx.coroutines.delay(50) }
        assertEquals(2, repo.importGeneration.value)
    }

    @Test
    fun `clearMoonLineIndex removes the key without touching other prefs`() = runTest {
        val moonKey = intPreferencesKey("threadsMoonLineLastLunationIndex")
        dataStore.edit { it[moonKey] = 7 }
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setThreadsAfterWalks(false)

        repo.clearMoonLineIndex()
        runBlocking { kotlinx.coroutines.delay(50) }

        assertEquals(null, dataStore.data.first()[moonKey])
        assertFalse("unrelated prefs must survive the clear", repo.threadsAfterWalks.value)
    }

    // ---- U6: backfill completion + checkpoint keys ----

    @Test
    fun `backfillCompletedAtVersion is null on a fresh install`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo.backfillCompletedAtVersion())
    }

    @Test
    fun `backfillCompletedAtImportGeneration defaults to zero on a fresh install`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertEquals(0, repo.backfillCompletedAtImportGeneration())
    }

    @Test
    fun `setBackfillCompleted persists both the version and the import generation`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setBackfillCompleted(version = 3, atImportGeneration = 5)

        assertEquals(3, repo.backfillCompletedAtVersion())
        assertEquals(5, repo.backfillCompletedAtImportGeneration())
    }

    @Test
    fun `clearBackfillCompleted resets the version to null without touching unrelated prefs`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setBackfillCompleted(version = 3, atImportGeneration = 5)
        repo.setThreadsAfterWalks(false)

        repo.clearBackfillCompleted()

        assertEquals(null, repo.backfillCompletedAtVersion())
        assertFalse("unrelated prefs must survive the clear", repo.threadsAfterWalks.value)
    }

    @Test
    fun `backfillCheckpoint is empty on a fresh install`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertEquals(BackfillCheckpoint.EMPTY, repo.backfillCheckpoint())
    }

    @Test
    fun `setBackfillCheckpoint persists processedCount and forImportGeneration`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setBackfillCheckpoint(BackfillCheckpoint(processedCount = 50, forImportGeneration = 2))

        assertEquals(BackfillCheckpoint(50, 2), repo.backfillCheckpoint())
    }

    @Test
    fun `clearBackfillCheckpoint resets to EMPTY`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setBackfillCheckpoint(BackfillCheckpoint(processedCount = 50, forImportGeneration = 2))

        repo.clearBackfillCheckpoint()

        assertEquals(BackfillCheckpoint.EMPTY, repo.backfillCheckpoint())
    }
}

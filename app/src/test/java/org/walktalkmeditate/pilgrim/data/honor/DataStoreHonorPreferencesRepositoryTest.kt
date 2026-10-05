// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/**
 * iOS `UserPreferences.honorVoicesEnabled` (default on), as the walk screen's
 * Start reads it, and `pilgrimageOfflineNoteShown` (default off), as a stage's
 * overview reads it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataStoreHonorPreferencesRepositoryTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "honor-test-${System.nanoTime()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `with nothing stored the Start reads voices on`() = runTest(dispatcher) {
        assertTrue(DataStoreHonorPreferencesRepository(dataStore, scope).awaitVoicesEnabled())
    }

    // Correctness F4: a Start right after a process restore must not read
    // the toggle flow's placeholder (on) over a stored off.
    @Test
    fun `the Start reads the stored value before the toggle's flow has loaded it`() = runTest(dispatcher) {
        dataStore.edit { it[booleanPreferencesKey("honorVoicesEnabled")] = false }
        val notYetCollected = CoroutineScope(StandardTestDispatcher())
        val repo = DataStoreHonorPreferencesRepository(dataStore, notYetCollected)

        try {
            assertTrue("the toggle's flow still holds its placeholder", repo.voicesEnabled.value)
            assertFalse(repo.awaitVoicesEnabled())
        } finally {
            notYetCollected.cancel()
        }
    }

    // iOS `UserPreferences.pilgrimageOfflineNoteShown` (pilgrimage-stage spec P4 §6.4), its key verbatim.
    @Test
    fun `the offline note reads unsaid with nothing stored, and said once written under iOS's key`() =
        runTest(dispatcher) {
            val repo = DataStoreHonorPreferencesRepository(dataStore, scope)
            assertFalse(repo.awaitPilgrimageOfflineNoteShown())

            repo.setPilgrimageOfflineNoteShown()

            assertTrue(repo.awaitPilgrimageOfflineNoteShown())
            assertEquals(true, dataStore.data.first()[booleanPreferencesKey("pilgrimageOfflineNoteShown")])
        }

    // A process restored onto a stage overview must not say the note again.
    @Test
    fun `a note said in an earlier process reads said on a fresh repository`() = runTest(dispatcher) {
        dataStore.edit { it[booleanPreferencesKey("pilgrimageOfflineNoteShown")] = true }
        val notYetCollected = CoroutineScope(StandardTestDispatcher())

        try {
            assertTrue(DataStoreHonorPreferencesRepository(dataStore, notYetCollected).awaitPilgrimageOfflineNoteShown())
        } finally {
            notYetCollected.cancel()
        }
    }
}

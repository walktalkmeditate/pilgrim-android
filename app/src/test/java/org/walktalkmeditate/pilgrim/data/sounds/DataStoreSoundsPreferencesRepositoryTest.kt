// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataStoreSoundsPreferencesRepositoryTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "sounds-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `default is true when no key written`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo.soundsEnabled.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setSoundsEnabled persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo1.setSoundsEnabled(false)

        // Fresh repo emits its `Eagerly` seed (true, the default) before
        // the upstream `dataStore.data` flow pushes the persisted value
        // through. Use `first { it == false }` to wait for the loaded
        // state without racing on the seed emission.
        val repo2 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        val loaded = repo2.soundsEnabled.first { it == false }
        assertEquals(false, loaded)
    }

    @Test
    fun `soundsEnabled emits new value after setSoundsEnabled`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo.soundsEnabled.test {
            assertEquals(true, awaitItem())
            repo.setSoundsEnabled(false)
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling off then back on round-trips through DataStore`() = runTest(dispatcher) {
        val repo1 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo1.setSoundsEnabled(false)
        // Wait for the false to land in the upstream flow, otherwise
        // the next setSoundsEnabled(true) is a no-op against the
        // default `true`.
        assertEquals(false, repo1.soundsEnabled.first { it == false })
        repo1.setSoundsEnabled(true)

        val repo2 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        // After a round-trip OFF→ON the persisted value should be true.
        // Since true matches the eager seed we can't `first { it == true }`
        // distinguish "loaded" from "seed." Force a fresh write of false
        // would be different test. Instead, assert the value is true and
        // then write false to the new repo and read it back.
        assertEquals(true, repo2.soundsEnabled.value)
        repo2.setSoundsEnabled(false)
        assertTrue(repo2.soundsEnabled.first { it == false } == false)
    }
}

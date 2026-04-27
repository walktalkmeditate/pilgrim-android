// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataStoreAppearancePreferencesRepositoryTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "appearance-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `default is System when no key written`() = runTest(dispatcher) {
        val repo = DataStoreAppearancePreferencesRepository(dataStore, scope)
        repo.appearanceMode.test {
            assertEquals(AppearanceMode.System, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setAppearanceMode persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStoreAppearancePreferencesRepository(dataStore, scope)
        repo1.setAppearanceMode(AppearanceMode.Dark)

        // A fresh repository constructed against the same DataStore
        // emits its `Eagerly` seed (System, the default) before the
        // upstream `dataStore.data` flow has a chance to push the
        // persisted value through `.map / .distinctUntilChanged`. Use
        // `first { it == Dark }` to wait for the loaded state without
        // racing on the seed emission.
        val repo2 = DataStoreAppearancePreferencesRepository(dataStore, scope)
        val loaded = repo2.appearanceMode.first { it == AppearanceMode.Dark }
        assertEquals(AppearanceMode.Dark, loaded)
    }

    @Test
    fun `unknown stored value falls back to System`() = runTest(dispatcher) {
        // Simulate a forward-compat scenario: a future build wrote
        // "sepia" but this version only knows system/light/dark.
        dataStore.edit { it[stringPreferencesKey("appearance_mode")] = "sepia" }

        val repo = DataStoreAppearancePreferencesRepository(dataStore, scope)
        repo.appearanceMode.test {
            assertEquals(AppearanceMode.System, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `appearanceMode emits new value after setAppearanceMode`() = runTest(dispatcher) {
        val repo = DataStoreAppearancePreferencesRepository(dataStore, scope)
        repo.appearanceMode.test {
            assertEquals(AppearanceMode.System, awaitItem())
            repo.setAppearanceMode(AppearanceMode.Light)
            assertEquals(AppearanceMode.Light, awaitItem())
            repo.setAppearanceMode(AppearanceMode.Dark)
            assertEquals(AppearanceMode.Dark, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

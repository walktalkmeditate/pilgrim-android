// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.practice

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
class DataStorePracticePreferencesRepositoryTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "practice-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    // ─── Defaults (one per pref) ──────────────────────────────────────

    @Test
    fun `beginWithIntention default is false`() = runTest(dispatcher) {
        val repo = DataStorePracticePreferencesRepository(dataStore, scope)
        assertEquals(false, repo.beginWithIntention.first())
    }

    @Test
    fun `celestialAwarenessEnabled default is false`() = runTest(dispatcher) {
        val repo = DataStorePracticePreferencesRepository(dataStore, scope)
        assertEquals(false, repo.celestialAwarenessEnabled.first())
    }

    @Test
    fun `zodiacSystem default is Tropical`() = runTest(dispatcher) {
        val repo = DataStorePracticePreferencesRepository(dataStore, scope)
        assertEquals(ZodiacSystem.Tropical, repo.zodiacSystem.first())
    }

    @Test
    fun `walkReliquaryEnabled default is false`() = runTest(dispatcher) {
        val repo = DataStorePracticePreferencesRepository(dataStore, scope)
        assertEquals(false, repo.walkReliquaryEnabled.first())
    }

    // ─── Round-trip persistence ───────────────────────────────────────

    @Test
    fun `beginWithIntention persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStorePracticePreferencesRepository(dataStore, scope)
        repo1.setBeginWithIntention(true)

        // Fresh repo emits its `Eagerly` seed (false, default) before
        // upstream `dataStore.data` pushes the persisted value through.
        // Use `first { it == true }` to wait for the loaded state.
        val repo2 = DataStorePracticePreferencesRepository(dataStore, scope)
        assertEquals(true, repo2.beginWithIntention.first { it == true })
    }

    @Test
    fun `celestialAwarenessEnabled persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStorePracticePreferencesRepository(dataStore, scope)
        repo1.setCelestialAwarenessEnabled(true)

        val repo2 = DataStorePracticePreferencesRepository(dataStore, scope)
        assertEquals(true, repo2.celestialAwarenessEnabled.first { it == true })
    }

    @Test
    fun `zodiacSystem persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStorePracticePreferencesRepository(dataStore, scope)
        repo1.setZodiacSystem(ZodiacSystem.Sidereal)

        val repo2 = DataStorePracticePreferencesRepository(dataStore, scope)
        assertEquals(
            ZodiacSystem.Sidereal,
            repo2.zodiacSystem.first { it == ZodiacSystem.Sidereal },
        )
    }

    @Test
    fun `walkReliquaryEnabled persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStorePracticePreferencesRepository(dataStore, scope)
        repo1.setWalkReliquaryEnabled(true)

        val repo2 = DataStorePracticePreferencesRepository(dataStore, scope)
        assertEquals(true, repo2.walkReliquaryEnabled.first { it == true })
    }

    // ─── Live emission after set ──────────────────────────────────────

    @Test
    fun `beginWithIntention emits new value after setBeginWithIntention`() = runTest(dispatcher) {
        val repo = DataStorePracticePreferencesRepository(dataStore, scope)
        repo.beginWithIntention.test {
            assertEquals(false, awaitItem())
            repo.setBeginWithIntention(true)
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `zodiacSystem emits new value after setZodiacSystem`() = runTest(dispatcher) {
        val repo = DataStorePracticePreferencesRepository(dataStore, scope)
        repo.zodiacSystem.test {
            assertEquals(ZodiacSystem.Tropical, awaitItem())
            repo.setZodiacSystem(ZodiacSystem.Sidereal)
            assertEquals(ZodiacSystem.Sidereal, awaitItem())
            repo.setZodiacSystem(ZodiacSystem.Tropical)
            assertEquals(ZodiacSystem.Tropical, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Forward-compat: unknown stored zodiac value falls back ───────

    @Test
    fun `unknown stored zodiacSystem value falls back to default`() = runTest(dispatcher) {
        // Simulate forward-compat: a future build wrote "vedic" but
        // this version only knows tropical/sidereal.
        dataStore.edit { it[stringPreferencesKey("zodiacSystem")] = "vedic" }

        val repo = DataStorePracticePreferencesRepository(dataStore, scope)
        repo.zodiacSystem.test {
            assertEquals(ZodiacSystem.Tropical, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

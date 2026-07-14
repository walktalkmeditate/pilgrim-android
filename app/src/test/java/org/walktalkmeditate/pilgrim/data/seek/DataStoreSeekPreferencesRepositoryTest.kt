// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.seek

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlin.time.Duration.Companion.seconds
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
class DataStoreSeekPreferencesRepositoryTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "seek-test-${System.nanoTime()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    // ─── Defaults (iOS `UserPreferences.swift:72-75@c1745e8`) ─────────

    @Test
    fun `sonarEnabled default is true`() = runTest(dispatcher) {
        val repo = DataStoreSeekPreferencesRepository(dataStore, scope)
        assertEquals(true, repo.sonarEnabled.first())
    }

    @Test
    fun `sonarVolume default is half`() = runTest(dispatcher) {
        val repo = DataStoreSeekPreferencesRepository(dataStore, scope)
        assertEquals(0.5f, repo.sonarVolume.first(), 0.001f)
    }

    @Test
    fun `lastDurationMinutes default is sixty`() = runTest(dispatcher) {
        val repo = DataStoreSeekPreferencesRepository(dataStore, scope)
        assertEquals(60, repo.lastDurationMinutes.first())
    }

    @Test
    fun `safetyShown default is false`() = runTest(dispatcher) {
        val repo = DataStoreSeekPreferencesRepository(dataStore, scope)
        assertEquals(false, repo.safetyShown.first())
    }

    // ─── Round-trip persistence ───────────────────────────────────────

    @Test
    fun `sonarEnabled persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStoreSeekPreferencesRepository(dataStore, scope)
        repo1.setSonarEnabled(false)

        // Fresh repo emits its `Eagerly` seed (default) before upstream
        // `dataStore.data` pushes the persisted value through — wait
        // for the loaded state (practice-family test convention).
        val repo2 = DataStoreSeekPreferencesRepository(dataStore, scope)
        assertEquals(false, repo2.sonarEnabled.first { !it })
    }

    @Test
    fun `sonarVolume persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStoreSeekPreferencesRepository(dataStore, scope)
        repo1.setSonarVolume(0.8f)

        val repo2 = DataStoreSeekPreferencesRepository(dataStore, scope)
        assertEquals(0.8f, repo2.sonarVolume.first { it == 0.8f }, 0.001f)
    }

    @Test
    fun `lastDurationMinutes persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStoreSeekPreferencesRepository(dataStore, scope)
        repo1.setLastDurationMinutes(120)

        val repo2 = DataStoreSeekPreferencesRepository(dataStore, scope)
        assertEquals(120, repo2.lastDurationMinutes.first { it == 120 })
    }

    @Test
    fun `safetyShown persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStoreSeekPreferencesRepository(dataStore, scope)
        repo1.setSafetyShown(true)

        val repo2 = DataStoreSeekPreferencesRepository(dataStore, scope)
        assertEquals(true, repo2.safetyShown.first { it })
    }

    // ─── Live emission after set ──────────────────────────────────────

    @Test
    fun `sonarEnabled emits new value after setSonarEnabled`() = runTest(dispatcher) {
        val repo = DataStoreSeekPreferencesRepository(dataStore, scope)
        repo.sonarEnabled.test(timeout = 10.seconds) {
            assertEquals(true, awaitItem())
            repo.setSonarEnabled(false)
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sonarVolume emits new value after setSonarVolume`() = runTest(dispatcher) {
        val repo = DataStoreSeekPreferencesRepository(dataStore, scope)
        repo.sonarVolume.test(timeout = 10.seconds) {
            assertEquals(0.5f, awaitItem(), 0.001f)
            repo.setSonarVolume(0.9f)
            assertEquals(0.9f, awaitItem(), 0.001f)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Volume clamp [0, 1] ──────────────────────────────────────────

    @Test
    fun `setSonarVolume clamps above one to one`() = runTest(dispatcher) {
        val repo = DataStoreSeekPreferencesRepository(dataStore, scope)
        repo.setSonarVolume(1.5f)
        assertEquals(1f, repo.sonarVolume.first { it == 1f }, 0.001f)
    }

    @Test
    fun `setSonarVolume clamps below zero to zero`() = runTest(dispatcher) {
        val repo = DataStoreSeekPreferencesRepository(dataStore, scope)
        repo.setSonarVolume(-0.3f)
        assertEquals(0f, repo.sonarVolume.first { it == 0f }, 0.001f)
    }

    @Test
    fun `setSonarVolume maps NaN to the default`() = runTest(dispatcher) {
        val repo = DataStoreSeekPreferencesRepository(dataStore, scope)
        repo.setSonarVolume(0.9f)
        assertEquals(0.9f, repo.sonarVolume.first { it == 0.9f }, 0.001f)

        // `Float.NaN.coerceIn(0f, 1f) == NaN` (BellPlayer precedent) —
        // the repository must not persist NaN.
        repo.setSonarVolume(Float.NaN)
        assertEquals(0.5f, repo.sonarVolume.first { it == 0.5f }, 0.001f)
    }

    @Test
    fun `out-of-range persisted volume is clamped at read`() = runTest(dispatcher) {
        // Simulate a value persisted by an old binary (or future bug)
        // outside [0, 1]: the read side sanitizes so a player's
        // `.value` read never sees it.
        dataStore.edit { it[floatPreferencesKey("seekSonarVolume")] = 3f }

        val repo = DataStoreSeekPreferencesRepository(dataStore, scope)
        assertEquals(1f, repo.sonarVolume.first { it == 1f }, 0.001f)
    }
}

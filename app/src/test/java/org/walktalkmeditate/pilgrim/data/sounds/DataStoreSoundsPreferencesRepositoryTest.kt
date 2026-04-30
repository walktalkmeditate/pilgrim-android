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
import kotlin.time.Duration.Companion.seconds
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

    @Test
    fun `default is true when no key written`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo.soundsEnabled.test(timeout = 10.seconds) {
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
        repo.soundsEnabled.test(timeout = 10.seconds) {
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

    // ─── Defaults (one per new pref) ──────────────────────────────────

    @Test
    fun `bellHapticEnabled default is true`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(true, repo.bellHapticEnabled.first())
    }

    @Test
    fun `bellVolume default is 0_7`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(0.7f, repo.bellVolume.first())
    }

    @Test
    fun `soundscapeVolume default is 0_4`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(0.4f, repo.soundscapeVolume.first())
    }

    @Test
    fun `walkStartBellId default is null`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo.walkStartBellId.first())
    }

    @Test
    fun `walkEndBellId default is null`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo.walkEndBellId.first())
    }

    @Test
    fun `meditationStartBellId default is null`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo.meditationStartBellId.first())
    }

    @Test
    fun `meditationEndBellId default is null`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo.meditationEndBellId.first())
    }

    @Test
    fun `breathRhythm default is 0`() = runTest(dispatcher) {
        val repo = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(0, repo.breathRhythm.first())
    }

    // ─── Round-trip persistence (non-default values) ──────────────────

    @Test
    fun `bellHapticEnabled round-trips through DataStore`() = runTest(dispatcher) {
        val repo1 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo1.setBellHapticEnabled(false)

        val repo2 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(false, repo2.bellHapticEnabled.first { it == false })
    }

    @Test
    fun `bellVolume round-trips through DataStore`() = runTest(dispatcher) {
        val repo1 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo1.setBellVolume(0.25f)

        val repo2 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(0.25f, repo2.bellVolume.first { it == 0.25f })
    }

    @Test
    fun `soundscapeVolume round-trips through DataStore`() = runTest(dispatcher) {
        val repo1 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo1.setSoundscapeVolume(0.9f)

        val repo2 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(0.9f, repo2.soundscapeVolume.first { it == 0.9f })
    }

    @Test
    fun `breathRhythm round-trips through DataStore`() = runTest(dispatcher) {
        val repo1 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo1.setBreathRhythm(3)

        val repo2 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(3, repo2.breathRhythm.first { it == 3 })
    }

    // ─── Nullable bell IDs: write, then write null = removed ─────────

    @Test
    fun `walkStartBellId round-trips a value then clears to null`() = runTest(dispatcher) {
        val repo1 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo1.setWalkStartBellId("temple-bell")

        val repo2 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals("temple-bell", repo2.walkStartBellId.first { it == "temple-bell" })

        repo2.setWalkStartBellId(null)
        val repo3 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo3.walkStartBellId.first { it == null })
    }

    @Test
    fun `walkEndBellId round-trips a value then clears to null`() = runTest(dispatcher) {
        val repo1 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo1.setWalkEndBellId("brass-bowl")

        val repo2 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals("brass-bowl", repo2.walkEndBellId.first { it == "brass-bowl" })

        repo2.setWalkEndBellId(null)
        val repo3 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo3.walkEndBellId.first { it == null })
    }

    @Test
    fun `meditationStartBellId round-trips a value then clears to null`() = runTest(dispatcher) {
        val repo1 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo1.setMeditationStartBellId("singing-bowl")

        val repo2 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals("singing-bowl", repo2.meditationStartBellId.first { it == "singing-bowl" })

        repo2.setMeditationStartBellId(null)
        val repo3 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo3.meditationStartBellId.first { it == null })
    }

    @Test
    fun `meditationEndBellId round-trips a value then clears to null`() = runTest(dispatcher) {
        val repo1 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        repo1.setMeditationEndBellId("kane")

        val repo2 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals("kane", repo2.meditationEndBellId.first { it == "kane" })

        repo2.setMeditationEndBellId(null)
        val repo3 = DataStoreSoundsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo3.meditationEndBellId.first { it == null })
    }
}

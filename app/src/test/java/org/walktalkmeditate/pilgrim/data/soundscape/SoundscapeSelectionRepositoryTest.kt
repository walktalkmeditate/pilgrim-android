// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.FakePreferencesDataStore

/**
 * Backed by an in-memory [FakePreferencesDataStore] (not the
 * file-backed `PreferenceDataStoreFactory`) so the repository's
 * `stateIn(scope, Eagerly)` producer and the awaiting `first { }`
 * both run in `runTest` virtual time on the test's
 * [UnconfinedTestDispatcher]-backed `backgroundScope`. Fully
 * deterministic — no `Dispatchers.Default`, no real-wall-clock
 * `withTimeout`, no generous CI headroom (the prior idiom flaked 3/3
 * on CI under runner contention — the ci-realtime-withtimeout family).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundscapeSelectionRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>

    @Before fun setUp() {
        dataStore = FakePreferencesDataStore()
    }

    @Test fun `initial value is null`() = runTest(UnconfinedTestDispatcher()) {
        val repo = SoundscapeSelectionRepository(dataStore, backgroundScope)
        assertNull(repo.selectedSoundscapeId.value)
    }

    @Test fun `select persists and emits`() = runTest(UnconfinedTestDispatcher()) {
        val repo = SoundscapeSelectionRepository(dataStore, backgroundScope)
        repo.select("forest-morning")
        assertEquals(
            "forest-morning",
            repo.selectedSoundscapeId.first { it == "forest-morning" },
        )
    }

    @Test fun `deselect clears and emits null`() = runTest(UnconfinedTestDispatcher()) {
        val repo = SoundscapeSelectionRepository(dataStore, backgroundScope)
        repo.select("forest-morning")
        repo.selectedSoundscapeId.first { it == "forest-morning" }

        repo.deselect()
        assertNull(repo.selectedSoundscapeId.first { it == null })
    }

    @Test fun `selection survives repository re-construction`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo1 = SoundscapeSelectionRepository(dataStore, backgroundScope)
            repo1.select("persisted")
            repo1.selectedSoundscapeId.first { it == "persisted" }

            val repo2 = SoundscapeSelectionRepository(dataStore, backgroundScope)
            assertEquals(
                "persisted",
                repo2.selectedSoundscapeId.first { it == "persisted" },
            )
        }
}

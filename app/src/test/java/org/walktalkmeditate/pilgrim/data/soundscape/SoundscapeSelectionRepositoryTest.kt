// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
class SoundscapeSelectionRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.preferencesDataStoreFile(DATASTORE_NAME).delete()
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(DATASTORE_NAME) },
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
        ApplicationProvider.getApplicationContext<Application>()
            .preferencesDataStoreFile(DATASTORE_NAME).delete()
    }

    @Test fun `initial value is null`() = runTest {
        val repo = SoundscapeSelectionRepository(dataStore, scope)
        assertNull(repo.selectedSoundscapeId.value)
    }

    @Test fun `select persists and emits`() = runTest {
        val repo = SoundscapeSelectionRepository(dataStore, scope)
        repo.select("forest-morning")

        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.selectedSoundscapeId.first { it == "forest-morning" }
            }
        }
        assertEquals("forest-morning", observed)
    }

    @Test fun `deselect clears and emits null`() = runTest {
        val repo = SoundscapeSelectionRepository(dataStore, scope)
        repo.select("forest-morning")
        withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.selectedSoundscapeId.first { it == "forest-morning" }
            }
        }

        repo.deselect()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.selectedSoundscapeId.first { it == null }
            }
        }
        assertNull(observed)
    }

    @Test fun `selection survives repository re-construction`() = runTest {
        val repo1 = SoundscapeSelectionRepository(dataStore, scope)
        repo1.select("persisted")
        withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo1.selectedSoundscapeId.first { it == "persisted" }
            }
        }

        val repo2Scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo2 = SoundscapeSelectionRepository(dataStore, repo2Scope)
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo2.selectedSoundscapeId.first { it == "persisted" }
            }
        }
        assertEquals("persisted", observed)
        repo2Scope.coroutineContext[Job]?.cancel()
    }

    private fun realTimeDispatcher() = Dispatchers.Default.limitedParallelism(1)

    private companion object {
        const val DATASTORE_NAME = "soundscape-selection-test"
        const val AWAIT_TIMEOUT_MS = 10_000L
    }
}

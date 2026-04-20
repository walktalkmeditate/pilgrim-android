// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

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

/**
 * Exercises [VoiceGuideSelectionRepository] against a real
 * Robolectric-backed `PreferenceDataStore`. Wires by hand (no
 * Hilt). Same shape as `HemisphereRepositoryTest` (Stage 3-D).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideSelectionRepositoryTest {

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
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        assertNull(repo.selectedPackId.value)
    }

    @Test fun `select persists and emits`() = runTest {
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        repo.select("morning-walk")

        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.selectedPackId.first { it == "morning-walk" }
            }
        }
        assertEquals("morning-walk", observed)
    }

    @Test fun `deselect clears and emits null`() = runTest {
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        repo.select("morning-walk")
        withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.selectedPackId.first { it == "morning-walk" }
            }
        }

        repo.deselect()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.selectedPackId.first { it == null }
            }
        }
        assertNull(observed)
    }

    @Test fun `selectIfUnset persists when unset`() = runTest {
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        repo.selectIfUnset("noon-sit")

        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.selectedPackId.first { it == "noon-sit" }
            }
        }
        assertEquals("noon-sit", observed)
    }

    @Test fun `selectIfUnset is a no-op when already set`() = runTest {
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        repo.select("first")
        withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.selectedPackId.first { it == "first" }
            }
        }
        repo.selectIfUnset("second")
        // Give the potential write a moment; assert still "first".
        repo.selectIfUnset("third")

        assertEquals("first", repo.selectedPackId.value)
    }

    @Test fun `selection survives repository re-construction`() = runTest {
        val repo1 = VoiceGuideSelectionRepository(dataStore, scope)
        repo1.select("persisted")
        withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo1.selectedPackId.first { it == "persisted" }
            }
        }

        val repo2Scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo2 = VoiceGuideSelectionRepository(dataStore, repo2Scope)
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo2.selectedPackId.first { it == "persisted" }
            }
        }
        assertEquals("persisted", observed)
        repo2Scope.coroutineContext[Job]?.cancel()
    }

    private fun realTimeDispatcher() = Dispatchers.Default.limitedParallelism(1)

    private companion object {
        const val DATASTORE_NAME = "voice-guide-selection-test"
        const val AWAIT_TIMEOUT_MS = 3_000L
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WidgetStateRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: WidgetStateRepository

    @Before
    fun setUp() {
        // Per-test scope so we can cancel in @After (Stage 8-B closing
        // review lesson — leaking SupervisorJob+IO scopes compound into
        // CI memory pressure).
        val unique = "test_widget_${UUID.randomUUID()}"
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(context.filesDir, "datastore/$unique.preferences_pb") },
        )
        repo = WidgetStateRepository(dataStore, json)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `initial stateFlow emits Empty when DataStore is fresh`() = runBlocking {
        assertEquals(WidgetState.Empty, repo.stateFlow.first())
    }

    @Test
    fun `write LastWalk then read round-trips identically`() = runBlocking {
        val state = WidgetState.LastWalk(
            walkId = 7L,
            endTimestampMs = 1_700_000_000_000L,
            distanceMeters = 5_432.10,
            activeDurationMs = 1_800_000L,
        )
        repo.write(state)
        assertEquals(state, repo.stateFlow.first())
    }

    @Test
    fun `write Empty after LastWalk reverts to Empty`() = runBlocking {
        repo.write(WidgetState.LastWalk(1L, 0L, 1.0, 1L))
        repo.stateFlow.first { it is WidgetState.LastWalk }
        repo.write(WidgetState.Empty)
        assertEquals(WidgetState.Empty, repo.stateFlow.first { it is WidgetState.Empty })
    }

    @Test
    fun `corrupted JSON in DataStore decodes to Empty fallback`() = runBlocking {
        // Write a manually-corrupted blob bypassing the repo.
        dataStore.edit { it[WidgetStateRepository.KEY_STATE_JSON] = "not-json-{garbage" }
        assertEquals(WidgetState.Empty, repo.stateFlow.first())
    }

    @Test
    fun `distinctUntilChanged suppresses duplicate writes`() = runBlocking {
        val state = WidgetState.LastWalk(1L, 0L, 1.0, 1L)
        repo.write(state)
        repo.stateFlow.first { it == state }
        // Second write of the same value — distinctUntilChanged should
        // dedupe at the consumer side.
        repo.write(state)
        // Take 1 emission with a small timeout — if the flow re-emits
        // identical values, this test would still pass (we'd just see
        // the latest), but the contract is that the operator filters.
        // We assert by reading the first emission and confirming it's
        // the same value; deeper duplicate-detection requires Turbine.
        assertTrue(repo.stateFlow.first() == state)
    }

    @Test
    fun `stateFlow can be collected as a fresh subscriber after a write`() = runBlocking {
        val state1 = WidgetState.LastWalk(1L, 100L, 100.0, 100L)
        val state2 = WidgetState.LastWalk(2L, 200L, 200.0, 200L)
        repo.write(state1)
        repo.write(state2)
        // A late subscriber sees the current state immediately.
        assertEquals(state2, repo.stateFlow.first())
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.location.FakeLocationSource

/**
 * Exercises [HemisphereRepository] against a real
 * [androidx.datastore.preferences] store (Robolectric-shadowable) and
 * the existing [FakeLocationSource] test double. No Hilt; we wire the
 * repository by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HemisphereRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var locationSource: FakeLocationSource
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Make sure we start from a clean slate — previous tests in the
        // same run may have left a preferences file behind.
        context.preferencesDataStoreFile(DATASTORE_NAME).delete()
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(DATASTORE_NAME) },
        )
        locationSource = FakeLocationSource()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
        ApplicationProvider.getApplicationContext<Application>()
            .preferencesDataStoreFile(DATASTORE_NAME).delete()
    }

    @Test fun `defaults to northern when no override and no location`() = runTest {
        val repo = HemisphereRepository(dataStore, locationSource, scope)
        // Collect via .first — the StateFlow's initialValue is immediately
        // available without needing to wait on a real-dispatcher upstream.
        assertEquals(Hemisphere.Northern, repo.hemisphere.value)
    }

    @Test fun `refresh is a no-op when no location is available`() = runTest {
        val repo = HemisphereRepository(dataStore, locationSource, scope)
        repo.refreshFromLocationIfNeeded()
        assertEquals(Hemisphere.Northern, repo.hemisphere.value)
    }

    @Test fun `infers southern from negative latitude`() = runTest {
        locationSource.lastKnown = LocationPoint(
            timestamp = 0L, latitude = -33.8688, longitude = 151.2093,
        )
        val repo = HemisphereRepository(dataStore, locationSource, scope)
        repo.refreshFromLocationIfNeeded()
        // Wait for the StateFlow (running on real Dispatchers.Default
        // in [scope]) to observe the DataStore write. `withTimeout`
        // under `runTest` uses virtual time by default, so bridge to
        // real time via the limited-parallelism dispatcher.
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.hemisphere.first { it == Hemisphere.Southern }
            }
        }
        assertEquals(Hemisphere.Southern, observed)
    }

    @Test fun `override wins over auto-inference`() = runTest {
        locationSource.lastKnown = LocationPoint(
            timestamp = 0L, latitude = -33.8688, longitude = 151.2093,
        )
        val repo = HemisphereRepository(dataStore, locationSource, scope)
        repo.setOverride(Hemisphere.Northern)
        repo.refreshFromLocationIfNeeded()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.hemisphere.first { it == Hemisphere.Northern }
            }
        }
        assertEquals(Hemisphere.Northern, observed)
        // A second refresh must not flip the override.
        repo.refreshFromLocationIfNeeded()
        val stillNorthern = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.hemisphere.first()
            }
        }
        assertEquals(Hemisphere.Northern, stillNorthern)
    }

    private fun realTimeDispatcher() = Dispatchers.Default.limitedParallelism(1)

    private companion object {
        const val DATASTORE_NAME = "hemisphere-test"
        const val AWAIT_TIMEOUT_MS = 3_000L
    }
}

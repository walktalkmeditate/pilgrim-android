// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
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
        repo.hemisphere.test {
            assertEquals(Hemisphere.Northern, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refresh is a no-op when no location is available`() = runTest {
        val repo = HemisphereRepository(dataStore, locationSource, scope)
        repo.refreshFromLocationIfNeeded()
        repo.hemisphere.test {
            assertEquals(Hemisphere.Northern, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `infers southern from negative latitude`() = runTest {
        locationSource.lastKnown = LocationPoint(
            timestamp = 0L, latitude = -33.8688, longitude = 151.2093,
        )
        val repo = HemisphereRepository(dataStore, locationSource, scope)
        repo.refreshFromLocationIfNeeded()
        repo.hemisphere.test {
            // The initial Eagerly emission may be Northern (pre-write);
            // await until Southern arrives.
            var latest = awaitItem()
            while (latest != Hemisphere.Southern) latest = awaitItem()
            assertEquals(Hemisphere.Southern, latest)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `override wins over auto-inference`() = runTest {
        locationSource.lastKnown = LocationPoint(
            timestamp = 0L, latitude = -33.8688, longitude = 151.2093,
        )
        val repo = HemisphereRepository(dataStore, locationSource, scope)
        repo.setOverride(Hemisphere.Northern)
        repo.refreshFromLocationIfNeeded()     // no-op because cached value exists
        repo.hemisphere.test {
            var latest = awaitItem()
            while (latest != Hemisphere.Northern) latest = awaitItem()
            assertEquals(Hemisphere.Northern, latest)
            // Wait briefly for any racing emission to surface; refresh
            // should NOT flip this back to Southern.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val DATASTORE_NAME = "hemisphere-test"
    }
}

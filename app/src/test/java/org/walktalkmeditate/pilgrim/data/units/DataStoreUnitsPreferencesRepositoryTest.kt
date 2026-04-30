// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.units

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
import kotlin.time.Duration.Companion.seconds
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
class DataStoreUnitsPreferencesRepositoryTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "units-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `default is Metric when no key written`() = runTest(dispatcher) {
        val repo = DataStoreUnitsPreferencesRepository(dataStore, scope)
        repo.distanceUnits.test(timeout = 10.seconds) {
            assertEquals(UnitSystem.Metric, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDistanceUnits persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStoreUnitsPreferencesRepository(dataStore, scope)
        repo1.setDistanceUnits(UnitSystem.Imperial)

        // Fresh repo emits its `Eagerly` seed (Metric, the default)
        // before upstream `dataStore.data` pushes the persisted value
        // through `.map / .distinctUntilChanged`. Use
        // `first { it == Imperial }` to wait for the loaded state.
        val repo2 = DataStoreUnitsPreferencesRepository(dataStore, scope)
        assertEquals(
            UnitSystem.Imperial,
            repo2.distanceUnits.first { it == UnitSystem.Imperial },
        )
    }

    @Test
    fun `unknown stored value falls back to Metric`() = runTest(dispatcher) {
        // Simulate forward-compat: a future build wrote
        // "nautical-miles" but this version only knows
        // kilometers/miles.
        dataStore.edit { it[stringPreferencesKey("distanceMeasurementType")] = "nautical-miles" }

        val repo = DataStoreUnitsPreferencesRepository(dataStore, scope)
        repo.distanceUnits.test(timeout = 10.seconds) {
            assertEquals(UnitSystem.Metric, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `distanceUnits emits new value after setDistanceUnits`() = runTest(dispatcher) {
        val repo = DataStoreUnitsPreferencesRepository(dataStore, scope)
        repo.distanceUnits.test(timeout = 10.seconds) {
            assertEquals(UnitSystem.Metric, awaitItem())
            repo.setDistanceUnits(UnitSystem.Imperial)
            assertEquals(UnitSystem.Imperial, awaitItem())
            repo.setDistanceUnits(UnitSystem.Metric)
            assertEquals(UnitSystem.Metric, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `iOS-faithful key 'distanceMeasurementType' is what we read and write`() =
        runTest(dispatcher) {
            // Directly seed via the iOS key string and confirm the
            // repo decodes the value — guards against silent renames
            // that would break .pilgrim ZIP cross-platform import.
            dataStore.edit {
                it[stringPreferencesKey("distanceMeasurementType")] = "miles"
            }

            val repo = DataStoreUnitsPreferencesRepository(dataStore, scope)
            assertEquals(
                UnitSystem.Imperial,
                repo.distanceUnits.first { it == UnitSystem.Imperial },
            )
        }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PermissionsRepositoryTest {

    private lateinit var tempFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: PermissionsRepository

    @Before
    fun setUp() {
        tempFile = File(
            System.getProperty("java.io.tmpdir"),
            "pilgrim-${UUID.randomUUID()}.preferences_pb",
        )
        // DataStore needs a scope for its internal write coroutine.
        // Must be separate from runTest's scope so its children don't
        // leak into the runTest unfinished-coroutines check.
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFile },
        )
        repository = PermissionsRepository(dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        tempFile.delete()
    }

    @Test
    fun `onboardingComplete starts as false`() = runTest {
        repository.onboardingComplete.test(timeout = 5.seconds) {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markOnboardingComplete flips the flow to true`() = runTest {
        repository.onboardingComplete.test(timeout = 5.seconds) {
            assertFalse(awaitItem())
            repository.markOnboardingComplete()
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `batteryExemptionAsked defaults to false and survives markBatteryExemptionAsked`() = runTest {
        repository.batteryExemptionAsked.test(timeout = 5.seconds) {
            assertFalse(awaitItem())
            repository.markBatteryExemptionAsked()
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onboarding and battery flags are independent`() = runTest {
        repository.markOnboardingComplete()

        repository.batteryExemptionAsked.test(timeout = 5.seconds) {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

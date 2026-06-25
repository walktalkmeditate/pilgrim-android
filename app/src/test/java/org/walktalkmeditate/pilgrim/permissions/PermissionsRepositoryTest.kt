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
        repository.onboardingComplete.test(timeout = 10.seconds) {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markOnboardingComplete flips the flow to true`() = runTest {
        repository.onboardingComplete.test(timeout = 10.seconds) {
            assertFalse(awaitItem())
            repository.markOnboardingComplete()
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `batteryExemptionAsked defaults to false and survives markBatteryExemptionAsked`() = runTest {
        repository.batteryExemptionAsked.test(timeout = 10.seconds) {
            assertFalse(awaitItem())
            repository.markBatteryExemptionAsked()
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onboarding and battery flags are independent`() = runTest {
        repository.markOnboardingComplete()

        repository.batteryExemptionAsked.test(timeout = 10.seconds) {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // #43 grant ritual persistence.

    @Test
    fun `consumeBellGrant fires once then stays silent`() = runTest {
        val location = PermissionRitual.Permission.Location
        assertFalse(repository.hasPlayedBell(location))

        assertTrue(
            "first grant should fire",
            repository.consumeBellGrant(location, soundsEnabled = true),
        )
        assertTrue("firing must persist", repository.hasPlayedBell(location))
        assertFalse(
            "a second grant for the same permission stays silent",
            repository.consumeBellGrant(location, soundsEnabled = true),
        )
    }

    @Test
    fun `consumeBellGrant is per permission`() = runTest {
        repository.consumeBellGrant(PermissionRitual.Permission.Location, soundsEnabled = true)

        assertTrue(
            "a different permission still rings its own first grant",
            repository.consumeBellGrant(PermissionRitual.Permission.Microphone, soundsEnabled = true),
        )
    }

    @Test
    fun `consumeBellGrant with sounds off does not consume the flag`() = runTest {
        val motion = PermissionRitual.Permission.Activity

        assertFalse(
            "no bell when sounds are off",
            repository.consumeBellGrant(motion, soundsEnabled = false),
        )
        assertFalse(
            "a silenced grant must not burn the once-per-grant flag",
            repository.hasPlayedBell(motion),
        )
        assertTrue(
            "re-enabling sounds lets the still-unplayed bell ring",
            repository.consumeBellGrant(motion, soundsEnabled = true),
        )
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.power

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.seek.SeekPowerTier

/**
 * Robolectric per the platform-object rule: the receiver registration path
 * runs against the real framework surface, not a fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekPowerTierSourceTest {

    private lateinit var context: Application
    private lateinit var powerManager: PowerManager
    private lateinit var source: SeekPowerTierSource

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        source = SeekPowerTierSource(context)
    }

    private fun setPowerSaveMode(enabled: Boolean) {
        shadowOf(powerManager).setIsPowerSaveMode(enabled)
    }

    private fun broadcastModeChange() {
        context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `initial collection seeds from the current mode`() = runTest {
        setPowerSaveMode(true)
        source.tiers.test {
            assertEquals(SeekPowerTier.LOW, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `power save broadcasts flip the tier both ways`() = runTest {
        source.tiers.test {
            assertEquals(SeekPowerTier.NORMAL, awaitItem())

            setPowerSaveMode(true)
            broadcastModeChange()
            assertEquals(SeekPowerTier.LOW, awaitItem())

            setPowerSaveMode(false)
            broadcastModeChange()
            assertEquals(SeekPowerTier.NORMAL, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `redundant broadcasts do not re-emit the same tier`() = runTest {
        source.tiers.test {
            assertEquals(SeekPowerTier.NORMAL, awaitItem())
            broadcastModeChange()
            broadcastModeChange()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `receiver registers on collection and unregisters on cancellation`() = runTest {
        assertFalse(hasPowerSaveReceiver())

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            source.tiers.collect { }
        }
        assertTrue(hasPowerSaveReceiver())

        job.cancel()
        testScheduler.advanceUntilIdle()
        assertFalse(hasPowerSaveReceiver())
    }

    private fun hasPowerSaveReceiver(): Boolean =
        shadowOf(context).registeredReceivers.any { wrapper ->
            wrapper.intentFilter.hasAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
}

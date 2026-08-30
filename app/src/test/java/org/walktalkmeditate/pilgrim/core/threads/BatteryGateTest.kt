// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Truth table for [BatteryGate.allowsBackgroundWork] — pinned parity spec
 * BEH-53/EDG-50: unknown level ALLOWS, the boundary is exclusive (exactly
 * 20% BLOCKS), and charging/full always ALLOWS regardless of level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BatteryGateTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Suppress("DEPRECATION")
    private fun stickBattery(level: Int, scale: Int, status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING) {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, scale)
            putExtra(BatteryManager.EXTRA_STATUS, status)
        }
        context.sendStickyBroadcast(intent)
    }

    @Test
    fun `no sticky battery intent ever sent - unknown level allows`() {
        assertTrue(BatteryGate.allowsBackgroundWork(context))
    }

    @Test
    fun `level well above 20 percent allows`() {
        stickBattery(level = 50, scale = 100)
        assertTrue(BatteryGate.allowsBackgroundWork(context))
    }

    @Test
    fun `level at exactly 21 percent allows`() {
        stickBattery(level = 21, scale = 100)
        assertTrue(BatteryGate.allowsBackgroundWork(context))
    }

    @Test
    fun `level at exactly 20 percent blocks - boundary is exclusive`() {
        stickBattery(level = 20, scale = 100, status = BatteryManager.BATTERY_STATUS_DISCHARGING)
        assertFalse(BatteryGate.allowsBackgroundWork(context))
    }

    @Test
    fun `level at 19 percent blocks`() {
        stickBattery(level = 19, scale = 100)
        assertFalse(BatteryGate.allowsBackgroundWork(context))
    }

    @Test
    fun `15-20 percent band blocks when not charging - BatteryNotLow would still admit this run`() {
        // Pinned cross-cutting fact (not exercised directly by BatteryGate
        // itself, but the reason the worker needs its own runtime re-check):
        // BatteryNotLow's system floor is ~15%, so WorkManager will happily
        // START a run anywhere in the 15-20% band that BatteryGate blocks.
        stickBattery(level = 17, scale = 100)
        assertFalse(BatteryGate.allowsBackgroundWork(context))
    }

    @Test
    fun `zero percent blocks when not charging`() {
        stickBattery(level = 0, scale = 100)
        assertFalse(BatteryGate.allowsBackgroundWork(context))
    }

    @Test
    fun `low level while charging allows`() {
        stickBattery(level = 5, scale = 100, status = BatteryManager.BATTERY_STATUS_CHARGING)
        assertTrue(BatteryGate.allowsBackgroundWork(context))
    }

    @Test
    fun `low level while full allows`() {
        stickBattery(level = 5, scale = 100, status = BatteryManager.BATTERY_STATUS_FULL)
        assertTrue(BatteryGate.allowsBackgroundWork(context))
    }

    @Test
    fun `high level while discharging still allows on its own merit`() {
        stickBattery(level = 80, scale = 100, status = BatteryManager.BATTERY_STATUS_DISCHARGING)
        assertTrue(BatteryGate.allowsBackgroundWork(context))
    }
}

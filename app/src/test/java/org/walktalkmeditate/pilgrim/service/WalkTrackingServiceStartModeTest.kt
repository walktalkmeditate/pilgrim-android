// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B2 regression: [WalkTrackingService.onStartCommand] previously returned
 * START_NOT_STICKY, so an OEM power-manager mid-walk kill (the
 * OnePlus/OxygenOS ~36-min force-kill that ended long backgrounded
 * walks) was never revived — the walk simply died and cold-start
 * "recovery" only finalized it.
 *
 * It now returns START_REDELIVER_INTENT so the OS re-delivers the
 * original ACTION_START into a fresh process, where startTracking()
 * rebuilds the live walk via WalkController.restoreActiveWalk().
 *
 * The project deliberately has no hilt-android-testing dependency
 * (see WalkTrackingServiceDiscardTest's rationale), so the start-mode
 * contract is pinned via the named [WalkTrackingService.START_MODE]
 * constant — the same pure-extraction precedent as decideStateAction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkTrackingServiceStartModeTest {

    @Test
    fun `onStartCommand return mode is START_REDELIVER_INTENT`() {
        assertEquals(Service.START_REDELIVER_INTENT, WalkTrackingService.START_MODE)
    }

    @Test
    fun `start mode is NOT the old START_NOT_STICKY`() {
        // The whole point of B2: START_NOT_STICKY meant no revival.
        assertNotEquals(Service.START_NOT_STICKY, WalkTrackingService.START_MODE)
    }

    @Test
    fun `start mode is NOT START_STICKY (null-intent revival crash)`() {
        // START_STICKY revives with a null intent → no pipeline + API 31+
        // FGS-start-timeout crash. The old comment rejected it for that
        // reason; REDELIVER_INTENT must not regress into it.
        assertNotEquals(Service.START_STICKY, WalkTrackingService.START_MODE)
    }

    @Test
    fun `redelivered intent carries ACTION_START so revival re-enters startTracking`() {
        // START_REDELIVER_INTENT re-delivers the LAST delivered intent.
        // The only intent the OS started us (foreground) with is
        // WalkTrackingService.startIntent — assert it carries ACTION_START
        // (not null), so onStartCommand's revived branch routes to
        // startTracking(), NOT the null-intent stopSelf() bail path.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val redelivered = WalkTrackingService.startIntent(context)
        assertEquals(WalkTrackingService.ACTION_START, redelivered.action)
        assertEquals(
            WalkTrackingService::class.java.name,
            redelivered.component?.className,
        )
    }

    @Test
    fun `ACTION_START intent action round-trips through Intent copy (parcel-equivalent)`() {
        // The OS re-delivers a COPY of the original intent on revival.
        // Verify the action survives a defensive copy so the revived
        // onStartCommand still matches the ACTION_START branch.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val original = WalkTrackingService.startIntent(context)
        val copy = Intent(original)
        assertEquals(WalkTrackingService.ACTION_START, copy.action)
    }
}

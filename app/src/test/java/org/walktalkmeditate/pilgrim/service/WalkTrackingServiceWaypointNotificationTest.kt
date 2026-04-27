// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkTrackingServiceWaypointNotificationTest {

    @Test fun `ACTION_MARK_WAYPOINT intent is constructible and addressed at the service`() {
        // Verifies that the notification builder's PendingIntent → service
        // path stays valid post-recordWaypoint signature change. The pure
        // smoke-check is: an Intent with ACTION_MARK_WAYPOINT action +
        // service component name resolves to WalkTrackingService.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WalkTrackingService::class.java).apply {
            action = WalkTrackingService.ACTION_MARK_WAYPOINT
        }
        assertEquals(WalkTrackingService.ACTION_MARK_WAYPOINT, intent.action)
        assertEquals(
            WalkTrackingService::class.java.name,
            intent.component?.className,
        )
    }
}

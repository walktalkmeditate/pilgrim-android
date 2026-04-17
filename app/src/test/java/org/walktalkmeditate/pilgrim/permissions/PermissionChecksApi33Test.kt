// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [PermissionChecks] on API 33 where POST_NOTIFICATIONS and
 * ACTIVITY_RECOGNITION both become runtime-gated permissions. Without
 * an explicit grant, both helpers must return `false` — the opposite of
 * the API 28 behaviour for ACTIVITY_RECOGNITION, which is the specific
 * bug the polish-pass SDK guard was added to fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PermissionChecksApi33Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `isActivityRecognitionGranted returns false when runtime permission not granted on TIRAMISU`() {
        assertFalse(PermissionChecks.isActivityRecognitionGranted(context))
    }

    @Test
    fun `isNotificationGranted returns false when POST_NOTIFICATIONS not granted on TIRAMISU`() {
        assertFalse(PermissionChecks.isNotificationGranted(context))
    }

    @Test
    fun `isMinimumGranted requires both FINE_LOCATION and POST_NOTIFICATIONS on TIRAMISU`() {
        assertFalse(PermissionChecks.isMinimumGranted(context))
    }
}

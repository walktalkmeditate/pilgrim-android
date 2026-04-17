// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [PermissionChecks] under the project's min SDK (API 28).
 * ACTIVITY_RECOGNITION is not a runtime permission on API 28, so the
 * helper must return `true` without consulting checkSelfPermission.
 * POST_NOTIFICATIONS is not a runtime permission either — the function
 * must short-circuit to `true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PermissionChecksApi28Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `isActivityRecognitionGranted returns true when permission predates runtime model`() {
        assertTrue(PermissionChecks.isActivityRecognitionGranted(context))
    }

    @Test
    fun `isNotificationGranted returns true on APIs before TIRAMISU`() {
        assertTrue(PermissionChecks.isNotificationGranted(context))
    }

    @Test
    fun `isFineLocationGranted returns false when manifest-only permission not granted`() {
        // Robolectric grants declared manifest permissions by default, but
        // ACCESS_FINE_LOCATION is a runtime permission on API 28+; without
        // explicit runtime grant it remains DENIED.
        assertFalse(PermissionChecks.isFineLocationGranted(context))
    }
}

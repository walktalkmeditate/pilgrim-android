// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Live runtime permission checks. Prefer these over a cached
 * [PermissionsRepository] state because the user can revoke permissions
 * from system Settings at any time.
 */
object PermissionChecks {

    fun isFineLocationGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    fun isNotificationGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun isActivityRecognitionGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // ACTIVITY_RECOGNITION became a runtime permission in API 29.
            // Before that, the capability is available without a prompt.
            true
        }

    /** True when the minimum set of required permissions for walk tracking is granted. */
    fun isMinimumGranted(context: Context): Boolean =
        isFineLocationGranted(context) && isNotificationGranted(context)
}

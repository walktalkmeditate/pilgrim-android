// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks

/**
 * Injectable seam over the static [PermissionChecks] helpers so unit
 * tests can supply deterministic state without spinning up Robolectric.
 */
interface LivePermissionChecks {
    fun isLocationGranted(): Boolean
    fun isMicrophoneGranted(): Boolean
    fun isMotionGranted(): Boolean
}

class PermissionChecksAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
) : LivePermissionChecks {
    override fun isLocationGranted(): Boolean = PermissionChecks.isFineLocationGranted(context)
    override fun isMicrophoneGranted(): Boolean = PermissionChecks.isMicrophoneGranted(context)
    override fun isMotionGranted(): Boolean = PermissionChecks.isActivityRecognitionGranted(context)
}

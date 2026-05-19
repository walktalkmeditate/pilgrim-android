// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.walktalkmeditate.pilgrim.permissions.BatteryExemption
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks

/**
 * Injectable seam over the static [PermissionChecks] helpers so unit
 * tests can supply deterministic state without spinning up Robolectric.
 */
interface LivePermissionChecks {
    fun isLocationGranted(): Boolean
    fun isMicrophoneGranted(): Boolean
    fun isMotionGranted(): Boolean

    /**
     * Battery-optimization exemption — not a runtime permission, but
     * THE determinant for long backgrounded-walk survival on OnePlus /
     * Xiaomi / Samsung. Surfaced as a persistent Settings row because
     * the onboarding card is reachable only during first-run and is
     * deferrable; an already-onboarded user otherwise has no in-app
     * path to fix dying walks.
     */
    fun isBatteryExempt(): Boolean
}

class PermissionChecksAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
) : LivePermissionChecks {
    override fun isLocationGranted(): Boolean = PermissionChecks.isFineLocationGranted(context)
    override fun isMicrophoneGranted(): Boolean = PermissionChecks.isMicrophoneGranted(context)
    override fun isMotionGranted(): Boolean = PermissionChecks.isActivityRecognitionGranted(context)
    override fun isBatteryExempt(): Boolean =
        BatteryExemption.isIgnoringBatteryOptimizations(context)
}

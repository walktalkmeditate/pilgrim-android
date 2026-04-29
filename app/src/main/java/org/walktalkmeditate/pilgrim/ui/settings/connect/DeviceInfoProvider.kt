// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.BuildConfig

/**
 * Builds the iOS-faithful "Android <release> · <model> · v<versionName>"
 * device-info string surfaced when the user opts in via the feedback
 * form's toggle. Strips the .debug applicationIdSuffix-implied suffix
 * is unnecessary here because we use BuildConfig.VERSION_NAME — that
 * carries `versionNameSuffix = "-debug"` on debug builds, which is
 * actually USEFUL signal in feedback (the recipient knows it's a debug
 * build). Keep the suffix.
 */
interface DeviceInfoProvider {
    fun deviceInfo(): String
}

@Singleton
class BuildDeviceInfoProvider @Inject constructor() : DeviceInfoProvider {
    override fun deviceInfo(): String =
        "Android ${Build.VERSION.RELEASE} · ${Build.MODEL} · v${BuildConfig.VERSION_NAME}"
}

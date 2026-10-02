// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.flags

import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager

/**
 * What the release build must not contain, shared by the release checks
 * (`src/testRelease`) and their debug twins (`src/testDebug`). Each twin
 * proves its check can see what it looks for, so a release pass is never
 * vacuous.
 */
internal object BuildContents {

    /** Classes compiled only into debug builds. Add each new debug-only class. */
    val DEBUG_ONLY_CLASSES = listOf(
        "org.walktalkmeditate.pilgrim.core.threads.ThreadsFieldReport",
        "org.walktalkmeditate.pilgrim.core.threads.ThreadsFieldReportReceiver",
        "org.walktalkmeditate.pilgrim.debug.honor.WayGpxExporter",
        "org.walktalkmeditate.pilgrim.debug.honor.ReplayStep",
        "org.walktalkmeditate.pilgrim.debug.honor.MockFix",
        "org.walktalkmeditate.pilgrim.debug.honor.WayReplayTimeline",
        "org.walktalkmeditate.pilgrim.debug.honor.MockLocationClient",
        "org.walktalkmeditate.pilgrim.debug.honor.FusedMockLocationClient",
        "org.walktalkmeditate.pilgrim.debug.honor.WayReplayer",
        "org.walktalkmeditate.pilgrim.debug.honor.WayReplayerModule",
        "org.walktalkmeditate.pilgrim.debug.honor.HonorDebugReceiver",
        "org.walktalkmeditate.pilgrim.debug.honor.HonorReplayReceiver",
        "org.walktalkmeditate.pilgrim.debug.honor.HonorDebugWays",
        "org.walktalkmeditate.pilgrim.debug.honor.HonorDebugReceiverKt",
    )

    /** Permissions only debug builds request; the release allow-list leaves them out. */
    val DEBUG_ONLY_PERMISSIONS = listOf(
        "android.permission.ACCESS_MOCK_LOCATION",
    )

    fun isOnClasspath(className: String): Boolean =
        runCatching { Class.forName(className) }.isSuccess

    /**
     * The app's own components that only a `DUMP` holder (adb) can reach —
     * debug harness triggers. Library components (WorkManager diagnostics,
     * ProfileInstaller) are DUMP-protected by design and are not counted.
     */
    fun ownDumpProtectedComponents(context: Context): List<String> {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS,
        )
        val components: List<ComponentInfo> =
            info.activities.orEmpty().toList() + info.receivers.orEmpty() +
                info.services.orEmpty() + info.providers.orEmpty()
        return components
            .filter { it.name.startsWith(APP_CODE_PACKAGE) }
            .filter { it.requiredPermission() == android.Manifest.permission.DUMP }
            .map { it.name }
    }

    private fun ComponentInfo.requiredPermission(): String? = when (this) {
        is android.content.pm.ActivityInfo -> permission
        is android.content.pm.ServiceInfo -> permission
        is android.content.pm.ProviderInfo -> readPermission
        else -> null
    }

    private const val APP_CODE_PACKAGE = "org.walktalkmeditate.pilgrim."
}

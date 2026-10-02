// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.flags

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Twins of `ReleaseBuildContentsTest`: each proves its release check can see
 * what it looks for, so the release job's passes are never vacuous.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DebugBuildContentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every debug-only class the release check names exists in debug`() {
        val missing = BuildContents.DEBUG_ONLY_CLASSES.filterNot(BuildContents::isOnClasspath)

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `the DUMP check finds every debug trigger`() {
        assertEquals(
            listOf(
                "org.walktalkmeditate.pilgrim.core.threads.ThreadsFieldReportReceiver",
                "org.walktalkmeditate.pilgrim.debug.honor.HonorDebugReceiver",
                "org.walktalkmeditate.pilgrim.debug.honor.HonorReplayReceiver",
            ),
            BuildContents.ownDumpProtectedComponents(context).sorted(),
        )
    }

    @Test
    fun `the honor link check sees the debug filter take honor links to MainActivity, and the walk host stays unclaimed`() {
        val claimed = context.packageManager.queryIntentActivities(BuildContents.honorLink(context), 0)
            .map { it.activityInfo.name }

        assertEquals(listOf("org.walktalkmeditate.pilgrim.MainActivity"), claimed)
        assertEquals(emptyList<Any>(), context.packageManager.queryIntentActivities(BuildContents.walkLink(context), 0))
    }

    @Test
    fun `the permission check sees every debug-only permission`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

        assertEquals(emptyList<String>(), BuildContents.DEBUG_ONLY_PERMISSIONS.filterNot(requested::contains))
    }
}

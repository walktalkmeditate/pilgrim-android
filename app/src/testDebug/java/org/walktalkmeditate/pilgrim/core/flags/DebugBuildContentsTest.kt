// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.flags

import android.app.Application
import android.content.Context
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
    fun `the DUMP check finds the debug field-report trigger`() {
        assertEquals(
            listOf("org.walktalkmeditate.pilgrim.core.threads.ThreadsFieldReportReceiver"),
            BuildContents.ownDumpProtectedComponents(context),
        )
    }
}

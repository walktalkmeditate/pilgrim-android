// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.flags

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.service.WalkTrackingService

/**
 * What the flag-off release build ships, checked by the release-variant CI
 * job. Robolectric reads the release manifest from the test resource APK,
 * which AGP packages from the app's own merged release manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReleaseBuildContentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `release requests only allow-listed permissions`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

        val unexpected = requested - allowedPermissions()

        assertTrue("release requests permissions off the allow-list: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun `release requests no debug-only permission`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

        assertEquals(emptyList<String>(), BuildContents.DEBUG_ONLY_PERMISSIONS.filter(requested::contains))
    }

    @Test
    fun `release has none of the app's DUMP-protected debug triggers`() {
        assertEquals(emptyList<String>(), BuildContents.ownDumpProtectedComponents(context))
    }

    @Test
    fun `release compiles in no debug-only class`() {
        val present = BuildContents.DEBUG_ONLY_CLASSES.filter(BuildContents::isOnClasspath)

        assertEquals(emptyList<String>(), present)
    }

    @Test
    fun `release claims no honor links before the 2_0_0 flip`() {
        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        // Guard: intent resolution works here, so an empty honor answer means unclaimed.
        assertTrue(context.packageManager.queryIntentActivities(launcher, 0).isNotEmpty())

        assertEquals(emptyList<Any>(), context.packageManager.queryIntentActivities(BuildContents.honorLink(context), 0))
    }

    @Test
    fun `release never claims the walk host`() {
        assertEquals(emptyList<Any>(), context.packageManager.queryIntentActivities(BuildContents.walkLink(context), 0))
    }

    @Test
    fun `release keeps the walk service unexported in its own process`() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, WalkTrackingService::class.java), 0)

        assertFalse("only the app's own intents may reach :tracker", info.exported)
        assertEquals("${context.packageName}:tracker", info.processName)
    }

    private fun allowedPermissions(): Set<String> = setOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.RECORD_AUDIO",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "android.permission.VIBRATE",
        "android.permission.WAKE_LOCK",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        // Play Install Referrer 2.2 (U27): binds the Play Store's referrer service.
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
    )
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.finsky.externalreferrer.IGetInstallReferrerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The real Play Install Referrer 2.2 client, built and driven by the
 * production adapter (the house builder rule): with no Play Store, and
 * against a stand-in for Play's referrer service bound in-process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayInstallReferrerClientAdapterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val adapter = PlayInstallReferrerClientAdapter(context)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the real client builds, not yet connected`() {
        val client = adapter.newClient()

        assertFalse(client.isReady)
    }

    @Test
    fun `with no Play Store to bind, the install has no referrer to read`() {
        val read = scope.async { adapter.read() }

        assertEquals(InstallReferrerRead.Answered(referrer = null), read.getCompleted())
    }

    @Test
    fun `with Play's service bound, it reads the referrer and disconnects`() {
        installPlayStore(
            object : IGetInstallReferrerService.Stub() {
                override fun c(request: Bundle): Bundle = Bundle().apply {
                    putString("install_referrer", "honor=Qoi4YmPHLN")
                }
            },
        )

        val read = scope.async { adapter.read() }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(InstallReferrerRead.Answered("honor=Qoi4YmPHLN"), read.getCompleted())
        assertTrue("the connection is let go", shadowOf(context as Application).unboundServiceConnections.isNotEmpty())
    }

    /** The Play Store at the version the library requires, with its referrer service answering in-process. */
    private fun installPlayStore(service: IGetInstallReferrerService.Stub) {
        val packageManager = shadowOf(context.packageManager)
        packageManager.installPackage(
            PackageInfo().apply {
                packageName = PLAY_STORE
                @Suppress("DEPRECATION")
                versionCode = PLAY_STORE_MIN_VERSION
                applicationInfo = ApplicationInfo().apply { packageName = PLAY_STORE }
            },
        )
        packageManager.addServiceIfNotPresent(REFERRER_SERVICE)
        packageManager.addIntentFilterForService(REFERRER_SERVICE, IntentFilter(BIND_ACTION))
        shadowOf(context as Application).setComponentNameAndServiceForBindService(REFERRER_SERVICE, service)
    }

    private companion object {
        const val PLAY_STORE = "com.android.vending"
        const val PLAY_STORE_MIN_VERSION = 80_837_300
        const val BIND_ACTION = "com.google.android.finsky.BIND_GET_INSTALL_REFERRER_SERVICE"
        val REFERRER_SERVICE = ComponentName(PLAY_STORE, "com.google.android.finsky.externalreferrer.GetInstallReferrerService")
    }
}

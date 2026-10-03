// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * The stage overview's one connectivity reading (pilgrimage-stage spec
 * P4 §6.4, A-4): iOS's `.satisfied` is a path to the internet, validated
 * or not, so a network with the internet capability reads connected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectivityInternetConnectionProbeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val probe = ConnectivityInternetConnectionProbe(context)

    @Test
    fun `an active network with the internet capability reads connected, unvalidated as it may be`() {
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))

        assertTrue(probe.isConnected())
    }

    @Test
    fun `an active network without the internet capability reads offline`() {
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities())

        assertFalse(probe.isConnected())
    }

    @Test
    fun `no active network reads offline`() {
        shadowOf(manager).setActiveNetworkInfo(null)

        assertFalse(probe.isConnected())
    }

    private fun capabilities(vararg capabilities: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { built ->
            capabilities.forEach { shadowOf(built).addCapability(it) }
        }
}

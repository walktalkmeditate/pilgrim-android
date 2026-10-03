// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * One reading of whether the phone has a network path to the internet, the
 * stand-in for iOS's `NWPathMonitor` reporting `.satisfied`
 * (`HonorOverviewView.swift:382-402@7c200bf`): the stage overview's offline
 * note asks it once (pilgrimage-stage spec P4 §6.4, A-4). A seam so the note
 * is testable, in [org.walktalkmeditate.pilgrim.audio.model.UnmeteredNetworkProbe]'s shape.
 */
fun interface InternetConnectionProbe {
    fun isConnected(): Boolean
}

/**
 * The active network with internet capability. Not `VALIDATED`, which would
 * be stricter than iOS's `.satisfied`: a Wi-Fi with no internet behind it
 * still reads connected there.
 */
class ConnectivityInternetConnectionProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) : InternetConnectionProbe {
    override fun isConnected(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

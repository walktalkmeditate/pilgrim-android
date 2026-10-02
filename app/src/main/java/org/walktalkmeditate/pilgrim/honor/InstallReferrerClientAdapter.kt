// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.content.Context
import android.os.RemoteException
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** One attempt at the Play Store's install referrer. */
sealed interface InstallReferrerRead {
    /** The Play Store answered: [referrer] is what the install link carried, null when it carried nothing. */
    data class Answered(val referrer: String?) : InstallReferrerRead

    /** Disconnected or unavailable for now: worth another attempt with a fresh client. */
    data object Retry : InstallReferrerRead
}

/** The Play Install Referrer library behind a seam the handoff's tests can hold open. */
interface InstallReferrerClientAdapter {
    /** Connects a fresh client, reads once, and disconnects. Suspends until the Play Store answers. */
    suspend fun read(): InstallReferrerRead
}

/**
 * The real client, Play Install Referrer 2.2. A feature the Play Store
 * doesn't support, a developer error, or a refused permission is an
 * answer: this install has no referrer to read, now or later.
 */
class PlayInstallReferrerClientAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstallReferrerClientAdapter {

    internal fun newClient(): InstallReferrerClient = InstallReferrerClient.newBuilder(context).build()

    override suspend fun read(): InstallReferrerRead {
        val client = newClient()
        return try {
            suspendCancellableCoroutine { continuation ->
                client.startConnection(
                    object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) {
                            if (continuation.isActive) continuation.resume(answer(client, responseCode))
                        }

                        override fun onInstallReferrerServiceDisconnected() {
                            if (continuation.isActive) continuation.resume(InstallReferrerRead.Retry)
                        }
                    },
                )
            }
        } finally {
            client.endConnection()
        }
    }

    private fun answer(client: InstallReferrerClient, responseCode: Int): InstallReferrerRead = when (responseCode) {
        InstallReferrerResponse.OK -> try {
            InstallReferrerRead.Answered(client.installReferrer.installReferrer)
        } catch (e: RemoteException) {
            Log.w(TAG, "the install referrer service failed mid-read", e)
            InstallReferrerRead.Retry
        }
        InstallReferrerResponse.SERVICE_DISCONNECTED, InstallReferrerResponse.SERVICE_UNAVAILABLE ->
            InstallReferrerRead.Retry
        else -> {
            Log.i(TAG, "no install referrer on this install (response $responseCode)")
            InstallReferrerRead.Answered(referrer = null)
        }
    }

    private companion object {
        const val TAG = "InstallReferrer"
    }
}

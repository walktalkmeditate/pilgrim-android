// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.location

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import org.walktalkmeditate.pilgrim.domain.LocationPoint

@Singleton
class FusedLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSource {

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    @SuppressLint("MissingPermission")
    override fun locationFlow(): Flow<LocationPoint> = callbackFlow {
        // Intentionally NO setMinUpdateDistanceMeters. On a 1-G device
        // test, the first few minutes delivered samples at the expected
        // cadence; then with the phone in a pocket and accuracy degrading
        // to 30–50 m, Android stopped forwarding samples to our callback
        // while GPS itself was still firing. The 2 m threshold is the
        // suspected culprit: when position jitters inside the accuracy
        // circle, consecutive samples fail the threshold and FLP drops
        // them silently. For a contemplative walk tracker we prefer
        // every sample at the interval cadence and can filter downstream
        // if we ever need to.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val now = System.currentTimeMillis()
                Log.i(
                    TAG,
                    "onLocationResult size=${result.locations.size} " +
                        "latestAcc=${result.lastLocation?.accuracy} " +
                        "dtSinceFixMs=${result.lastLocation?.time?.let { now - it }}",
                )
                result.locations.forEach { location ->
                    val point = LocationPoint(
                        timestamp = location.time,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        horizontalAccuracyMeters =
                            if (location.hasAccuracy()) location.accuracy else null,
                        speedMetersPerSecond =
                            if (location.hasSpeed()) location.speed else null,
                    )
                    trySend(point)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.i(TAG, "onLocationAvailability isLocationAvailable=${availability.isLocationAvailable}")
            }
        }

        Log.i(TAG, "requestLocationUpdates intervalMs=$UPDATE_INTERVAL_MS minIntervalMs=$MIN_UPDATE_INTERVAL_MS")
        client.requestLocationUpdates(request, callback, /* looper = */ null)

        awaitClose {
            Log.i(TAG, "removeLocationUpdates (flow cancelled)")
            client.removeLocationUpdates(callback)
        }
    }.buffer(Channel.UNLIMITED)

    private companion object {
        const val TAG = "FusedLocationSource"
        const val UPDATE_INTERVAL_MS = 2_000L
        const val MIN_UPDATE_INTERVAL_MS = 1_000L
    }
}

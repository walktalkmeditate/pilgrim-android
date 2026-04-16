// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
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
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_METERS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
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
        }

        client.requestLocationUpdates(request, callback, /* looper = */ null)

        awaitClose {
            client.removeLocationUpdates(callback)
        }
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 2_000L
        const val MIN_UPDATE_INTERVAL_MS = 1_000L
        const val MIN_UPDATE_DISTANCE_METERS = 2f
    }
}

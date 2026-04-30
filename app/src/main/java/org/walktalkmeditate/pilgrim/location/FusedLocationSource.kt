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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Tiny seam over `FusedLocationProviderClient.requestLocationUpdates` /
 * `removeLocationUpdates`. Production wraps the real FLP; tests inject
 * a fake that delivers `LocationResult`s synchronously.
 *
 * The seam exists for testability of the per-collection accuracy gate
 * (Stage 12-B). Driving real FLP under Robolectric is not viable — the
 * shadow currently stubs `requestLocationUpdates` as a no-op, which
 * would silently swallow every test's emissions.
 */
interface LocationCallbackBinder {
    fun register(callback: LocationCallback)
    fun unregister(callback: LocationCallback)
}

@Singleton
class FusedLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callbackBinder: LocationCallbackBinder,
) : LocationSource {

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    @SuppressLint("MissingPermission")
    override fun locationFlow(): Flow<LocationPoint> = callbackFlow {
        // Per-collection (per-walk) anchor flag. Lives inside the
        // callbackFlow body so each new `locationFlow()` collection —
        // i.e. each new walk — gets a fresh `false`. A class-level
        // AtomicBoolean would carry walk N's `true` into walk N+1,
        // rejecting the new walk's first bad-accuracy sample instead
        // of anchoring it. See Stage 12 spec, CRITICAL #1.
        val hasEmitted = AtomicBoolean(false)

        // Intentionally NO setMinUpdateDistanceMeters. On a 1-G device
        // test, the first few minutes delivered samples at the expected
        // cadence; then with the phone in a pocket and accuracy degrading
        // to 30–50 m, Android stopped forwarding samples to our callback
        // while GPS itself was still firing. The 2 m threshold is the
        // suspected culprit: when position jitters inside the accuracy
        // circle, consecutive samples fail the threshold and FLP drops
        // them silently. For a contemplative walk tracker we prefer
        // every sample at the interval cadence and can filter downstream.
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
                    val isFirst = !hasEmitted.get()
                    // First sample is force-anchored, mirroring iOS
                    // LocationManagement.swift `guard isFirst ||
                    // checkForAppropriateAccuracy(location)`. Sets the
                    // walk's geographic anchor even with bad GPS so a
                    // walk in a heavy backpack doesn't strand empty.
                    if (!isFirst && !meetsAccuracyGate(point)) return@forEach
                    hasEmitted.set(true)
                    trySend(point)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.i(TAG, "onLocationAvailability isLocationAvailable=${availability.isLocationAvailable}")
            }
        }

        Log.i(TAG, "requestLocationUpdates intervalMs=$UPDATE_INTERVAL_MS minIntervalMs=$MIN_UPDATE_INTERVAL_MS")
        callbackBinder.register(callback)

        awaitClose {
            Log.i(TAG, "removeLocationUpdates (flow cancelled)")
            callbackBinder.unregister(callback)
        }
    }.buffer(Channel.UNLIMITED)

    @SuppressLint("MissingPermission")
    override suspend fun lastKnownLocation(): LocationPoint? =
        suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { location ->
                    if (location == null) {
                        cont.resume(null)
                        return@addOnSuccessListener
                    }
                    cont.resume(
                        LocationPoint(
                            timestamp = location.time,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            horizontalAccuracyMeters =
                                if (location.hasAccuracy()) location.accuracy else null,
                            speedMetersPerSecond =
                                if (location.hasSpeed()) location.speed else null,
                        ),
                    )
                }
                .addOnFailureListener { t ->
                    Log.w(TAG, "lastLocation failed", t)
                    cont.resume(null)
                }
        }

    /**
     * Accept a sample only when its horizontal accuracy is finite and
     * within the iOS gates. `null`-accuracy returns `false` (defensive):
     * iOS sees a non-optional `CLLocation.horizontalAccuracy`, but
     * Android's [android.location.Location.hasAccuracy] can be false on
     * cold starts or low-power providers. A sample we can't quality-check
     * shouldn't accumulate distance — except for the first-sample anchor
     * exception applied at the call site, which still gives a walk its
     * starting position even when accuracy is reported absent.
     */
    private fun meetsAccuracyGate(point: LocationPoint): Boolean {
        val accuracy = point.horizontalAccuracyMeters ?: return false
        return accuracy < HARD_CEILING_METERS && accuracy <= DESIRED_ACCURACY_METERS
    }

    private companion object {
        const val TAG = "FusedLocationSource"
        const val UPDATE_INTERVAL_MS = 2_000L
        const val MIN_UPDATE_INTERVAL_MS = 1_000L
        /** iOS hard ceiling: any horizontalAccuracy >= 100m is rejected. */
        const val HARD_CEILING_METERS = 100f
        /** iOS default `desiredAccuracy` when the user hasn't set GPS Accuracy preference. */
        const val DESIRED_ACCURACY_METERS = 20f
    }
}

/**
 * Default [LocationCallbackBinder] backed by the real FLP. Hilt injects
 * this for production via [LocationModule]; unit tests construct
 * [FusedLocationSource] directly with a fake binder.
 */
@Singleton
class DefaultLocationCallbackBinder @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationCallbackBinder {

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val request: LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()

    @SuppressLint("MissingPermission")
    override fun register(callback: LocationCallback) {
        client.requestLocationUpdates(request, callback, /* looper = */ null)
    }

    override fun unregister(callback: LocationCallback) {
        client.removeLocationUpdates(callback)
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 2_000L
        const val MIN_UPDATE_INTERVAL_MS = 1_000L
    }
}

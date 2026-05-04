// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Reverse-geocodes start + end coordinates of a walk into [PlaceContext]
 * entries fed to PromptAssembler. Verbatim port of iOS
 * [PromptListView.geocodeWalkRoute] (lines 233-264) — start always
 * called, end called only if > 500m from start, with a 1.1s delay
 * between calls to placate Android's geocoder rate limiter (mirrors
 * iOS CLGeocoder mitigation).
 *
 * Failures are silent — null return → caller omits the location section
 * from the rendered prompt. Matches iOS error-eats-to-nil behavior.
 *
 * Uses the synchronous [Geocoder.getFromLocation] API; the API-33+
 * async variant is a future upgrade.
 */
@Singleton
open class PromptGeocoder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Test seam — production wires the real [Geocoder]. */
    private val geocoderFactory: (Context, Locale) -> GeocoderClient = ::defaultGeocoder,
    /** Test seam — production resolves to system default. */
    private val localeProvider: () -> Locale = Locale::getDefault,
) {
    open suspend fun geocodeStart(coord: LatLng): PlaceContext? = withContext(ioDispatcher) {
        runReverseGeocode(coord)?.let { name ->
            PlaceContext(name = name, coordinate = coord, role = PlaceRole.Start)
        }
    }

    open suspend fun geocodeEnd(coord: LatLng, distanceFromStartMeters: Double): PlaceContext? {
        if (distanceFromStartMeters <= DISTANCE_GATE_METERS) return null
        return withContext(ioDispatcher) {
            delay(RATE_LIMIT_DELAY_MS)
            runReverseGeocode(coord)?.let { name ->
                PlaceContext(name = name, coordinate = coord, role = PlaceRole.End)
            }
        }
    }

    private suspend fun runReverseGeocode(coord: LatLng): String? {
        val client = try {
            geocoderFactory(context, localeProvider())
        } catch (t: Throwable) {
            Log.w(TAG, "geocoder construction failed", t)
            return null
        }
        return try {
            val addresses = client.getFromLocation(coord.latitude, coord.longitude, 1)
            val first = addresses.firstOrNull() ?: return null
            formatAddress(first)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            Log.w(TAG, "geocoder I/O failure (offline?)", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "geocoder rejected coords lat=${coord.latitude} lon=${coord.longitude}", e)
            null
        }
    }

    private fun formatAddress(address: Address): String? {
        val parts = listOfNotNull(address.featureName, address.locality)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    interface GeocoderClient {
        suspend fun getFromLocation(latitude: Double, longitude: Double, maxResults: Int): List<Address>
    }

    companion object {
        private const val TAG = "PromptGeocoder"
        const val DISTANCE_GATE_METERS = 500.0
        const val RATE_LIMIT_DELAY_MS = 1_100L
    }
}

@Suppress("DEPRECATION")
private fun defaultGeocoder(context: Context, locale: Locale): PromptGeocoder.GeocoderClient =
    object : PromptGeocoder.GeocoderClient {
        private val real = Geocoder(context, locale)
        override suspend fun getFromLocation(latitude: Double, longitude: Double, maxResults: Int): List<Address> =
            real.getFromLocation(latitude, longitude, maxResults) ?: emptyList()
    }

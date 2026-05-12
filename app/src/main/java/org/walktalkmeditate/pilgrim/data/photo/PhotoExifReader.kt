// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads GPS EXIF coords from a content URI. iOS parity for capturing
 * `WalkPhoto.capturedLat` / `capturedLng` at pin time, used by Walk
 * Summary to render the photo as a map annotation pin
 * (`WalkSummaryView+Map.swift:34-46@db4196e`).
 *
 * Returns `null` when the URI is unreadable, the photo has no EXIF GPS
 * (indoor shot, screenshot, user-stripped metadata), or the coords are
 * the legacy `0.0, 0.0` (some Android system camera apps emit when GPS
 * was off).
 */
@Singleton
class PhotoExifReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class GpsCoords(val lat: Double, val lng: Double)

    /**
     * Reads EXIF GPS from [uri]. Pure I/O on the calling thread —
     * caller should hop to `Dispatchers.IO` before invoking (~10-30ms
     * per photo on a fast phone, longer over SAF/cloud-backed URIs).
     */
    fun read(uri: Uri): GpsCoords? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val coords = exif.latLong
            if (coords == null) {
                null
            } else {
                val lat = coords[0]
                val lng = coords[1]
                // Filter the legacy null-island sentinel some camera apps
                // emit when GPS is off — a real photo at (0.0, 0.0) is
                // essentially never the user's intent.
                if (lat == 0.0 && lng == 0.0) null else GpsCoords(lat, lng)
            }
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        Log.w(TAG, "EXIF read failed for $uri", t)
        null
    }

    private companion object {
        const val TAG = "PhotoExifReader"
    }
}

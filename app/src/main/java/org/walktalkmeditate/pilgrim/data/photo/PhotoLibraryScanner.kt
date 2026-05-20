// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

/**
 * iOS parity for the Photo Reliquary auto-discovery (the "future
 * MediaStore scan path" the rest of the codebase references in
 * comments). Queries `MediaStore.Images` for photos whose
 * `DATE_TAKEN` falls within the walk window, reads their EXIF GPS
 * via [ExifInterface], and filters to candidates near the walk
 * route.
 *
 * Required permissions (caller must check):
 *  - `READ_MEDIA_IMAGES` (Tiramisu+) or `READ_EXTERNAL_STORAGE` (<=32)
 *    for the MediaStore query itself.
 *  - `ACCESS_MEDIA_LOCATION` (Q+) for unredacted EXIF via
 *    [MediaStore.setRequireOriginal] — without it MediaStore strips
 *    the GPS tags and every candidate fails the proximity gate.
 *
 * Single-pass over the time-filtered candidate set; route-proximity
 * is a Haversine compare against the supplied route samples.
 */
@Singleton
class PhotoLibraryScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @androidx.compose.runtime.Immutable
    data class DiscoveredPhoto(
        val uri: Uri,
        val takenAtMs: Long,
        val latitude: Double,
        val longitude: Double,
    )

    /**
     * Photos in `[walkStartMs, walkEndMs]` with EXIF GPS within
     * [proximityMeters] of any sample in [route]. Empty when no
     * route samples (can't meaningfully filter — better to skip
     * than auto-pin every photo from the timeframe).
     */
    suspend fun scan(
        walkStartMs: Long,
        walkEndMs: Long,
        route: List<RouteDataSample>,
        proximityMeters: Double = DEFAULT_PROXIMITY_METERS,
    ): List<DiscoveredPhoto> = withContext(Dispatchers.IO) {
        if (walkEndMs <= walkStartMs) return@withContext emptyList()
        if (route.isEmpty()) return@withContext emptyList()
        val candidates = queryWindow(walkStartMs, walkEndMs)
        candidates.filter { c ->
            route.any { sample ->
                haversineMeters(c.latitude, c.longitude, sample.latitude, sample.longitude) <=
                    proximityMeters
            }
        }
    }

    private fun queryWindow(walkStartMs: Long, walkEndMs: Long): List<DiscoveredPhoto> {
        val cr = context.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?"
        val args = arrayOf(walkStartMs.toString(), walkEndMs.toString())
        val out = mutableListOf<DiscoveredPhoto>()
        cr.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val takenAt = cursor.getLong(takenCol)
                val rawUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                // Q+: setRequireOriginal yields unredacted EXIF (no
                // location-stripping). On older API the raw URI is
                // already unredacted.
                val readUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { MediaStore.setRequireOriginal(rawUri) }.getOrDefault(rawUri)
                } else {
                    rawUri
                }
                val latLng = readExifLatLng(readUri) ?: continue
                out += DiscoveredPhoto(
                    uri = rawUri,
                    takenAtMs = takenAt,
                    latitude = latLng.first,
                    longitude = latLng.second,
                )
            }
        }
        return out
    }

    private fun readExifLatLng(uri: Uri): Pair<Double, Double>? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val ll = exif.latLong ?: return@use null
            ll[0] to ll[1]
        }
    }.getOrNull()

    private fun haversineMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private companion object {
        // iOS uses ~200m as the threshold for "this photo was taken on
        // this walk" when the photo carries an EXIF fix near the route.
        const val DEFAULT_PROXIMITY_METERS = 200.0
    }
}

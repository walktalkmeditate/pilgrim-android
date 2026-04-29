// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import java.time.Instant
import kotlin.math.abs
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPhoto

/**
 * Bidirectional `WalkPhoto` ↔ `PilgrimPhoto` conversion. Kept separate
 * from the main converter to mirror iOS layout and to keep the GPS
 * derivation logic isolated.
 *
 * **Android-specific:** Room `WalkPhoto` has no `capturedLat`/`Lng`
 * columns. Export derives them from the nearest `RouteDataSample` to
 * `capturedAt`. Photos with no nearby route sample (walks without
 * GPS fix) are dropped from the export and counted as skipped.
 */
object PilgrimPackagePhotoConverter {

    /**
     * Returns null when [includePhotos] is false (key omitted from the
     * JSON output, byte-parity with pre-reliquary archives). Returns
     * an empty list when opted in but no photos pinned. Returns a
     * dropped/included partition when GPS derivation fails for some
     * photos — callers track [Result.skippedCount] for the post-share
     * "some photos couldn't be included" alert.
     */
    fun exportPhotos(
        walkPhotos: List<WalkPhoto>,
        routeSamples: List<RouteDataSample>,
        includePhotos: Boolean,
    ): Result {
        if (!includePhotos) return Result(photos = null, skippedCount = 0)
        if (walkPhotos.isEmpty()) return Result(photos = emptyList(), skippedCount = 0)

        var skipped = 0
        val converted = walkPhotos.mapNotNull { photo ->
            val capturedAtMs = photo.takenAt ?: photo.pinnedAt
            val nearest = nearestRouteSample(routeSamples, capturedAtMs)
            if (nearest == null) {
                skipped += 1
                null
            } else {
                PilgrimPhoto(
                    localIdentifier = photo.photoUri,
                    capturedAt = Instant.ofEpochMilli(capturedAtMs),
                    capturedLat = nearest.latitude,
                    capturedLng = nearest.longitude,
                    keptAt = Instant.ofEpochMilli(photo.pinnedAt),
                    embeddedPhotoFilename = null,
                )
            }
        }
        return Result(photos = converted, skippedCount = skipped)
    }

    /**
     * Reverse: rebuild Room `WalkPhoto` rows from the imported
     * archive's `photos[]` array. Drops `capturedLat/Lng` (no Android
     * column to receive them) — silently. Cross-platform photo
     * thumbnails come from the desktop viewer reading the embedded
     * JPEG bytes directly.
     */
    fun importPhotos(walkId: Long, exported: List<PilgrimPhoto>?): List<WalkPhoto> {
        if (exported.isNullOrEmpty()) return emptyList()
        return exported.map { photo ->
            WalkPhoto(
                walkId = walkId,
                photoUri = photo.localIdentifier,
                pinnedAt = photo.keptAt.toEpochMilli(),
                takenAt = photo.capturedAt.toEpochMilli(),
            )
        }
    }

    private fun nearestRouteSample(samples: List<RouteDataSample>, atMs: Long): RouteDataSample? {
        if (samples.isEmpty()) return null
        return samples.minByOrNull { abs(it.timestamp - atMs) }
    }

    data class Result(val photos: List<PilgrimPhoto>?, val skippedCount: Int)
}

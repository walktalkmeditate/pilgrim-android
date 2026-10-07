// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry

/**
 * iOS `PilgrimageTilesDescriptors` (`PilgrimageTilesDescriptors.swift@7c200bf`):
 * the numbers the offline descriptors are built from, kept as plain data so
 * a test can pin them without touching Mapbox (spec D C1 §1, §5).
 */
object PilgrimageTilesDescriptors {

    /**
     * The SDK downloads whole tile packs in fixed zoom bands (0–5, 6–10,
     * 11–14, 15–16) and caps a store at 750 unique packs. 11 is the first
     * band whose packs follow the corridor; 15–16 adds building footprints
     * only. Offline, the walk screen's z16 overzooms the saved z14.
     */
    val STREETS_ZOOM: IntRange = 11..14

    /**
     * The root of the one band the region lives in: a pack is one z11 tile
     * with its descendants to z14, per tileset, so the estimate counts z11
     * cells rather than tiles.
     */
    const val PACK_ROOT_ZOOM = 11

    /**
     * The first eight bytes of every corridor hash, so a region saved under
     * earlier descriptors reads as unsaved and the next save reloads it.
     * Bump it whenever the descriptors change under regions already on
     * phones. 2, as iOS's, though no Android phone ever held a version 1.
     */
    const val REGION_VERSION = 2

    /**
     * Shikoku and Kumano labels are CJK; rasterizing ideographs on the phone
     * keeps the style pack from carrying every glyph range.
     */
    const val RASTERIZES_IDEOGRAPHS_LOCALLY = true

    /**
     * Distinct XYZ tiles, summed over [zooms], that any part of [rings]
     * touches by [tileTouches]. The parts of several stages passed together
     * dedup the same way, which is how a z11 cell two stages share is one
     * pack in the estimate as it is in the store. Parts of three
     * coordinates or fewer are skipped; none left counts 0.
     *
     * Each part's own box rejects a tile before the five probes run. It is a
     * rejection, not an approximation: every way [tileTouches] can be true
     * puts a point in both boxes.
     */
    fun tileCount(rings: List<List<WayCoordinate>>, zooms: IntRange): Int {
        val parts = rings.filter { it.size > 3 }
        if (parts.isEmpty()) return 0
        val boxes = parts.map { part ->
            Box(
                minLat = part.minOf { it.lat },
                maxLat = part.maxOf { it.lat },
                minLon = part.minOf { it.lon },
                maxLon = part.maxOf { it.lon },
            )
        }
        var total = 0
        for (z in zooms) {
            val n = (1 shl z).toDouble()
            val (xMin, yMax) = tile(lat = boxes.minOf { it.minLat }, lon = boxes.minOf { it.minLon }, n = n)
            val (xMax, yMin) = tile(lat = boxes.maxOf { it.maxLat }, lon = boxes.maxOf { it.maxLon }, n = n)
            // The sweep visits each (z, x, y) once and stops at the first
            // part that touches it, so overlapping parts cannot double-count.
            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    val southWest = coordinate(x = x.toDouble(), y = (y + 1).toDouble(), n = n)
                    val northEast = coordinate(x = (x + 1).toDouble(), y = y.toDouble(), n = n)
                    val touched = parts.indices.any { index ->
                        val box = boxes[index]
                        box.maxLat >= southWest.lat && box.minLat <= northEast.lat &&
                            box.maxLon >= southWest.lon && box.minLon <= northEast.lon &&
                            tileTouches(parts[index], x = x, y = y, n = n)
                    }
                    if (touched) total += 1
                }
            }
        }
        return total
    }

    /** Web Mercator XYZ, clamped to the grid; no latitude clamp, the y clamp covers the poles. */
    private fun tile(lat: Double, lon: Double, n: Double): Pair<Int, Int> {
        val x = floor((lon + 180) / 360 * n).toInt()
        val latRad = lat * Math.PI / 180
        val y = floor((1 - ln(tan(latRad) + 1 / cos(latRad)) / Math.PI) / 2 * n).toInt()
        return Pair(min(max(x, 0), n.toInt() - 1), min(max(y, 0), n.toInt() - 1))
    }

    private fun coordinate(x: Double, y: Double, n: Double): WayCoordinate {
        val lon = x / n * 360 - 180
        val lat = atan(sinh(Math.PI * (1 - 2 * y / n))) * 180 / Math.PI
        return WayCoordinate(lat = lat, lon = lon)
    }

    /**
     * A tile counts when any of its corners or its centre is inside the
     * ring, or when a ring vertex is inside the tile, every side inclusive.
     * Not a true polygon–tile intersection: a long thin part that crosses a
     * tile with none of those inside misses it, so the estimate can err low,
     * though iOS's comment says it errs high. Matched as shipped and filed
     * (spec D C1-D1, pilgrim-ios #124 item 12); no shipped route hits it.
     */
    private fun tileTouches(ring: List<WayCoordinate>, x: Int, y: Int, n: Double): Boolean {
        val probes = listOf(
            coordinate(x = x.toDouble(), y = y.toDouble(), n = n),
            coordinate(x = (x + 1).toDouble(), y = y.toDouble(), n = n),
            coordinate(x = x.toDouble(), y = (y + 1).toDouble(), n = n),
            coordinate(x = (x + 1).toDouble(), y = (y + 1).toDouble(), n = n),
            coordinate(x = x.toDouble() + 0.5, y = y.toDouble() + 0.5, n = n),
        )
        if (probes.any { WayGeometry.ringContains(ring, it) }) return true
        val west = coordinate(x = x.toDouble(), y = (y + 1).toDouble(), n = n)
        val east = coordinate(x = (x + 1).toDouble(), y = y.toDouble(), n = n)
        return ring.any {
            it.lon >= west.lon && it.lon <= east.lon && it.lat >= west.lat && it.lat <= east.lat
        }
    }

    private class Box(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)
}

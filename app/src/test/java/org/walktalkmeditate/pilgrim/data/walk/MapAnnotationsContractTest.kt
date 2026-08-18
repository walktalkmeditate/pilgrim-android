// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.whisper.CachedWhisper
import org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory
import org.walktalkmeditate.pilgrim.ui.walk.packArgbLong

/**
 * Locks the two whisper-color packing sites together:
 * [computeWalkMapAnnotations] packs `Whisper.categoryColor` with inline
 * ARGB arithmetic, while `rememberWhisperGlyphBitmaps` (PilgrimMap.kt)
 * keys its wisp tint table via [packArgbLong] over the same fixed
 * `WhisperCategory` tints plus `Color(UNRESOLVED_WHISPER_ARGB)`. If the
 * two ever disagree for any category — or the unresolved constant stops
 * surviving the Color round-trip — the bitmap lookup misses and those
 * pins silently render icon-less (and untappable) on the map.
 */
class MapAnnotationsContractTest {

    private fun whisper(category: String) = CachedWhisper(
        id = "cache-$category",
        latitude = 1.0,
        longitude = 2.0,
        whisperId = "w-$category",
        category = category,
        expiresAt = "2026-12-31T00:00:00Z",
    )

    @Test
    fun everyPackedCategoryColor_isATintTableKey() {
        val whispers =
            WhisperCategory.entries.map { whisper(it.apiValue) } + whisper("uncharted-mood")
        val annotations = computeWalkMapAnnotations(
            routeSamples = listOf(
                RouteDataSample(
                    walkId = 1L, timestamp = 0L, latitude = 0.0, longitude = 0.0,
                    altitudeMeters = 0.0,
                ),
            ),
            meditationIntervals = emptyList(),
            nearbyWhispers = whispers,
        )
        val packed = annotations.mapNotNull {
            (it.kind as? WalkMapAnnotationKind.Whisper)?.categoryColor
        }
        assertEquals(whispers.size, packed.size)

        val tintTableKeys =
            WhisperCategory.entries.map { packArgbLong(it.borderColor) }.toSet() +
                UNRESOLVED_WHISPER_ARGB
        assertEquals(tintTableKeys, packed.toSet())
    }

    @Test
    fun unresolvedConstant_survivesTheColorRoundTrip() {
        assertEquals(UNRESOLVED_WHISPER_ARGB, packArgbLong(Color(UNRESOLVED_WHISPER_ARGB)))
    }
}

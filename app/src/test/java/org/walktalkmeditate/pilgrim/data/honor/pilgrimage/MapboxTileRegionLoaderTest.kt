// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.app.Application
import android.os.Looper
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.bindgen.Value
import com.mapbox.common.NetworkRestriction
import com.mapbox.common.TileRegion
import com.mapbox.common.TileRegionError
import com.mapbox.common.TileRegionErrorType
import com.mapbox.common.TilesetDescriptor
import com.mapbox.geojson.MultiPolygon
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.Style
import com.mapbox.maps.StylePack
import com.mapbox.maps.StylePackError
import com.mapbox.maps.StylePackErrorType
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate

/**
 * Port of iOS `MapboxTileRegionLoaderTests.swift@7c200bf` (spec D C3 §13.3):
 * its tests 3–7, test 5 adapted to Android's error types. Tests 1 and 2
 * read iOS's own store folder and map option, which Android doesn't have;
 * the backup-rules assertions and U48's device rows stand in for them.
 *
 * Then the Android additions: the region options and the style pack
 * options built as the loader builds them (CLAUDE.md's builder rule), a
 * read's summary and its pack filter from real SDK objects, the loader's
 * two entry rules, and its answer when the store can't open. The SDK's
 * natives never load under Robolectric, so the store, the offline manager
 * and their callbacks stay device-only, as iOS's do; the cache they feed
 * is [TileStoreCacheTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MapboxTileRegionLoaderTest {

    // ---- iOS's tests ----------------------------------------------------------

    @Test
    fun `descriptor options are the two styles at the spec's range and name no tileset`() {
        val options = MapboxTileRegionLoader.descriptorOptions(pixelRatio = 2.75f)

        assertEquals(listOf(Style.LIGHT, Style.DARK), options.map { it.styleURI })
        for (option in options) {
            assertEquals(11.toByte(), option.minZoom)
            assertEquals(14.toByte(), option.maxZoom)
            assertNull(option.tilesets)
            assertNull("the packs load on their own", option.stylePackOptions)
            assertEquals("the screen density, as iOS's SDK fills its scale", 2.75f, option.pixelRatio)
        }
    }

    @Test
    fun `the glyphs mode comes from the pinned constant`() {
        assertTrue(PilgrimageTilesDescriptors.RASTERIZES_IDEOGRAPHS_LOCALLY)
        assertEquals(GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY, MapboxTileRegionLoader.glyphsRasterizationMode)
    }

    /** iOS's `URLError(.cannotWriteToFile)` row has no Android input; its place pins that the message is never read. */
    @Test
    fun `mapped errors name a full disk, a pack ceiling and a cancel`() {
        assertEquals(TileRegionLoadingError.DISK_FULL, MapboxTileRegionLoader.mapped(TileRegionError(TileRegionErrorType.DISK_FULL, "x")))
        assertEquals(TileRegionLoadingError.DISK_FULL, MapboxTileRegionLoader.mapped(StylePackError(StylePackErrorType.DISK_FULL, "x")))
        assertEquals(
            TileRegionLoadingError.TILE_COUNT_EXCEEDED,
            MapboxTileRegionLoader.mapped(TileRegionError(TileRegionErrorType.TILE_COUNT_EXCEEDED, "x")),
        )
        assertEquals(TileRegionLoadingError.CANCELLED, MapboxTileRegionLoader.mapped(TileRegionError(TileRegionErrorType.CANCELED, "x")))
        assertEquals(
            TileRegionLoadingError.FAILED,
            MapboxTileRegionLoader.mapped(TileRegionError(TileRegionErrorType.OTHER, "No space left on device")),
        )
        assertEquals(TileRegionLoadingError.FAILED, MapboxTileRegionLoader.mapped(TileRegionError(TileRegionErrorType.OTHER, "x")))
    }

    @Test
    fun `every other region error is a failure`() {
        for (type in listOf(TileRegionErrorType.DOES_NOT_EXIST, TileRegionErrorType.TILESET_DESCRIPTOR, TileRegionErrorType.OTHER)) {
            assertEquals("$type", TileRegionLoadingError.FAILED, MapboxTileRegionLoader.mapped(TileRegionError(type, "x")))
        }
    }

    /** iOS's `.canceled` clause reads only a region error; the walker sees "the download didn't finish" either way. */
    @Test
    fun `a cancelled style pack is a failure, not a cancel, and so is every other pack error`() {
        for (type in listOf(StylePackErrorType.CANCELED, StylePackErrorType.DOES_NOT_EXIST, StylePackErrorType.OTHER)) {
            assertEquals("$type", TileRegionLoadingError.FAILED, MapboxTileRegionLoader.mapped(StylePackError(type, "x")))
        }
    }

    @Test
    fun `the settled projection ignores counts and sizes but not completion or hash`() {
        fun region(completed: Long, size: Long, hash: String) = TileRegionSummary(
            id = "r",
            completedResourceCount = completed,
            requiredResourceCount = 10,
            completedResourceSize = size,
            metadata = mapOf("corridorHash" to hash),
        )
        val downloading = region(completed = 3, size = 300, hash = "h")
        val further = region(completed = 7, size = 700, hash = "h")
        val done = region(completed = 10, size = 1_000, hash = "h")
        val redrawn = region(completed = 3, size = 300, hash = "h2")

        assertNotEquals("the snapshots differ, so the cache is rewritten", downloading, further)
        assertEquals(MapboxTileRegionLoader.settled(listOf(downloading)), MapboxTileRegionLoader.settled(listOf(further)))
        assertNotEquals(MapboxTileRegionLoader.settled(listOf(downloading)), MapboxTileRegionLoader.settled(listOf(done)))
        assertNotEquals(MapboxTileRegionLoader.settled(listOf(downloading)), MapboxTileRegionLoader.settled(listOf(redrawn)))
        assertNotEquals(
            "a region appearing or vanishing is a settled change",
            MapboxTileRegionLoader.settled(listOf(downloading)),
            MapboxTileRegionLoader.settled(emptyList()),
        )
    }

    @Test
    fun `isComplete mirrors TileRegionSummary's isComplete`() {
        assertTrue(MapboxTileRegionLoader.isComplete(completed = 10, required = 10))
        assertFalse(MapboxTileRegionLoader.isComplete(completed = 4, required = 10))
        assertFalse(MapboxTileRegionLoader.isComplete(completed = 0, required = 0))

        for ((completed, required) in listOf(10L to 10L, 4L to 10L, 0L to 0L, 0L to 5L, 5L to 0L)) {
            val summary = TileRegionSummary(
                id = "x",
                completedResourceCount = completed,
                requiredResourceCount = required,
                completedResourceSize = 0,
                metadata = emptyMap(),
            )
            assertEquals("$completed of $required", summary.isComplete, MapboxTileRegionLoader.isComplete(completed, required))
        }
    }

    // ---- The option builders (CLAUDE.md's builder rule) ---------------------------------

    /** The builder leaves the glyph mode unset, so only the explicit set pins it. */
    @Test
    fun `style pack options carry the pinned glyphs mode and nothing else`() {
        val options = MapboxTileRegionLoader.stylePackLoadOptions()

        assertEquals(GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY, options.glyphsRasterizationMode)
        assertFalse(options.acceptExpired)
        assertNull(options.metadata)
        assertNull(options.extraOptions)
    }

    @Test
    fun `region options carry every ring as one multipolygon, longitude first, with the hash, and nothing else`() {
        val descriptors = listOf(descriptorStub(), descriptorStub())

        val options = MapboxTileRegionLoader.regionLoadOptions(request(acceptExpired = true), descriptors)

        val geometry = options.geometry as MultiPolygon
        assertEquals("one polygon per convex part", 2, geometry.polygons().size)
        assertTrue("each with its one outer ring", geometry.coordinates().all { it.size == 1 })
        val first = geometry.coordinates()[0][0][0]
        assertEquals(-8.0, first.longitude(), 0.0)
        assertEquals(42.0, first.latitude(), 0.0)
        assertEquals("the rings in their own order", -7.95, geometry.coordinates()[1][0][0].longitude(), 0.0)
        assertEquals("closed, as given", 5, geometry.coordinates()[1][0].size)
        val metadata = options.metadata?.contents as Map<*, *>
        assertEquals(setOf("corridorHash"), metadata.keys)
        assertEquals(HASH, (metadata["corridorHash"] as Value).contents)
        assertTrue(options.acceptExpired)
        assertEquals("cellular allowed, as on iOS", NetworkRestriction.NONE, options.networkRestriction)
        assertSame("the two descriptors, handed through", descriptors, options.descriptors)
        assertNull(options.startLocation)
        assertNull(options.averageBytesPerSecond)
        assertNull(options.extraOptions)
    }

    @Test
    fun `a region's acceptExpired is the request's`() {
        val options = MapboxTileRegionLoader.regionLoadOptions(request(acceptExpired = false), listOf(descriptorStub()))

        assertFalse(options.acceptExpired)
    }

    // ---- A read's conversions, from real SDK objects ------------------------------------

    @Test
    fun `a region read gives the snapshot's counts and bytes and its metadata's hash`() {
        val region = TileRegion("pilgrimage:camino-frances:3", 10L, 7L, 70_000L, null, null)

        val summary = MapboxTileRegionLoader.summary(region, ExpectedFactory.createValue(hashMetadata("h")))

        assertEquals(
            TileRegionSummary(
                id = "pilgrimage:camino-frances:3",
                completedResourceCount = 7,
                requiredResourceCount = 10,
                completedResourceSize = 70_000,
                metadata = mapOf("corridorHash" to "h"),
            ),
            summary,
        )
    }

    /** iOS's `try?` and `as?` chain: whatever the store can't say reads as no corridor, so the stage reads stale. */
    @Test
    fun `a hash the store can't give reads as empty, never null`() {
        val unreadable = listOf(
            ExpectedFactory.createError<TileRegionError, Value>(TileRegionError(TileRegionErrorType.OTHER, "x")),
            ExpectedFactory.createValue<TileRegionError, Value>(Value.nullValue()),
            ExpectedFactory.createValue<TileRegionError, Value>(Value.valueOf("h")),
            ExpectedFactory.createValue<TileRegionError, Value>(Value.valueOf(hashMapOf("other" to Value.valueOf("h")))),
            ExpectedFactory.createValue<TileRegionError, Value>(Value.valueOf(hashMapOf("corridorHash" to Value.valueOf(7L)))),
        )
        for (metadata in unreadable) {
            assertEquals("$metadata", "", MapboxTileRegionLoader.corridorHash(metadata))
        }
    }

    /** iOS filters by this rule on its worker thread, where no test reaches it. */
    @Test
    fun `a style pack counts as present only when complete, by its exact style`() {
        val packs = listOf(
            stylePack(Style.LIGHT, required = 10, completed = 10),
            stylePack(Style.DARK, required = 10, completed = 4),
            stylePack("mapbox://styles/mapbox/streets-v12", required = 10, completed = 10),
        )

        assertEquals(setOf(StylePackRequest.LIGHT), MapboxTileRegionLoader.presentPacks(packs))
        assertEquals(emptySet<StylePackRequest>(), MapboxTileRegionLoader.presentPacks(listOf(stylePack(Style.DARK, 0, 0))))
        assertEquals(
            StylePackRequest.entries.toSet(),
            MapboxTileRegionLoader.presentPacks(listOf(stylePack(Style.DARK, 10, 12), stylePack(Style.LIGHT, 3, 3))),
        )
    }

    // ---- The loader's entry rules ---------------------------------------------------------

    /** Its first call opens the store; building it, as Hilt does in either process, opens nothing. */
    @Test
    fun `building the loader makes no Mapbox object`() {
        val scope = CoroutineScope(SupervisorJob() + recordingHandler(mutableListOf()))

        MapboxTileRegionLoader(scope).onChange = {}

        scope.cancel()
    }

    @Test
    fun `a call off the main thread fails before it reaches Mapbox`() {
        val scope = CoroutineScope(SupervisorJob() + recordingHandler(mutableListOf()))
        val loader = MapboxTileRegionLoader(scope)
        val thrown = AtomicReference<Throwable>()

        thread { thrown.set(runCatching { loader.regions() }.exceptionOrNull()) }.join()

        assertTrue("${thrown.get()}", thrown.get() is IllegalStateException)
        scope.cancel()
    }

    @Test
    fun `the loader refuses a scope with no exception handler`() {
        assertThrows(IllegalArgumentException::class.java) { MapboxTileRegionLoader(CoroutineScope(SupervisorJob())) }
    }

    /** Mapbox's startup swallows a failed init, and the next Mapbox class to load retries it: an `Error`, not an `Exception`. */
    @Test
    fun `a store that can't open answers the first answer failed, posted, and its error reaches the scope's handler`() {
        val escaped = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + recordingHandler(escaped))
        val loader = MapboxTileRegionLoader(scope) { throw ExceptionInInitializerError("Mapbox couldn't initialize") }
        val answers = mutableListOf<TileStoreRead>()

        loader.firstAnswer { answers += it }
        assertTrue("never answered before the call returns", answers.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(TileStoreRead.FAILED), answers)
        assertTrue("$escaped", escaped.single() is ExceptionInInitializerError)
        scope.cancel()
    }

    @Test
    fun `a call after a store that couldn't open tries the open again`() {
        val scope = CoroutineScope(SupervisorJob() + recordingHandler(mutableListOf()))
        var opens = 0
        val loader = MapboxTileRegionLoader(scope) {
            opens += 1
            throw NoClassDefFoundError("com/mapbox/maps/OfflineManager")
        }
        val answers = mutableListOf<TileStoreRead>()

        loader.firstAnswer { answers += it }
        shadowOf(Looper.getMainLooper()).idle()
        loader.firstAnswer { answers += it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(TileStoreRead.FAILED, TileStoreRead.FAILED), answers)
        assertEquals(2, opens)
        scope.cancel()
    }

    private companion object {
        const val HASH = "0200000000000000abc"

        /** Two closed 5-point rings around the Francés' longitudes, the second east of the first. */
        fun request(acceptExpired: Boolean) = TileRegionRequest(
            id = "pilgrimage:camino-frances:0",
            rings = listOf(ring(west = -8.0), ring(west = -7.95)),
            corridorHash = HASH,
            acceptExpired = acceptExpired,
        )

        fun ring(west: Double) = listOf(
            WayCoordinate(lat = 42.0, lon = west),
            WayCoordinate(lat = 42.0, lon = west + 0.01),
            WayCoordinate(lat = 42.01, lon = west + 0.01),
            WayCoordinate(lat = 42.01, lon = west),
            WayCoordinate(lat = 42.0, lon = west),
        )

        /** A peer-0 descriptor never registers a native cleaner; only its identity is used. */
        fun descriptorStub(): TilesetDescriptor = object : TilesetDescriptor(0L) {}

        fun hashMetadata(hash: String): Value = Value.valueOf(hashMapOf("corridorHash" to Value.valueOf(hash)))

        fun stylePack(styleUri: String, required: Long, completed: Long) =
            StylePack(styleUri, GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY, required, completed, completed * 1_000, null, null)
    }
}

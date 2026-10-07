// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.content.res.Resources
import android.os.Looper
import androidx.annotation.MainThread
import com.mapbox.bindgen.Expected
import com.mapbox.bindgen.Value
import com.mapbox.common.NetworkRestriction
import com.mapbox.common.TileRegion
import com.mapbox.common.TileRegionError
import com.mapbox.common.TileRegionErrorType
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileStore
import com.mapbox.common.TilesetDescriptor
import com.mapbox.geojson.MultiPolygon
import com.mapbox.geojson.Point
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.Style
import com.mapbox.maps.StylePack
import com.mapbox.maps.StylePackError
import com.mapbox.maps.StylePackErrorType
import com.mapbox.maps.StylePackLoadOptions
import com.mapbox.maps.TilesetDescriptorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The one file that speaks to Mapbox's offline API (iOS `MapboxTileRegionLoader`,
 * `MapboxTileRegionLoader.swift@7c200bf`, spec D C3). Everything above it
 * talks in [TileRegionRequest] and [TileRegionSummary]; what it knows of the
 * store between reads is [TileStoreCache].
 *
 * **The store** is Mapbox's default, `TileStore.create()` under
 * `files/.mapbox/`, the store the map already reads in its default
 * READ_ONLY mode, so no map option is written (owner decision 2026-10-06,
 * C3 §2.3, §3.1). The backup rules keep `.mapbox/` out of a device transfer.
 *
 * **First use.** Building the loader makes no Mapbox object, so a graph
 * built in `:tracker` or with the flag off opens nothing. The store, the
 * offline manager and both descriptors are made by the first call, on the
 * main thread, and held for the process; that call also starts the first
 * store read, as iOS's `init` does (C3 §8.2).
 *
 * **One thread.** Every method is main-only, checked at entry: the offline
 * manager is `@MainThread`, and calling it from another thread is undefined
 * rather than an exception. Every SDK callback arrives on a Mapbox worker
 * thread and does one thing there: post itself to the main thread through
 * [scope], so a hop that throws reaches the scope's handler instead of
 * taking down the UI process (C3 §8.2, §C2.12 point 6).
 *
 * Errors map by type only (C3 §9.2). A load's progress is never reported:
 * its one caller, the tiles manager, passes iOS's no-op for it.
 *
 * A Mapbox call that throws is a failed answer, still posted: a read's half,
 * or the first answer when the store can't open. Any non-cancellation
 * `Throwable` counts, since a device whose Mapbox natives didn't load throws
 * an `Error` there: Mapbox's startup swallows the failure, and the next
 * Mapbox class to load retries it and throws.
 */
class MapboxTileRegionLoader internal constructor(
    private val scope: CoroutineScope,
    /** The first Mapbox object a call makes; a test's throws, as a device's does when Mapbox can't initialize. */
    private val makeOfflineManager: () -> OfflineManager = { OfflineManager() },
) : TileRegionLoading {

    private class MapboxObjects(val store: TileStore, val offline: OfflineManager, val descriptors: List<TilesetDescriptor>)

    private var opened: MapboxObjects? = null

    private val cache = TileStoreCache(startRead = ::read)

    init {
        require(scope.coroutineContext[CoroutineExceptionHandler] != null) {
            "the tiles scope needs an exception handler, or a failed hop crashes the UI process"
        }
    }

    override var onChange: ((TileStoreChange) -> Unit)?
        get() = cache.onChange
        set(value) {
            cache.onChange = value
        }

    @MainThread
    override fun hasStylePack(pack: StylePackRequest): Boolean {
        open()
        return cache.hasStylePack(pack)
    }

    @MainThread
    override fun loadStylePack(pack: StylePackRequest, completion: (TileLoadResult<Unit>) -> Unit): TileLoadHandle {
        val cancelable = open().offline.loadStylePack(styleUri(pack), stylePackLoadOptions()) { answer ->
            onMain {
                if (answer.value != null) {
                    cache.packLoaded(pack, completion)
                } else {
                    completion(TileLoadResult.Failure(answer.error?.let { mapped(it) } ?: TileRegionLoadingError.FAILED))
                }
            }
        }
        return TileLoadHandle(cancelable::cancel)
    }

    @MainThread
    override fun loadRegion(
        request: TileRegionRequest,
        progress: (completed: Long, required: Long) -> Unit,
        completion: (TileLoadResult<TileRegionSummary>) -> Unit,
    ): TileLoadHandle {
        val mapbox = open()
        val cancelable = mapbox.store.loadTileRegion(request.id, regionLoadOptions(request, mapbox.descriptors)) { answer ->
            onMain {
                val region = answer.value
                if (region != null) {
                    // The request's hash, not a metadata read-back: it is what the load was given.
                    cache.regionLoaded(summary(region, request.corridorHash), completion)
                } else {
                    completion(TileLoadResult.Failure(answer.error?.let { mapped(it) } ?: TileRegionLoadingError.FAILED))
                }
            }
        }
        return TileLoadHandle(cancelable::cancel)
    }

    @MainThread
    override fun regions(): List<TileRegionSummary> {
        open()
        return cache.regions()
    }

    @MainThread
    override fun refreshRegions(completion: () -> Unit) {
        open()
        cache.refreshRegions(completion)
    }

    /** Fire-and-forget, as the SDK's removal is: a load of the same id still pending fails as cancelled. */
    @MainThread
    override fun removeRegion(id: String) {
        open().store.removeTileRegion(id)
        cache.regionRemoved(id)
    }

    /**
     * A store that can't even be opened answers [TileStoreRead.FAILED],
     * posted, rather than leaving the caller waiting; a later call tries
     * again, as a call after any failed answer does.
     */
    @MainThread
    override fun firstAnswer(completion: (TileStoreRead) -> Unit) {
        checkMainThread()
        try {
            open()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            reportUnrequested(failure) { completion(TileStoreRead.FAILED) }
            return
        }
        cache.firstAnswer(completion)
    }

    private fun open(): MapboxObjects {
        checkMainThread()
        opened?.let { return it }
        val offline = makeOfflineManager()
        val descriptors = descriptorOptions(Resources.getSystem().displayMetrics.density).map(offline::createTilesetDescriptor)
        return MapboxObjects(TileStore.create(), offline, descriptors).also {
            opened = it
            cache.refresh()
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "the tile region loader is main-thread only" }
    }

    /**
     * The regions and the packs, each answered on a worker thread and hopped
     * back. A request the SDK refuses outright is that half's failed answer,
     * still posted, so a waiter is never answered before its call returns.
     */
    private fun read(token: Int) {
        val mapbox = checkNotNull(opened) { "a store read before the store opened" }
        try {
            mapbox.store.getAllTileRegions { answer ->
                onMain {
                    val regions = answer.value
                    if (regions == null) cache.regionsAnswered(token, null) else readMetadata(mapbox.store, token, regions)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            reportUnrequested(failure) { cache.regionsAnswered(token, null) }
        }
        try {
            mapbox.offline.getAllStylePacks { answer ->
                // A failed packs read is hopped too, unlike iOS's, so the first answer hears it (C3 §7).
                onMain { cache.packsAnswered(token, answer.value?.let(::presentPacks)) }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            reportUnrequested(failure) { cache.packsAnswered(token, null) }
        }
    }

    /**
     * One metadata read per region, issued from the main thread and each
     * answer hopped back to it, so the count needs no lock; the generation
     * is checked once, when the last lands (C3 §6.4).
     */
    private fun readMetadata(store: TileStore, token: Int, regions: List<TileRegion>) {
        if (regions.isEmpty()) {
            cache.regionsAnswered(token, emptyList())
            return
        }
        val summaries = ArrayList<TileRegionSummary>(regions.size)
        try {
            for (region in regions) {
                store.getTileRegionMetadata(region.id) { metadata ->
                    onMain {
                        summaries += summary(region, metadata)
                        if (summaries.size == regions.size) cache.regionsAnswered(token, summaries)
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            // The count can no longer reach the list's size, so this is the read's only answer.
            reportUnrequested(failure) { cache.regionsAnswered(token, null) }
        }
    }

    /** iOS's `DispatchQueue.main.async`: always enqueued behind what the main thread already holds, never run inline. */
    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    /** [answer] runs posted; [failure] is rethrown inside [scope], whose handler logs it, since nothing in this package does. */
    private fun reportUnrequested(failure: Throwable, answer: () -> Unit) {
        scope.launch(Dispatchers.Main) {
            answer()
            throw failure
        }
    }

    /** What `onChange(REGIONS)` speaks for: which regions exist, whether each is done, and which corridor it was loaded for. */
    internal data class SettledRegion(val id: String, val isComplete: Boolean, val corridorHash: String?)

    companion object {

        /** From the pinned constant rather than the SDK's default, which agrees today and is exactly what gets "fixed". */
        internal val glyphsRasterizationMode: GlyphsRasterizationMode
            get() = if (PilgrimageTilesDescriptors.RASTERIZES_IDEOGRAPHS_LOCALLY) {
                GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY
            } else {
                GlyphsRasterizationMode.NO_GLYPHS_RASTERIZED_LOCALLY
            }

        /** The map's own two styles: the descriptors, the pack loads and the pack filter all key on these. */
        internal fun styleUri(pack: StylePackRequest): String = when (pack) {
            StylePackRequest.LIGHT -> Style.LIGHT
            StylePackRequest.DARK -> Style.DARK
        }

        /**
         * The two descriptors every region is loaded with, light then dark,
         * at the streets band. No `tilesets`: the styles' composite source
         * already carries Streets and terrain-v2, and no DEM is saved. No
         * style pack either; the packs load on their own. [pixelRatio] is
         * the screen density, the value iOS's SDK fills in (C3-3); with
         * all-vector styles it changes no download.
         */
        internal fun descriptorOptions(pixelRatio: Float): List<TilesetDescriptorOptions> = StylePackRequest.entries.map { pack ->
            TilesetDescriptorOptions.Builder()
                .styleURI(styleUri(pack))
                .minZoom(PilgrimageTilesDescriptors.STREETS_ZOOM.first.toByte())
                .maxZoom(PilgrimageTilesDescriptors.STREETS_ZOOM.last.toByte())
                .pixelRatio(pixelRatio)
                .build()
        }

        /** The glyph mode set explicitly, since the builder leaves it unset; nothing else. */
        internal fun stylePackLoadOptions(): StylePackLoadOptions =
            StylePackLoadOptions.Builder().glyphsRasterizationMode(glyphsRasterizationMode).build()

        /**
         * One polygon per convex part, longitude first, in the rings' own
         * order; the store unions them when it tiles. Cellular is allowed,
         * as on iOS, and set so the test can pin it.
         */
        internal fun regionLoadOptions(request: TileRegionRequest, descriptors: List<TilesetDescriptor>): TileRegionLoadOptions =
            TileRegionLoadOptions.Builder()
                .geometry(MultiPolygon.fromLngLats(request.rings.map { ring -> listOf(ring.map { Point.fromLngLat(it.lon, it.lat) }) }))
                .descriptors(descriptors)
                .metadata(Value.valueOf(hashMapOf(TileRegionSummary.CORRIDOR_HASH_KEY to Value.valueOf(request.corridorHash))))
                .acceptExpired(request.acceptExpired)
                .networkRestriction(NetworkRestriction.NONE)
                .build()

        internal fun mapped(error: TileRegionError): TileRegionLoadingError = when (error.type) {
            TileRegionErrorType.DISK_FULL -> TileRegionLoadingError.DISK_FULL
            TileRegionErrorType.TILE_COUNT_EXCEEDED -> TileRegionLoadingError.TILE_COUNT_EXCEEDED
            TileRegionErrorType.CANCELED -> TileRegionLoadingError.CANCELLED
            TileRegionErrorType.DOES_NOT_EXIST, TileRegionErrorType.TILESET_DESCRIPTOR, TileRegionErrorType.OTHER ->
                TileRegionLoadingError.FAILED
        }

        /** A cancelled pack is a failure, not a cancel, as iOS maps it (C3-2); the walker reads the same line either way. */
        internal fun mapped(error: StylePackError): TileRegionLoadingError = when (error.type) {
            StylePackErrorType.DISK_FULL -> TileRegionLoadingError.DISK_FULL
            StylePackErrorType.CANCELED, StylePackErrorType.DOES_NOT_EXIST, StylePackErrorType.OTHER ->
                TileRegionLoadingError.FAILED
        }

        /** A read's region: the snapshot's counts and bytes, with the hash its metadata holds. */
        internal fun summary(region: TileRegion, metadata: Expected<TileRegionError, Value>): TileRegionSummary =
            summary(region, corridorHash(metadata))

        internal fun summary(region: TileRegion, corridorHash: String): TileRegionSummary = TileRegionSummary(
            id = region.id,
            completedResourceCount = region.completedResourceCount,
            requiredResourceCount = region.requiredResourceCount,
            completedResourceSize = region.completedResourceSize,
            metadata = mapOf(TileRegionSummary.CORRIDOR_HASH_KEY to corridorHash),
        )

        /** `""`, never null, for a failed read, a value that isn't an object, or one with no hash string, as iOS's `try?` and `as?` give. */
        internal fun corridorHash(metadata: Expected<TileRegionError, Value>): String {
            val fields = metadata.value?.contents as? Map<*, *>
            return (fields?.get(TileRegionSummary.CORRIDOR_HASH_KEY) as? Value)?.contents as? String ?: ""
        }

        /** An interrupted pack persists and still names its style, so only a complete one counts, by the regions' rule. */
        internal fun presentPacks(packs: List<StylePack>): Set<StylePackRequest> {
            val complete = packs.filter { isComplete(it.completedResourceCount, it.requiredResourceCount) }.map { it.styleURI }.toSet()
            return StylePackRequest.entries.filter { styleUri(it) in complete }.toSet()
        }

        /** Counts and bytes move on every progress tick of a save and are left out, so a reader that reloads on a signal never spins. */
        internal fun settled(regions: List<TileRegionSummary>): List<SettledRegion> =
            regions.map { SettledRegion(it.id, it.isComplete, it.corridorHash) }

        /** [TileRegionSummary.isComplete]'s rule, for the counts a style pack reports under the same names. */
        internal fun isComplete(completed: Long, required: Long): Boolean = required > 0 && completed >= required
    }
}

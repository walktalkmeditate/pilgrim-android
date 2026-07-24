// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * Owns the collective-route artifact: loads a catalog from disk at launch,
 * refreshes it from the CDN, publishes whichever is current. Ports iOS
 * `CollectiveRouteCatalogService.swift@9a418e4` (parity spec
 * `docs/parity/2026-07-23-port-route-catalog-service-u3.md`), structured
 * after [org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifestService].
 *
 * Three tiers, in precedence order: a catalog fetched this process with a
 * different content-version, the cached copy of the last fetch, the bundled
 * bootstrap asset. Every tier produces *a* catalog, so a fresh offline
 * install still rotates a daily route.
 */
@Singleton
class CollectiveRouteCatalogService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    @CollectiveRouteCatalogScope private val scope: CoroutineScope,
    @CollectiveRouteCatalogUrl private val catalogUrl: String,
    @CollectiveRouteBootstrapAsset private val bootstrapAssetPath: String,
) {
    /**
     * [CollectiveRouteCatalog.EMPTY] until the initial load lands, and no
     * surface may assume otherwise — `EMPTY` selects nothing, so lookups
     * render nothing rather than blocking.
     */
    private val _catalog = MutableStateFlow(CollectiveRouteCatalog.EMPTY)
    val catalog: StateFlow<CollectiveRouteCatalog> = _catalog.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val cacheFile: File by lazy { File(context.filesDir, CACHE_FILE_NAME) }

    /**
     * Completes when the init-time three-tier load finishes (whatever it
     * produced). [syncIfNeeded] awaits this before comparing versions.
     */
    private val _initialLoad = CompletableDeferred<Unit>()
    val initialLoad: Deferred<Unit> get() = _initialLoad

    init {
        // Init must stay cheap — Hilt may construct this on the main thread.
        // Disk reads + JSON decodes run on IO; iOS mirrors this with a
        // detached utility task (issue #42's launch-stall shape).
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { loadInitialCatalog() }
                // Same belt-and-braces as iOS's `guard service.catalog == nil`:
                // never stomp a catalog a sync already published.
                if (_catalog.value == CollectiveRouteCatalog.EMPTY) {
                    _catalog.value = loaded
                }
            } finally {
                _initialLoad.complete(Unit)
            }
        }
    }

    /**
     * Non-reactive snapshot read of the current catalog — UI surfaces must
     * consume the [catalog] StateFlow instead (the Stage 5-G staleness class).
     */
    fun dailyLine(epochMillis: Long, collectiveKm: Double?, units: UnitSystem): String? =
        _catalog.value.dailyLine(epochMillis, collectiveKm, units)

    /**
     * Anchored to the walk's own date, so reopening an old walk shows what it
     * showed the day it ended. Non-reactive snapshot read of the current
     * catalog — UI surfaces must consume the [catalog] StateFlow instead
     * (the Stage 5-G staleness class).
     */
    fun contributionLine(epochMillis: Long, walkKm: Double, units: UnitSystem): String? =
        _catalog.value.contributionLine(epochMillis, walkKm, units)

    /**
     * Fetch the published artifact; if its content-version differs from the
     * current catalog's, publish it and cache the served bytes. Inequality
     * rather than `>`: the version carries no ordering, so a curator
     * reverting to a prior artifact has to reach devices too.
     */
    fun syncIfNeeded() {
        if (!_isSyncing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                // Before the comparison, never after: a fast network response
                // would otherwise be overwritten by the bootstrap decode
                // still in flight.
                initialLoad.await()

                val body = fetchRemoteCatalog() ?: return@launch
                val remote = decodeRemoteCatalog(body)
                // An empty catalog is rejected like an undecodable one:
                // arrays are optional and elements decode lossily, so a bake
                // dropping a required field parses cleanly into nothing and
                // would cache dark.
                if (remote == null || remote.entries.isEmpty()) return@launch

                if (_catalog.value.version != remote.version) {
                    _catalog.value = remote
                    withContext(Dispatchers.IO) { saveLocalCatalog(body) }
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private suspend fun fetchRemoteCatalog(): String? =
        withContext(Dispatchers.IO) {
            try {
                // FORCE_NETWORK mirrors iOS's `.reloadIgnoringLocalCacheData`:
                // a curator rollback must land, never replay a stale cached
                // body. No-op today (the shared client has no cache) but
                // survives one gaining it.
                val request = Request.Builder()
                    .url(catalogUrl)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        Log.w(TAG, "catalog fetch non-200: ${response.code}")
                        return@use null
                    }
                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) {
                        Log.w(TAG, "catalog body empty")
                        return@use null
                    }
                    body
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "catalog fetch failed", t)
                null
            }
        }

    private fun decodeRemoteCatalog(body: String): CollectiveRouteCatalog? =
        try {
            CollectiveRouteCatalog.decode(body)
        } catch (t: Throwable) {
            Log.w(TAG, "undecodable remote catalog", t)
            null
        }

    /** Cache, then bundled bootstrap. */
    private fun loadInitialCatalog(): CollectiveRouteCatalog =
        readCachedCatalog() ?: readBootstrapCatalog()

    /**
     * An entry-less cached file is passed over rather than adopted — a build
     * shipped before the sync guard could have written one, and serving it
     * would shadow a working bootstrap for every offline launch. A corrupt
     * file costs one launch's freshness, not the whole feature, and stays on
     * disk until the next sync's atomic rewrite.
     */
    private fun readCachedCatalog(): CollectiveRouteCatalog? {
        if (!cacheFile.exists()) return null
        return try {
            CollectiveRouteCatalog.decode(cacheFile.readText())
                .takeIf { it.entries.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "corrupt cached catalog; falling back to bootstrap", t)
            null
        }
    }

    /**
     * Shipped builds must include the bootstrap asset so fresh offline
     * installs still rotate a route. `Log.wtf` ports iOS's
     * `assertionFailure` — loud in dev tooling, never a release crash.
     */
    private fun readBootstrapCatalog(): CollectiveRouteCatalog =
        try {
            val text = context.assets.open(bootstrapAssetPath).bufferedReader().use { it.readText() }
            CollectiveRouteCatalog.decode(text)
        } catch (t: Throwable) {
            Log.wtf(
                TAG,
                "Missing/undecodable $bootstrapAssetPath — verify the asset is a verbatim copy " +
                    "of the iOS bundled artifact (CollectiveRouteBundledArtifactTest guards it)",
                t,
            )
            CollectiveRouteCatalog.EMPTY
        }

    /**
     * Caches the exact bytes the CDN served rather than re-encoding the
     * decoded catalog: a round-trip would strip every field this app ignores
     * today, handing the next launch a thinner artifact than the one fetched.
     * Atomic tmp+rename so a partial write never corrupts the cache.
     */
    private fun saveLocalCatalog(body: String) {
        val tmp = File(cacheFile.parentFile, "$CACHE_FILE_NAME.tmp")
        try {
            tmp.writeText(body)
            if (!tmp.renameTo(cacheFile)) {
                Files.move(
                    tmp.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to save catalog; in-memory state retains it", t)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "CollectiveRouteCatalog"
        const val CACHE_FILE_NAME = "collective_routes.json"
    }
}

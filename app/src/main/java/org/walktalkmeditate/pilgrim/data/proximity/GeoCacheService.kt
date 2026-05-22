// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.proximity

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.walktalkmeditate.pilgrim.data.cairn.CachedCairn
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenSource
import org.walktalkmeditate.pilgrim.data.whisper.CachedWhisper
import org.walktalkmeditate.pilgrim.domain.Clock

/**
 * iOS parity `GeoCacheService.swift@db4196e`. Fetches nearby whispers
 * + cairns from the pilgrimapp.org API and persists them via
 * DataStore. Also owns the pending-placement queue (replayed at the
 * tail of every successful `fetchIfNeeded` call, matching iOS).
 *
 * Numerical constants — verbatim from iOS:
 *  - 50_000 m fetch radius (query param)
 *  - 10_000 m re-fetch threshold (distance moved from last center)
 *  - ETag in-memory only (cleared on process death — matches iOS)
 *  - whispers + cairns fetched in parallel via `async`
 *
 * Persistence keys must match iOS exactly: `geoCachedWhispers` and
 * `geoCachedCairns` and `pendingPlacements`. The bytes stored are
 * raw JSON arrays of the same shape iOS reads/writes.
 */
@Singleton
open class GeoCacheService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val deviceTokenStore: DeviceTokenSource,
    private val json: Json,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    private val _whispers = MutableStateFlow<List<CachedWhisper>>(emptyList())
    open val whispers: StateFlow<List<CachedWhisper>> = _whispers.asStateFlow()

    private val _cairns = MutableStateFlow<List<CachedCairn>>(emptyList())
    open val cairns: StateFlow<List<CachedCairn>> = _cairns.asStateFlow()

    /**
     * Append a just-placed whisper to the local cache so its map marker
     * appears immediately. iOS appends to `cachedWhispers` on a
     * successful placement; without this the marker only shows after the
     * next geo-cache refetch (which is gated on a ~10km move), so a
     * whisper the user just left never appears mid-walk. Idempotent on
     * `id` — a later refetch returning the same row won't duplicate it.
     */
    open fun addPlacedWhisper(whisper: CachedWhisper) {
        _whispers.update { current ->
            if (current.any { it.id == whisper.id }) current else current + whisper
        }
    }

    /**
     * Insert or bump a just-placed cairn. A new stone may start a new
     * cairn or increment an existing one's count; preserve the original
     * `createdAt` when updating (iOS keeps the first-stone timestamp).
     */
    open fun addOrUpdatePlacedCairn(cairn: CachedCairn) {
        _cairns.update { current ->
            val idx = current.indexOfFirst { it.id == cairn.id }
            if (idx >= 0) {
                current.toMutableList().also {
                    it[idx] = cairn.copy(createdAt = current[idx].createdAt ?: cairn.createdAt)
                }
            } else {
                current + cairn
            }
        }
    }

    // ETags are in-memory only — match iOS exactly. Re-fetch on every
    // process start. Both are cleared by `invalidateLastFetch()` so
    // a fresh walk start forces a network round-trip even if the
    // cache JSON has been hydrated from DataStore.
    private var whispersEtag: String? = null
    private var cairnsEtag: String? = null
    private var lastFetchLat: Double? = null
    private var lastFetchLon: Double? = null
    @Volatile private var hydrated: Boolean = false

    private suspend fun hydrateOnce() {
        // Lazy first-call hydration. Previously this ran in an init
        // block on a leaked `SupervisorJob() + Dispatchers.Default`
        // scope — each `GeoCacheService` construction leaked a coroutine
        // reading DataStore. In unit tests that built up across the
        // 1980+ test fleet and wedged the JVM at ~25min (CI timeout).
        // Lazy + idempotent on `hydrated` flag is safe: the first
        // `fetchIfNeeded` call hydrates before the network round-trip;
        // subsequent calls short-circuit.
        if (hydrated) return
        runCatching {
            val prefs = context.geoCacheStore.data.first()
            prefs[KEY_WHISPERS]?.let { raw ->
                _whispers.value = json.decodeFromString(
                    ListSerializer(CachedWhisper.serializer()),
                    raw,
                )
            }
            prefs[KEY_CAIRNS]?.let { raw ->
                _cairns.value = json.decodeFromString(
                    ListSerializer(CachedCairn.serializer()),
                    raw,
                )
            }
        }.onFailure { Log.w(TAG, "hydrate failed: ${it.message}") }
        hydrated = true
    }

    /**
     * iOS parity `GeoCacheService.swift:invalidateLastFetch@db4196e`.
     * Forces the next `fetchIfNeeded` call to issue a network request
     * even if the user has moved less than [REFETCH_THRESHOLD_M] from
     * the prior fetch center. Called at walk-bind setup so each walk
     * starts with a fresh cache.
     */
    open fun invalidateLastFetch() {
        lastFetchLat = null
        lastFetchLon = null
        whispersEtag = null
        cairnsEtag = null
    }

    /**
     * iOS parity `GeoCacheService.swift:fetchIfNeeded@db4196e`. Two
     * gates:
     *  1. If a prior `lastFetchCenter` exists AND we have moved less
     *     than [REFETCH_THRESHOLD_M] from it, return without a
     *     network call.
     *  2. Else fetch whispers + cairns in parallel; on success update
     *     the StateFlows and persist. ETag-aware: 304 returns leave
     *     the StateFlow alone.
     *
     * After the fetch (success OR 304), `syncPendingPlacements` fires
     * at the tail. Pending placement TTL pruning + replay match iOS.
     */
    open suspend fun fetchIfNeeded(latitude: Double, longitude: Double) {
        hydrateOnce()
        mutex.withLock {
            val prevLat = lastFetchLat
            val prevLon = lastFetchLon
            if (prevLat != null && prevLon != null) {
                val moved = approxMetersBetween(prevLat, prevLon, latitude, longitude)
                if (moved <= REFETCH_THRESHOLD_M) return@withLock
            }
            val (whispersOk, cairnsOk) = withContext(Dispatchers.IO) {
                val whispersDeferred = async { fetchWhispers(latitude, longitude) }
                val cairnsDeferred = async { fetchCairns(latitude, longitude) }
                val results = awaitAll(whispersDeferred, cairnsDeferred)
                results[0] to results[1]
            }
            // Only commit the fetch center when at least ONE side
            // succeeded (2xx or 304 — both mean the server is
            // reachable). A double-network-failure must NOT update
            // the center; otherwise the 10km re-fetch threshold
            // would suppress the next retry until the user moves
            // 10km, leaving them with an empty cache for the rest
            // of the walk. Reviewer flag — real bug.
            if (whispersOk || cairnsOk) {
                lastFetchLat = latitude
                lastFetchLon = longitude
            }
        }
        // Pending replay runs OUTSIDE the mutex so a pending POST
        // doesn't block the next fetch.
        syncPendingPlacements()
    }

    private suspend fun fetchWhispers(lat: Double, lon: Double): Boolean {
        val request = Request.Builder()
            .url("$BASE_URL/api/whispers?lat=$lat&lon=$lon&radius=$FETCH_RADIUS_M")
            .get()
            .apply { whispersEtag?.let { header("If-None-Match", it) } }
            .build()
        return try {
            httpClient.newCall(request).awaitResponse().use { response ->
                if (response.code == 304) return@use true
                if (!response.isSuccessful) {
                    Log.w(TAG, "whispers fetch HTTP ${response.code}")
                    return@use false
                }
                val bodyStr = response.body.string()
                val decoded = json.decodeFromString(
                    ListSerializer(CachedWhisper.serializer()),
                    bodyStr,
                )
                _whispers.value = decoded
                response.header("ETag")?.let { whispersEtag = it }
                context.geoCacheStore.edit { prefs -> prefs[KEY_WHISPERS] = bodyStr }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "whispers fetch network: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "whispers fetch decode: ${e.message}")
            false
        }
    }

    private suspend fun fetchCairns(lat: Double, lon: Double): Boolean {
        val request = Request.Builder()
            .url("$BASE_URL/api/cairns?lat=$lat&lon=$lon&radius=$FETCH_RADIUS_M")
            .get()
            .apply { cairnsEtag?.let { header("If-None-Match", it) } }
            .build()
        return try {
            httpClient.newCall(request).awaitResponse().use { response ->
                if (response.code == 304) return@use true
                if (!response.isSuccessful) {
                    Log.w(TAG, "cairns fetch HTTP ${response.code}")
                    return@use false
                }
                val bodyStr = response.body.string()
                val decoded = json.decodeFromString(
                    ListSerializer(CachedCairn.serializer()),
                    bodyStr,
                )
                _cairns.value = decoded
                response.header("ETag")?.let { cairnsEtag = it }
                context.geoCacheStore.edit { prefs -> prefs[KEY_CAIRNS] = bodyStr }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "cairns fetch network: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "cairns fetch decode: ${e.message}")
            false
        }
    }

    /**
     * iOS parity `GeoCacheService.swift:enqueuePending@db4196e`. Prune
     * stale entries, enforce 50-cap (refuse-new — NOT drop-oldest),
     * append the new placement. Persisted JSON array.
     */
    open suspend fun enqueuePending(placement: PendingPlacement) {
        val now = clock.now()
        context.geoCacheStore.edit { prefs ->
            val current = readPending(prefs[KEY_PENDING])
            val pruned = current.filter { now - it.timestampMs < PendingPlacement.TTL_MS }
            if (pruned.size >= PendingPlacement.MAX_QUEUE) return@edit
            val updated = pruned + placement
            prefs[KEY_PENDING] = json.encodeToString(
                ListSerializer(PendingPlacement.serializer()),
                updated,
            )
        }
    }

    /**
     * iOS parity `GeoCacheService.swift:syncPendingPlacements@db4196e`.
     * Prune stale, replay each survivor, drop on success. Failures
     * remain in the queue for a future replay. Idempotency is NOT
     * client-side — a POST that succeeded but lost its response will
     * replay and create a duplicate. iOS accepts this and so does
     * Android (per parity).
     */
    suspend fun syncPendingPlacements() {
        val now = clock.now()
        val surviving: List<PendingPlacement>
        context.geoCacheStore.data.first().let { prefs ->
            val current = readPending(prefs[KEY_PENDING])
            surviving = current.filter { now - it.timestampMs < PendingPlacement.TTL_MS }
        }
        if (surviving.isEmpty()) {
            // Write back the pruned-empty state so stale entries don't
            // linger across process restarts.
            context.geoCacheStore.edit { prefs -> prefs.remove(KEY_PENDING) }
            return
        }
        val stillPending = mutableListOf<PendingPlacement>()
        for (placement in surviving) {
            val ok = withContext(Dispatchers.IO) { replayPlacement(placement) }
            if (!ok) stillPending += placement
        }
        context.geoCacheStore.edit { prefs ->
            if (stillPending.isEmpty()) prefs.remove(KEY_PENDING) else {
                prefs[KEY_PENDING] = json.encodeToString(
                    ListSerializer(PendingPlacement.serializer()),
                    stillPending,
                )
            }
        }
    }

    private suspend fun replayPlacement(placement: PendingPlacement): Boolean {
        // Inject coordinates from the stored placement, regardless of
        // what's in the payload (iOS pattern).
        val injectedJson = injectCoords(
            placement.payload,
            placement.latitude,
            placement.longitude,
        ) ?: return false
        val endpoint = when (placement.type) {
            PendingPlacement.PlacementType.Whisper -> "/api/whispers"
            PendingPlacement.PlacementType.Stone -> "/api/cairns"
        }
        val token = deviceTokenStore.getToken()
        val request = Request.Builder()
            .url("$BASE_URL$endpoint")
            .post(injectedJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-Device-Token", token)
            .build()
        return try {
            httpClient.newCall(request).awaitResponse().use { it.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            false
        }
    }

    private fun injectCoords(payload: String, lat: Double, lon: Double): String? {
        // Decode -> map -> re-encode. Defensive on malformed payloads.
        return runCatching {
            val node = json.parseToJsonElement(payload).let { it as? kotlinx.serialization.json.JsonObject }
                ?: return null
            val rebuilt = buildMap<String, kotlinx.serialization.json.JsonElement> {
                node.forEach { (k, v) ->
                    if (k != "latitude" && k != "longitude") put(k, v)
                }
                put("latitude", kotlinx.serialization.json.JsonPrimitive(lat))
                put("longitude", kotlinx.serialization.json.JsonPrimitive(lon))
            }
            json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.JsonObject(rebuilt),
            )
        }.getOrNull()
    }

    private fun readPending(raw: String?): List<PendingPlacement> {
        if (raw == null) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PendingPlacement.serializer()), raw)
        }.getOrElse { emptyList() }
    }

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response) { _, _, _ -> runCatching { response.close() } }
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }
            })
            cont.invokeOnCancellation { runCatching { cancel() } }
        }

    private fun approxMetersBetween(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        // Equirectangular approximation — same as iOS GeoCacheService
        // for the 10km threshold check. Accurate to within a few %
        // at city scales; the threshold doesn't need haversine.
        val dLat = (lat2 - lat1) * METERS_PER_DEGREE_LAT
        val dLon = (lon2 - lon1) * METERS_PER_DEGREE_LAT *
            cos(((lat1 + lat2) / 2.0) * Math.PI / 180.0)
        return sqrt(dLat * dLat + dLon * dLon)
    }

    companion object {
        const val BASE_URL = "https://walk.pilgrimapp.org"
        const val FETCH_RADIUS_M = 50_000
        const val REFETCH_THRESHOLD_M = 10_000.0
        private const val METERS_PER_DEGREE_LAT = 111_000.0
        private const val TAG = "GeoCacheService"
        private val KEY_WHISPERS = stringPreferencesKey("geoCachedWhispers")
        private val KEY_CAIRNS = stringPreferencesKey("geoCachedCairns")
        private val KEY_PENDING = stringPreferencesKey("pendingPlacements")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private val Context.geoCacheStore: DataStore<Preferences> by preferencesDataStore(
    name = "geo_cache",
)

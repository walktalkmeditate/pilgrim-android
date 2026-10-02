// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.walktalkmeditate.pilgrim.data.honor.WayImporter
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.stayingOnTheHost
import org.walktalkmeditate.pilgrim.di.PilgrimageCatalogHttpClient
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.honor.prefixCharacters

/**
 * The open-pilgrimages index, read from the dataset's default branch at
 * most once a day: iOS `PilgrimageCatalogService`
 * (`PilgrimageCatalogService.swift:72-379@7c200bf`, pilgrimage-stage spec
 * P1 §2–§3, §7–§10). It says which routes exist, how big they are, and
 * which release to pin their packages to; it never installs one.
 *
 * Nothing runs at construction, and nothing fetches until a screen asks:
 * with the release flag off, nothing asks (P1 §13 gap 14). [catalog] is
 * iOS's `@Published catalog`, set on every successful return and never
 * cleared, so a catalog reopened in the same process lists at once while
 * it loads again (P4 §3.4).
 *
 * Its own client, with no HTTP cache, where iOS's ephemeral session keeps a
 * memory cache that honours the CDN's 7-day `max-age` (P1 A1,
 * pilgrim-ios #121): every load past 24 h, and every "try again", reaches
 * the network. Redirects stay on the CDN (P1 A2, owner decision 6).
 *
 * The cache and the route previews live in `filesDir/Pilgrimages/`, which
 * a device transfer carries as iOS's backup carries its Application
 * Support copy (owner decision 5).
 *
 * Nothing is logged: not a URL, not a route, not a decode error, whose
 * kotlinx message quotes the input.
 */
@Singleton
class PilgrimageCatalogService internal constructor(
    private val client: OkHttpClient,
    /** The CDN every fetch goes to; a test's server stands in with the same paths. */
    private val cdn: HttpUrl,
    resolveDirectory: () -> File,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @PilgrimageCatalogHttpClient client: OkHttpClient,
        clock: Clock,
    ) : this(
        client = client,
        cdn = CDN_ORIGIN.toHttpUrl(),
        resolveDirectory = { File(context.filesDir, DIRECTORY) },
        clock = clock,
        ioDispatcher = Dispatchers.IO,
    )

    /** Resolved on first use and created only by a write. */
    internal val directory: File by lazy(resolveDirectory)

    private val cacheFile: File get() = File(directory, CACHE_FILE)

    private val _catalog = MutableStateFlow<PilgrimageCatalog?>(null)
    val catalog: StateFlow<PilgrimageCatalog?> = _catalog.asStateFlow()

    /**
     * One load at a time, the 24 h check inside the lock, so a load that
     * waited on another reads the cache it wrote (P1 A4). iOS lets two
     * loads interleave and both fetch.
     */
    private val loadLock = Mutex()

    /**
     * iOS `load(force:)` (`PilgrimageCatalogService.swift:153-172@7c200bf`,
     * P1 §8): the cache read from disk every time; served while under a
     * day old unless [force]; otherwise fetched, parsed, cached and served.
     * A fetch or parse that fails serves the cache at any age, a forced
     * load included, with no error. A parse that keeps no route is a
     * success, cached like any other.
     *
     * Freshness is the signed wall-clock difference, so a fetch time in the
     * future (a clock set back) reads as fresh until the clock passes it by
     * a day (P1 §8, flow gap 11).
     *
     * Cancellation is rethrown, never served from the cache (P1 A3).
     *
     * @throws PilgrimageException [PilgrimageError.CATALOG_UNREACHABLE], only
     *   when the fetch failed and no cache could be read.
     */
    suspend fun load(force: Boolean = false): PilgrimageCatalog = withContext(ioDispatcher) {
        loadLock.withLock {
            val cached = readCache()
            if (!force && cached != null && clock.now() - cached.fetchedAt < CACHE_LIFETIME_MILLIS) {
                return@withLock published(cached.catalog)
            }
            // iOS's `catch`: any refusal falls back to the cache, or to the one error.
            val fresh = try {
                parse(fetch(INDEX, MAX_INDEX_BYTES.toLong()))
            } catch (e: PilgrimageException) {
                null
            }
            when {
                fresh != null -> {
                    writeCache(Cached(fetchedAt = clock.now(), catalog = fresh))
                    published(fresh)
                }
                cached != null -> published(cached.catalog)
                else -> throw unreachable()
            }
        }
    }

    /**
     * iOS `routePreview(entry:release:)` (`PilgrimageCatalogService.swift:179-192@7c200bf`,
     * P1 §9): a route's `route.json` before its package is downloaded, so
     * the route page can list the stages on offer. A preview cached for
     * this route and release is served as it is, with no network and no
     * second entry check (it passed one before it was written). Otherwise
     * the catalog's own fetch under the route cap, the importer's checks,
     * then the entry check the download makes too (P1 C3), and the bytes
     * as they arrived written beside the catalog. Nothing expires or
     * deletes a preview (pilgrim-ios #119, matched).
     *
     * @throws PilgrimageException [PilgrimageError.NOT_WALKABLE] for a bad
     *   release or route id, a file the importer refuses, or a route that
     *   disagrees with [entry]; [PilgrimageError.CATALOG_UNREACHABLE] for any
     *   fetch failure, a 404 included (pilgrim-ios #121, matched).
     */
    suspend fun routePreview(entry: PilgrimageCatalogEntry, release: String): PilgrimageRoute = withContext(ioDispatcher) {
        readRoutePreview(entry.id, release)?.let { return@withContext it }
        val url = packageUrl(release, entry.id, ROUTE_FILE) ?: throw PilgrimageException(PilgrimageError.NOT_WALKABLE)
        val data = fetch(url, PilgrimageWayImporter.MAX_ROUTE_BYTES.toLong())
        val route = PilgrimageWayImporter.route(from = data)
        if (route.id != entry.id || route.stageCount != entry.stageCount || route.stages.size != entry.stageCount) {
            throw PilgrimageException(PilgrimageError.NOT_WALKABLE)
        }
        routePreviewFile(entry.id, release)?.let { writeQuietly(it, data) }
        route
    }

    private fun published(catalog: PilgrimageCatalog): PilgrimageCatalog {
        _catalog.value = catalog
        return catalog
    }

    /**
     * iOS `fetch(_:cap:session:)` (`PilgrimageCatalogService.swift:223-239@7c200bf`,
     * P1 §3.1): HTTP 200 only, the declared length checked before the
     * body (`<=` passes), then the bytes counted as they arrive (`>`
     * refuses), so exactly [cap] bytes pass. Every failure is
     * [PilgrimageError.CATALOG_UNREACHABLE]; the call is cancelled with the
     * caller.
     */
    private suspend fun fetch(url: HttpUrl, cap: Long): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request(onCdn(url)))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(unreachable()))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        Result.success(response.use { readCapped(it, cap) })
                    } catch (e: Exception) {
                        // iOS's `catch { throw PilgrimageError.catalogUnreachable }`: anything else mid-fetch.
                        Result.failure(unreachable())
                    }
                    continuation.resumeWith(result)
                }
            },
        )
    }

    /** The same path on this service's CDN: the production CDN itself, or a test's server. */
    private fun onCdn(url: HttpUrl): HttpUrl = url.newBuilder().scheme(cdn.scheme).host(cdn.host).port(cdn.port).build()

    /**
     * iOS `readCache` (`PilgrimageCatalogService.swift:364-372@7c200bf`): a
     * file that is missing, unreadable, or not exactly this format is no
     * cache at all, never an error.
     */
    private fun readCache(): Cached? = try {
        CACHE_JSON.decodeFromString(Cached.serializer(), cacheFile.readText())
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The parsed catalog, not the served bytes (P1 §8, C13). */
    private fun writeCache(cached: Cached) {
        writeQuietly(cacheFile, CACHE_JSON.encodeToString(Cached.serializer(), cached).toByteArray(Charsets.UTF_8))
    }

    /**
     * iOS `routePreviewURL` (`PilgrimageCatalogService.swift:194-199@7c200bf`):
     * keyed by release as well as route, so an older build's preview never
     * stands in for the stages the current index names; null unless both
     * pass their rules, so no path is built from a string the dataset chose.
     */
    private fun routePreviewFile(routeId: String, release: String): File? {
        if (!WayStore.isValidRouteId(routeId) || !isValidRelease(release)) return null
        return File(directory, "route-$routeId-$release.json")
    }

    private fun readRoutePreview(routeId: String, release: String): PilgrimageRoute? {
        val file = routePreviewFile(routeId, release) ?: return null
        return try {
            PilgrimageWayImporter.route(from = file.readBytes())
        } catch (e: IOException) {
            null
        } catch (e: PilgrimageException) {
            null
        }
    }

    /**
     * A temp file, then a rename over [target], so a reader never meets half
     * a file (`CollectiveRouteCatalogService`'s write). A write that fails is
     * dropped, as iOS's `try? … .atomic` drops it: what was fetched is still
     * returned, and the next load asks again.
     */
    private fun writeQuietly(target: File, bytes: ByteArray) {
        val temp = File(directory, "${target.name}$TEMP_SUFFIX")
        try {
            directory.mkdirs()
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temp.delete()
        }
    }

    /** iOS's `Cached`, with [fetchedAt] in epoch milliseconds where iOS's ISO date keeps whole seconds (P1 A5). */
    @Serializable
    private class Cached(val fetchedAt: Long, val catalog: PilgrimageCatalog)

    companion object {

        /**
         * `@main`, never a tag (`PilgrimageCatalogService.swift:80-86@7c200bf`):
         * jsDelivr caches a tag URL for good, so a moved tag keeps serving
         * the index it first saw.
         */
        const val INDEX_URL = "https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@main/index.json"

        /** The host every URL here names, and the only one a redirect may reach (owner decision 6). */
        const val CDN_ORIGIN = "https://cdn.jsdelivr.net/"

        const val MAX_INDEX_BYTES = 256 * 1024
        const val CACHE_LIFETIME_MILLIS = 24L * 3600 * 1000
        const val MAX_DISTANCE_KM = 10_000.0
        const val MAX_STAGE_COUNT = 200
        const val MAX_PACKAGE_BYTES = 50 * 1024 * 1024

        /** A stage can't plausibly carry fifty curated places; beyond that is a broken report. */
        const val MAX_PLACES_PER_STAGE = 50.0

        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 30L

        private const val PACKAGE_BASE = "https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages"
        private const val DIRECTORY = "Pilgrimages"
        private const val CACHE_FILE = "catalog.json"
        private const val ROUTE_FILE = "route.json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val READ_CHUNK_BYTES = 8_192L
        private const val HTTP_OK = 200

        private val INDEX = INDEX_URL.toHttpUrl()

        /* iOS's patterns with literal ASCII classes; [Regex.matches] anchors the whole input as `\A…\z` does. */
        private val RELEASE = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")
        private val PACKAGE_FILE = Regex("route\\.json|stage-[0-9]{2,3}\\.json")
        private val LANGUAGE_CODE = Regex("[a-z]{2,3}")

        /** The cache's own format, read strictly: a key missing or unknown, or a wrong type, and the file is no cache. */
        private val CACHE_JSON = Json { explicitNulls = false }

        /**
         * iOS `packageURL(release:routeId:file:)` (`PilgrimageCatalogService.swift:137-142@7c200bf`):
         * a package file pinned to the exact tag the index named. The release
         * and the route id pass their rules, and the file name comes from a
         * closed set, before any URL is built (P1 §2, C12). Built as one
         * string, as iOS builds it, so the `@` stays inside its segment.
         */
        fun packageUrl(release: String, routeId: String, file: String): HttpUrl? {
            if (!isValidRelease(release) || !WayStore.isValidRouteId(routeId) || !PACKAGE_FILE.matches(file)) return null
            return "$PACKAGE_BASE@$release/routes/$routeId/ways/$file".toHttpUrlOrNull()
        }

        /** iOS `isValidRelease` (`PilgrimageCatalogService.swift:144-146@7c200bf`): `\Av[0-9]+\.[0-9]+\.[0-9]+\z`. */
        fun isValidRelease(release: String): Boolean = RELEASE.matches(release)

        /**
         * iOS's ephemeral catalog session (`PilgrimageCatalogService.swift:110-115@7c200bf`):
         * a stalled request times out after 15 s and the whole fetch after
         * 30 s, nothing is retried, and OkHttp keeps no cache unless one is
         * set. A redirect is followed only on [cdn]'s scheme, host and port.
         */
        fun httpClient(cdn: HttpUrl): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .stayingOnTheHost(cdn)
            .build()

        /** A plain GET, as iOS's `session.bytes(from:)` sends. */
        internal fun request(url: HttpUrl): Request = Request.Builder().url(url).build()

        private fun readCapped(response: Response, cap: Long): ByteArray {
            if (response.code != HTTP_OK) throw unreachable()
            val body = response.body
            if (body.contentLength() > cap) throw unreachable()
            val source = body.source()
            val buffer = Buffer()
            while (source.read(buffer, READ_CHUNK_BYTES) != -1L) {
                if (buffer.size > cap) throw unreachable()
            }
            return buffer.readByteArray()
        }

        /**
         * iOS `parse` (`PilgrimageCatalogService.swift:281-312@7c200bf`, P1 §7):
         * the whole index is refused when it doesn't decode, a row that
         * doesn't decode included (P1 C8, pilgrim-ios #121, matched), or its
         * release fails the tag rule. A row that decodes but fails a rule is
         * dropped. A repeated id keeps its first valid row. With no
         * `pilgrimages` key, every route sits in one group under no header.
         *
         * kotlinx refuses `1e400` as it reads, as Foundation does, so a
         * `1e400` anywhere fails the whole index on both platforms; an
         * integer written `2.0` fails it on Android only, and a quoted number
         * reads on Android only (P1 A9, not emulated).
         *
         * @throws PilgrimageException [PilgrimageError.CATALOG_UNREACHABLE].
         */
        internal fun parse(data: ByteArray): PilgrimageCatalog {
            val file = try {
                WayImporter.decodeWire(IndexFile.serializer(), data)
            } catch (e: IllegalArgumentException) {
                throw unreachable()
            } catch (e: CharacterCodingException) {
                throw unreachable()
            }
            if (!isValidRelease(file.release)) throw unreachable()
            val seenIds = HashSet<String>()
            val routes = file.routes.mapNotNull(::entry).filter { seenIds.add(it.id) }
            val pilgrimages = file.pilgrimages ?: return PilgrimageCatalog(file.release, routes)
            return PilgrimageCatalog(file.release, routes, grouped(routes, pilgrimages))
        }

        /**
         * One row, or null when it fails a rule, in iOS's order (P1 §7.2):
         * `ways` present, the slug, a finite distance in [0, 10,000], stages
         * in [1, 200], bytes in [0, 50 MiB), a finite density in [0, 50] when
         * given, and a name. Numbers are checked as [Long] before they
         * narrow (P1 A7). Names and labels are cut, never trimmed.
         */
        private fun entry(row: IndexFile.Route): PilgrimageCatalogEntry? {
            val ways = row.ways ?: return null
            val inRange = WayStore.isValidRouteId(row.id) &&
                row.distanceKm.isFinite() && row.distanceKm in 0.0..MAX_DISTANCE_KM &&
                ways.stageCount in 1L..MAX_STAGE_COUNT &&
                ways.bytes in 0L until MAX_PACKAGE_BYTES &&
                (ways.placesPerStage?.let { it.isFinite() && it in 0.0..MAX_PLACES_PER_STAGE } ?: true)
            if (!inRange) return null
            val names = localeNames(row.name)
            val display = displayName(names) ?: return null
            return PilgrimageCatalogEntry(
                id = row.id,
                name = display.prefixCharacters(PilgrimageWayImporter.MAX_STAGE_NAME_CHARACTERS),
                names = names.mapValues { (_, name) -> name.prefixCharacters(PilgrimageWayImporter.MAX_STAGE_NAME_CHARACTERS) },
                country = row.country?.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
                region = row.region?.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
                distanceKm = row.distanceKm,
                tradition = row.tradition?.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
                stageCount = ways.stageCount.toInt(),
                bytes = ways.bytes.toInt(),
                placesPerStage = ways.placesPerStage ?: 0.0,
                sparse = ways.sparse ?: false,
            )
        }

        /** Only `[a-z]{2,3}` keys; values kept as they are, and never capped in number (P1 §7.3). */
        private fun localeNames(raw: Map<String, String>): Map<String, String> = raw.filterKeys { LANGUAGE_CODE.matches(it) }

        /**
         * `en` whenever the key is there, an empty value included, else the
         * value of the alphabetically first key (P1 C9). The keys are ASCII
         * by now, so the natural order is Swift's `<`.
         */
        private fun displayName(names: Map<String, String>): String? = names["en"] ?: names.toSortedMap().values.firstOrNull()

        /**
         * iOS `grouped(_:under:)` (`PilgrimageCatalogService.swift:328-353@7c200bf`,
         * P1 §7.4): groups in the index's pilgrimage order, each holding its
         * sections in walking order. A pilgrimage with an empty id or no
         * readable name is skipped before it claims anything; a section is
         * taken only if it names a listed route nothing has claimed yet; a
         * pilgrimage left with nothing is dropped. What no pilgrimage claimed
         * trails under no header, so every route appears exactly once.
         */
        private fun grouped(routes: List<PilgrimageCatalogEntry>, pilgrimages: List<IndexFile.Pilgrimage>): List<PilgrimageGroup> {
            val byId = routes.associateBy { it.id }
            val claimed = HashSet<String>()
            val groups = pilgrimages.mapNotNullTo(ArrayList()) { pilgrimage ->
                if (pilgrimage.id.isEmpty()) return@mapNotNullTo null
                val name = displayName(localeNames(pilgrimage.name)) ?: return@mapNotNullTo null
                val entries = pilgrimage.sections.mapNotNull { section -> byId[section]?.takeIf { claimed.add(section) } }
                if (entries.isEmpty()) return@mapNotNullTo null
                PilgrimageGroup(
                    id = pilgrimage.id,
                    name = name.prefixCharacters(PilgrimageWayImporter.MAX_STAGE_NAME_CHARACTERS),
                    entries = entries,
                )
            }
            val loose = routes.filter { it.id !in claimed }
            if (loose.isNotEmpty()) groups += PilgrimageGroup(id = "", name = null, entries = loose)
            return groups
        }

        private fun unreachable() = PilgrimageException(PilgrimageError.CATALOG_UNREACHABLE)
    }
}

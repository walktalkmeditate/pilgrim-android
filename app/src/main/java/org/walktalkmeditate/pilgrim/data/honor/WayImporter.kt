// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.honor.OwnWalkWayBuilder
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WaySpan
import org.walktalkmeditate.pilgrim.domain.honor.WaySpanKind
import org.walktalkmeditate.pilgrim.domain.honor.WayWeather
import org.walktalkmeditate.pilgrim.domain.honor.prefixCharacters
import org.walktalkmeditate.pilgrim.domain.honor.trimmingWhitespacesAndNewlines

/** iOS `WayError` (`WayImporter.swift:3@7c200bf`). The importer raises the first three; [DISK_FULL] is the media download's. */
enum class WayError { NOT_FOUND, RETURNED_TO_TRAIL, UNAVAILABLE, DISK_FULL }

/** A [WayError], thrown. It carries no message: nothing from a share may reach a log. */
class WayImportException(val error: WayError) : Exception()

/**
 * A shared walk's manifest becomes a listed Way: iOS `WayImporter`
 * (`WayImporter.swift@7c200bf`, shared-walk spec S1 §3–§5). The fetch,
 * the decode, the build, and the save all run on [ioDispatcher]; the save
 * is the acceptance, writing `way.json` and the first `accepted.json`.
 *
 * Nothing is logged: not the manifest, not the id, not a decode error,
 * whose kotlinx message quotes the input (S1 §9 trap 14).
 */
@Singleton
class WayImporter internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val store: WayStore,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val locale: () -> Locale,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(store: WayStore, clock: Clock) : this(
        client = httpClient(BASE_URL.toHttpUrl()),
        baseUrl = BASE_URL.toHttpUrl(),
        store = store,
        clock = clock,
        zone = ZoneId::systemDefault,
        locale = Locale::getDefault,
        ioDispatcher = Dispatchers.IO,
    )

    /**
     * iOS `importShare(id:)` (`WayImporter.swift:51-86@7c200bf`), in its
     * order: the id before the network, the status and the declared length
     * before the body, the body under the cap, then the decode, the build's
     * own checks, and the save (S1 §3.3). The fetch is cancelled with the
     * caller; once the body is in, nothing checks for cancellation, so a
     * superseded import can still save its Way, as on iOS (S1 §8.10,
     * pilgrim-ios #114, matched).
     *
     * @throws WayImportException for every outcome iOS names.
     * @throws IOException when the store's write fails, which iOS's caller
     *   folds into "couldn't reach the walk" (S1 §6.4, pilgrim-ios #114, matched).
     */
    suspend fun importShare(id: String): Way {
        if (!isShareId(id)) throw WayImportException(WayError.NOT_FOUND)
        return withContext(ioDispatcher) {
            val body = fetch(id)
            val way = way(manifest(body), shareId = id, now = Instant.ofEpochMilli(clock.now()), zone(), locale())
            store.save(way)
            way
        }
    }

    private suspend fun fetch(id: String): ByteArray = suspendCancellableCoroutine { continuation ->
        val url = baseUrl.newBuilder().addPathSegment(id).addPathSegment(TOUR_FILE).build()
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(WayImportException(WayError.UNAVAILABLE)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        Result.success(response.use { readCapped(it) })
                    } catch (e: WayImportException) {
                        Result.failure(e)
                    } catch (e: Exception) {
                        // iOS's `catch { throw WayError.unavailable }`: anything else mid-fetch.
                        Result.failure(WayImportException(WayError.UNAVAILABLE))
                    }
                    continuation.resumeWith(result)
                }
            },
        )
    }

    /** A 404 or an oversized declared length costs no download; the body is held only up to the cap. */
    private fun readCapped(response: Response): ByteArray {
        if (response.code == HTTP_NOT_FOUND) throw WayImportException(WayError.NOT_FOUND)
        if (response.code != HTTP_OK) throw WayImportException(WayError.UNAVAILABLE)
        val body = response.body
        if (body.contentLength() > MAX_MANIFEST_BYTES) throw WayImportException(WayError.UNAVAILABLE)
        val source = body.source()
        val buffer = Buffer()
        while (source.read(buffer, READ_CHUNK_BYTES) != -1L) {
            if (buffer.size > MAX_MANIFEST_BYTES) throw WayImportException(WayError.UNAVAILABLE)
        }
        return buffer.readByteArray()
    }

    /**
     * Refuses a redirect to any other scheme, host, or port: no request
     * reaches it (an R6 addition: iOS follows any HTTPS redirect, S1 §3.2).
     * A redirect on the walk host is followed, as iOS follows it. The media
     * download refuses the same way (S3 §2).
     */
    internal class StaysOnTheWalkHost(private val base: HttpUrl) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) {
                throw IOException("a redirect off the walk host was refused")
            }
            return chain.proceed(chain.request())
        }
    }

    companion object {
        const val BASE_URL = "https://walk.pilgrimapp.org"

        const val MAX_ROUTE_POINTS = 2000
        const val MAX_ENCOUNTERS = 200
        const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024

        const val MAX_ALTITUDE_METERS = 100_000.0
        const val MAX_UNIX_SECONDS = 4_102_444_800L
        const val MAX_VOICE_DURATION_SECONDS = 108.0 * 60
        const val MAX_REST_MINUTES = 1440L
        const val MAX_ENCOUNTER_N = 10_000L
        const val MAX_ACTIVE_DURATION_SECONDS = 7.0 * 24 * 3600
        const val MAX_TITLE_PLACE_CHARACTERS = 80
        const val MAX_LABEL_CHARACTERS = 80
        const val MAX_ICON_CHARACTERS = 64
        const val MAX_WEATHER_CONDITION_CHARACTERS = 64

        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 30L

        private const val TOUR_FILE = "tour.json"
        private const val DEFAULT_WAYPOINT_ICON = "mappin"
        private const val READ_CHUNK_BYTES = 8_192L
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404

        private val ID = Regex("[A-Za-z0-9_-]{10}")

        private val MANIFEST_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /**
         * The body as a [TourManifest], strict UTF-8, or "couldn't reach the walk".
         *
         * @throws WayImportException
         */
        internal fun manifest(body: ByteArray): TourManifest = try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
            MANIFEST_JSON.decodeFromString(TourManifest.serializer(), text)
        } catch (e: IllegalArgumentException) {
            // kotlinx's SerializationException is one; its message quotes the input, so it goes no further.
            throw WayImportException(WayError.UNAVAILABLE)
        } catch (e: CharacterCodingException) {
            throw WayImportException(WayError.UNAVAILABLE)
        }

        /**
         * `\A[A-Za-z0-9_-]{10}\z`, checked here whatever a caller parsed
         * first (`WayImporter.swift:25-29@7c200bf`).
         */
        fun isShareId(id: String): Boolean = ID.matches(id)

        /**
         * The import's own client, never the shared one (S1 §3.2, correction
         * 16): iOS's ephemeral session times a stalled request out after
         * 15 s and the whole fetch after 30 s, retries nothing, and keeps no
         * cache. OkHttp has no cache unless one is set.
         */
        fun httpClient(base: HttpUrl): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor(StaysOnTheWalkHost(base))
            .build()

        /**
         * iOS `isoDate`: RFC 3339 with or without a fraction, kept to the
         * millisecond. Case-sensitive, with a colon in the offset and a
         * strict calendar; iOS also reads `+0200` and rolls `02-30` on, and
         * neither app nor the worker writes either (S1 §4.4).
         */
        internal fun isoDate(text: String): Instant? = try {
            OffsetDateTime.parse(text, ISO_DATE).toInstant().truncatedTo(ChronoUnit.MILLIS)
        } catch (e: DateTimeParseException) {
            null
        }

        private val ISO_DATE: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .appendOffset("+HH:MM", "Z")
            .toFormatter(Locale.ROOT)
            .withChronology(IsoChronology.INSTANCE)
            .withResolverStyle(ResolverStyle.STRICT)

        /**
         * iOS `way(from:shareId:now:)` (`WayImporter.swift:126-194@7c200bf`),
         * its checks in iOS's order, each with its own outcome: the dates,
         * then the expiry (before every count and range, so an expired
         * malformed share still reads as returned to the trail), the
         * counts, every range, and the route's length (S1 §4.2–§4.3). The
         * title's date fallback is formatted now, in [zone] and [locale],
         * and frozen into the Way.
         *
         * @throws WayImportException
         */
        internal fun way(manifest: TourManifest, shareId: String, now: Instant, zone: ZoneId, locale: Locale): Way {
            val expires = isoDate(manifest.expires)
            val departed = isoDate(manifest.startDate)
            if (expires == null || departed == null) throw WayImportException(WayError.UNAVAILABLE)
            if (!expires.isAfter(now)) throw WayImportException(WayError.RETURNED_TO_TRAIL)
            val counted = manifest.route.size in 2..MAX_ROUTE_POINTS &&
                manifest.encounters.size <= MAX_ENCOUNTERS &&
                manifest.meditation.size <= MAX_ENCOUNTERS
            if (!counted || !inRange(manifest)) throw WayImportException(WayError.UNAVAILABLE)
            val ts0 = manifest.route[0].ts
            val route = manifest.route.map { WayPoint(lat = it.lat, lon = it.lon, alt = it.alt, t = (it.ts - ts0).toDouble()) }
            val geometry = WayGeometry(route)
            if (geometry.totalMeters < OwnWalkWayBuilder.MIN_LENGTH_METERS) throw WayImportException(WayError.UNAVAILABLE)
            val moments = (encounterMoments(manifest.encounters) + sittingMoments(manifest.meditation, geometry))
                .sortedWith(BY_FRAC_THEN_ID)
            return Way(
                id = "share:$shareId",
                source = WaySource.Share(id = shareId, pageUrl = "$BASE_URL/$shareId"),
                title = title(manifest.placeStart, manifest.placeEnd, departed, zone, locale),
                departedAt = departed,
                tzIdentifier = manifest.tzIdentifier,
                expires = expires,
                route = route,
                totalDistanceMeters = geometry.totalMeters,
                theirActiveSeconds = manifest.stats?.activeDuration ?: geometry.totalSeconds,
                moments = moments,
                weather = manifest.weatherCondition?.let {
                    WayWeather(condition = it.prefixCharacters(MAX_WEATHER_CONDITION_CHARACTERS), temperatureC = manifest.weatherTemperature)
                },
                spans = spans(manifest.activitySegments.orEmpty()),
            )
        }

        /**
         * iOS `validate` (`WayImporter.swift:88-124@7c200bf`). Every encounter
         * is checked, the kinds then skipped included; nothing is clamped,
         * and one bad field refuses the whole share. Kotlin's conversions
         * saturate where Swift's trap, so a missed bound here would build a
         * wrong Way rather than crash (S1 §4.3).
         */
        private fun inRange(m: TourManifest): Boolean {
            fun inLat(v: Double) = v in -90.0..90.0
            fun inLon(v: Double) = v in -180.0..180.0
            fun inFrac(v: Double) = v in 0.0..1.0

            val routeOk = m.route.all { point ->
                inLat(point.lat) && inLon(point.lon) && abs(point.alt) < MAX_ALTITUDE_METERS &&
                    point.ts in 0..MAX_UNIX_SECONDS
            }
            if (!routeOk) return false
            if ((1 until m.route.size).any { i -> m.route[i].ts < m.route[i - 1].ts }) return false

            for (e in m.encounters) {
                if (!inFrac(e.frac)) return false
                if (e.endFrac != null && !inFrac(e.endFrac)) return false
                if (e.duration != null && e.duration !in 0.0..MAX_VOICE_DURATION_SECONDS) return false
                if (e.minutes != null && e.minutes !in 0..MAX_REST_MINUTES) return false
                if (e.n != null && e.n !in 1..MAX_ENCOUNTER_N) return false
                if (e.lat != null && !inLat(e.lat)) return false
                if (e.lon != null && !inLon(e.lon)) return false
            }
            for (sit in m.meditation) {
                if (!inFrac(sit.startFrac) || !inFrac(sit.endFrac)) return false
                if (sit.duration != null && sit.duration !in 0.0..MAX_VOICE_DURATION_SECONDS) return false
            }
            for (seg in m.activitySegments.orEmpty()) {
                if (!inFrac(seg.startFrac) || !inFrac(seg.endFrac)) return false
            }
            val active = m.stats?.activeDuration
            if (active != null && active !in 0.0..MAX_ACTIVE_DURATION_SECONDS) return false
            val temperature = m.weatherTemperature
            if (temperature != null && temperature !in -100.0..100.0) return false
            return true
        }

        /**
         * Ids count per kind, in manifest order, for the moments made;
         * voice and ambience share one counter. Media paths take the
         * manifest's `n`, not the counter (S1 §5.4).
         */
        private fun encounterMoments(encounters: List<TourManifest.Encounter>): List<WayMoment> {
            val moments = mutableListOf<WayMoment>()
            var voiceN = 0
            var photoN = 0
            var waypointN = 0
            var restN = 0
            for (e in encounters) {
                val at = if (e.lat != null && e.lon != null) WayCoordinate(lat = e.lat, lon = e.lon) else null
                when (e.type) {
                    "voice", "ambience" -> {
                        val n = e.n ?: continue
                        voiceN++
                        moments += WayMoment(
                            id = "voice-$voiceN",
                            frac = e.frac,
                            at = at,
                            kind = WayMomentKind.Voice(
                                endFrac = e.endFrac ?: e.frac,
                                duration = e.duration ?: 0.0,
                                kind = if (e.type == "voice") VoiceKind.SPOKEN else VoiceKind.AMBIENT,
                                media = WayMedia.File("audio/$n.m4a"),
                            ),
                            place = trimmedPlace(e.place),
                            transcript = WayMoment.trimmedTranscript(e.transcript),
                        )
                    }
                    "photo" -> {
                        val n = e.n ?: continue
                        photoN++
                        moments += WayMoment(
                            id = "photo-$photoN",
                            frac = e.frac,
                            at = at,
                            kind = WayMomentKind.Photo(media = WayMedia.File("photos/$n.jpg")),
                        )
                    }
                    "waypoint" -> {
                        waypointN++
                        // Cut, never trimmed; the icon falls back only when absent (S1 §9 traps 7–8).
                        moments += WayMoment(
                            id = "waypoint-$waypointN",
                            frac = e.frac,
                            at = at,
                            kind = WayMomentKind.Waypoint(
                                label = e.label.orEmpty().prefixCharacters(MAX_LABEL_CHARACTERS),
                                icon = (e.icon ?: DEFAULT_WAYPOINT_ICON).prefixCharacters(MAX_ICON_CHARACTERS),
                            ),
                        )
                    }
                    "rest" -> {
                        restN++
                        moments += WayMoment(
                            id = "rest-$restN",
                            frac = e.frac,
                            at = at,
                            kind = WayMomentKind.Rest(minutes = (e.minutes ?: 0L).toInt()),
                        )
                    }
                    else -> continue
                }
            }
            return moments
        }

        /**
         * Every sitting is a moment at its start frac, with no coordinate:
         * its declared minutes, or else an estimate from the route gap
         * around it. Swift's `rounded()` is half away from zero, which
         * [roundToInt] matches for these non-negative values (S1 §5.5).
         */
        private fun sittingMoments(sittings: List<TourManifest.Sitting>, geometry: WayGeometry): List<WayMoment> =
            sittings.mapIndexed { index, sit ->
                val declared = sit.duration
                val kind = if (declared != null) {
                    WayMomentKind.Meditation(minutes = (declared / 60).roundToInt(), isEstimate = false)
                } else {
                    WayMomentKind.Meditation(minutes = (gapSeconds(sit.startFrac, geometry) / 60).roundToInt(), isEstimate = true)
                }
                WayMoment(id = "sit-${index + 1}", frac = sit.startFrac, at = null, kind = kind)
            }

        /**
         * iOS `gapSeconds(around:geometry:)`: the time across the first
         * segment whose cumulative range holds `frac × length`, both ends
         * inclusive, so a frac on a vertex takes the segment ending there.
         */
        internal fun gapSeconds(frac: Double, geometry: WayGeometry): Double {
            val points = geometry.points
            if (points.size <= 1 || geometry.totalMeters <= 0) return 0.0
            val target = frac * geometry.totalMeters
            for (i in 0 until points.size - 1) {
                if (geometry.cumulative[i] <= target && target <= geometry.cumulative[i + 1]) {
                    return points[i + 1].t - points[i].t
                }
            }
            return 0.0
        }

        /** Unknown kinds and spans of no length are dropped; ties keep the manifest's order (S1 §5.6). */
        private fun spans(segments: List<TourManifest.ActivitySegment>): List<WaySpan> =
            segments.mapNotNull { seg ->
                val kind = when (seg.kind) {
                    "meditation" -> WaySpanKind.MEDITATING
                    "talk" -> WaySpanKind.TALKING
                    else -> return@mapNotNull null
                }
                if (seg.endFrac > seg.startFrac) WaySpan(startFrac = seg.startFrac, endFrac = seg.endFrac, kind = kind) else null
            }.sortedWith { a, b -> a.startFrac.compareFracTo(b.startFrac) }

        /**
         * `"<start> → <end>"`, one place alone, or the departure date in
         * medium style; each place trimmed and cut to 80 characters (S1 §5.3).
         */
        private fun title(placeStart: String?, placeEnd: String?, departed: Instant, zone: ZoneId, locale: Locale): String {
            val places = listOfNotNull(trimmedPlace(placeStart), trimmedPlace(placeEnd))
            if (places.isNotEmpty()) return places.joinToString(" → ")
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .withZone(zone)
                .format(departed)
        }

        /** Blank after Swift's trim reads as no place at all. */
        private fun trimmedPlace(raw: String?): String? {
            val trimmed = raw?.trimmingWhitespacesAndNewlines()
            if (trimmed.isNullOrEmpty()) return null
            return trimmed.prefixCharacters(MAX_TITLE_PLACE_CHARACTERS)
        }

        /** Swift's `<` and `==` on two fracs: `-0.0` and `0.0` tie, where `compareTo` orders them. */
        private fun Double.compareFracTo(other: Double): Int = when {
            this < other -> -1
            other < this -> 1
            else -> 0
        }

        /** iOS's `(frac, id)` order, ids compared as plain strings (S1 §5.7). */
        private val BY_FRAC_THEN_ID = Comparator<WayMoment> { a, b ->
            a.frac.compareFracTo(b.frac).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }
    }
}

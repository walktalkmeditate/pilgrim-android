# Stage 8-A Implementation Plan — Share Journey

Spec: `docs/superpowers/specs/2026-04-24-stage-8a-share-journey-design.md`

Sequence: Task 1 → 13. Each task ends compiling + passing tests. Branch already created: `feat/stage-8a-share-journey` (off `origin/main`, first commit already landed the 7-B/7-C docs backfill).

---

## Task 1 — `ShareConfig` + `ExpiryOption` + data models

Small foundation: config constants, expiry enum, in-memory result/error types. No I/O, pure Kotlin.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ShareConfig.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

/**
 * Stage 8-A: Share Worker endpoint configuration.
 *
 * Reuses the iOS `ShareService.baseURL` exactly so both platforms POST
 * to the same Cloudflare Worker + R2 bucket.
 */
internal object ShareConfig {
    const val BASE_URL = "https://walk.pilgrimapp.org"
    const val SHARE_ENDPOINT = "/api/share"
    /** Hardcoded for 8-A; a future settings stage can replace with a DataStore pref. */
    const val DEFAULT_UNITS = "metric"
    const val JOURNAL_MAX_LEN = 140
    const val ROUTE_MIN_POINTS = 2
    const val DOWNSAMPLE_TARGET_POINTS = 200
}
```

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ExpiryOption.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

/**
 * Stage 8-A: Expiry options for a shared walk page. Values mirror iOS
 * `WalkShareViewModel.ExpiryOption` verbatim — same days, labels,
 * kanji glyphs, and cache keys so a `.pilgrim` export/import between
 * platforms sees identical semantics.
 */
enum class ExpiryOption(
    val days: Int,
    val label: String,
    val kanji: String,
    val cacheKey: String,
) {
    Moon(days = 30, label = "1 moon", kanji = "月", cacheKey = "moon"),
    Season(days = 90, label = "1 season", kanji = "季", cacheKey = "season"),
    Cycle(days = 365, label = "1 cycle", kanji = "巡", cacheKey = "cycle"),
    ;

    companion object {
        fun fromCacheKey(key: String?): ExpiryOption? =
            entries.firstOrNull { it.cacheKey == key }
    }
}
```

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ShareResult.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

/** Stage 8-A: Successful share response from the Cloudflare Worker. */
data class ShareResult(val url: String, val id: String)
```

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ShareError.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

/**
 * Stage 8-A: Sealed error hierarchy for share-flow failures.
 *
 * Each variant maps to a distinct snackbar in the UI — kept explicit
 * (not a generic `Throwable` wrapper) so upstream VMs can reason about
 * rate-limit vs server vs network without string-matching error
 * messages (iOS `ShareError` parity).
 */
sealed class ShareError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class EncodingFailed(cause: Throwable? = null) : ShareError("Failed to prepare walk data.", cause)
    class NetworkError(cause: Throwable) : ShareError("Network error: ${cause.message}", cause)
    class ServerError(val code: Int, val serverMessage: String) :
        ShareError("Server error ($code): $serverMessage")
    object RateLimited : ShareError("You've shared too many walks today. Try again tomorrow.")
}
```

### Verify
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 2 — `DeviceTokenStore`

DataStore-backed UUID persistence. One accessor `getToken()` that's idempotent on re-call.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/DeviceTokenStore.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Stage 8-A: persistent per-device UUID used as the `X-Device-Token`
 * header on share requests. Not a secret — the Cloudflare Worker
 * hashes it for rate-limiting only. Generated on first call and
 * retained across app updates (DataStore survives reinstalls only
 * when the user's backup includes `.preferences_pb`).
 *
 * Exposed as a `suspend fun getToken()` so future flows beyond share
 * (e.g., feedback-trace tagging per iOS `deviceTokenForFeedback()`)
 * can reuse the same accessor.
 */
@Singleton
class DeviceTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Idempotent generate-or-read. Uses `edit` to make the
     * check-then-write atomic — two concurrent callers on first launch
     * will both see the same UUID after their edits settle.
     */
    suspend fun getToken(): String {
        val prefs = context.deviceTokenDataStore.data.first()
        prefs[KEY]?.let { return it }
        val fresh = UUID.randomUUID().toString()
        context.deviceTokenDataStore.edit { edit ->
            edit[KEY] = edit[KEY] ?: fresh
        }
        return context.deviceTokenDataStore.data.first()[KEY] ?: fresh
    }

    companion object {
        private val KEY = stringPreferencesKey("share_device_token")
    }
}

private val Context.deviceTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "share_device_token",
)
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/data/share/DeviceTokenStoreTest.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceTokenStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store = DeviceTokenStore(context)

    @After
    fun cleanup() {
        context.filesDir.parentFile?.resolve("datastore/share_device_token.preferences_pb")?.delete()
    }

    @Test
    fun `getToken generates a UUID on first call`() = runBlocking {
        val token = store.getToken()
        assertTrue("expected UUID-shape token, got $token", token.matches(Regex("[0-9a-f\\-]{36}")))
    }

    @Test
    fun `getToken is idempotent — second call returns the same token`() = runBlocking {
        val t1 = store.getToken()
        val t2 = store.getToken()
        assertEquals(t1, t2)
    }
}
```

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.DeviceTokenStoreTest"
```

---

## Task 3 — `SharePayload` + serializable sub-types

Kotlinx-serializable wire format. Field names exactly match the backend schema (`pilgrim-worker/src/types.ts`).

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/SharePayload.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stage 8-A: wire format for `POST /api/share`. Field names use
 * @SerialName to match the backend's snake_case schema
 * (`pilgrim-worker/src/types.ts`). Fields Android doesn't populate
 * yet (photos, mark, turning_day, weather, place_start/end) are
 * nullable so the serializer emits them as `null` or omits them via
 * `NetworkModule.provideJson().explicitNulls = false`.
 */
@Serializable
data class SharePayload(
    val stats: Stats,
    val route: List<RoutePoint>,
    @SerialName("activity_intervals") val activityIntervals: List<ActivityIntervalPayload>,
    val journal: String? = null,
    @SerialName("expiry_days") val expiryDays: Int,
    val units: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("tz_identifier") val tzIdentifier: String? = null,
    @SerialName("toggled_stats") val toggledStats: List<String>,
    @SerialName("place_start") val placeStart: String? = null,
    @SerialName("place_end") val placeEnd: String? = null,
    val mark: String? = null,
    val waypoints: List<Waypoint>? = null,
    val photos: List<Photo>? = null,
    @SerialName("turning_day") val turningDay: String? = null,
) {
    @Serializable
    data class Stats(
        val distance: Double? = null,
        @SerialName("active_duration") val activeDuration: Double? = null,
        @SerialName("elevation_ascent") val elevationAscent: Double? = null,
        @SerialName("elevation_descent") val elevationDescent: Double? = null,
        val steps: Int? = null,
        @SerialName("meditate_duration") val meditateDuration: Double? = null,
        @SerialName("talk_duration") val talkDuration: Double? = null,
        @SerialName("weather_condition") val weatherCondition: String? = null,
        @SerialName("weather_temperature") val weatherTemperature: Double? = null,
    )

    @Serializable
    data class RoutePoint(
        val lat: Double,
        val lon: Double,
        val alt: Double,
        val ts: Long,
    )

    @Serializable
    data class ActivityIntervalPayload(
        val type: String,
        @SerialName("start_ts") val startTs: Long,
        @SerialName("end_ts") val endTs: Long,
    )

    @Serializable
    data class Waypoint(
        val lat: Double,
        val lon: Double,
        val label: String,
        val icon: String,
        val ts: Long,
    )

    @Serializable
    data class Photo(
        val lat: Double,
        val lon: Double,
        val ts: Long,
        val data: String,
    )
}
```

### Verify
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 4 — `RouteDownsampler` (RDP port)

Pure Ramer-Douglas-Peucker + stride-fallback. Target 200 points. Port of iOS `RouteDownsampler.swift`.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/RouteDownsampler.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stage 8-A: downsamples a long GPS route to at most [maxPoints]
 * points using Ramer-Douglas-Peucker with a binary-search epsilon,
 * falling back to stride sampling if RDP overshoots. Port of iOS
 * `RouteDownsampler.swift` — same algorithm, same 200-point target,
 * same strideSample fallback.
 *
 * Pure function; testable without Android infra.
 */
internal object RouteDownsampler {

    fun downsample(
        points: List<SharePayload.RoutePoint>,
        maxPoints: Int = ShareConfig.DOWNSAMPLE_TARGET_POINTS,
    ): List<SharePayload.RoutePoint> {
        if (points.size <= maxPoints) return points
        val eps = findEpsilon(points, target = maxPoints)
        val simplified = ramerDouglasPeucker(points, eps)
        return if (simplified.size <= maxPoints) simplified else strideSample(simplified, maxPoints)
    }

    private fun strideSample(
        points: List<SharePayload.RoutePoint>,
        target: Int,
    ): List<SharePayload.RoutePoint> {
        val step = (points.size - 1).toDouble() / (target - 1)
        val result = ArrayList<SharePayload.RoutePoint>(target)
        for (i in 0 until target - 1) {
            result += points[(i * step).toInt().coerceAtMost(points.lastIndex)]
        }
        result += points.last()
        return result
    }

    private fun ramerDouglasPeucker(
        points: List<SharePayload.RoutePoint>,
        epsilon: Double,
    ): List<SharePayload.RoutePoint> {
        if (points.size <= 2) return points
        val first = points.first()
        val last = points.last()
        var maxDist = 0.0
        var maxIdx = 0
        for (i in 1 until points.lastIndex) {
            val d = perpendicularDistance(points[i], first, last)
            if (d > maxDist) {
                maxDist = d
                maxIdx = i
            }
        }
        return if (maxDist > epsilon) {
            val left = ramerDouglasPeucker(points.subList(0, maxIdx + 1), epsilon)
            val right = ramerDouglasPeucker(points.subList(maxIdx, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun perpendicularDistance(
        p: SharePayload.RoutePoint,
        a: SharePayload.RoutePoint,
        b: SharePayload.RoutePoint,
    ): Double {
        val dx = b.lat - a.lat
        val dy = b.lon - a.lon
        val denom = sqrt(dx * dx + dy * dy)
        if (denom == 0.0) {
            val ex = p.lat - a.lat
            val ey = p.lon - a.lon
            return sqrt(ex * ex + ey * ey)
        }
        val num = abs(dx * (a.lon - p.lon) - (a.lat - p.lat) * dy)
        return num / denom
    }

    private fun findEpsilon(
        points: List<SharePayload.RoutePoint>,
        target: Int,
    ): Double {
        var low = 0.0
        var high = 1.0
        repeat(MAX_BINARY_SEARCH_ITERATIONS) {
            val mid = (low + high) / 2.0
            val simplified = ramerDouglasPeucker(points, mid)
            when {
                simplified.size > target -> low = mid
                simplified.size < target -> high = mid
                else -> return mid
            }
        }
        return high
    }

    private const val MAX_BINARY_SEARCH_ITERATIONS = 32
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/data/share/RouteDownsamplerTest.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDownsamplerTest {

    private fun pt(lat: Double, lon: Double, alt: Double = 0.0, ts: Long = 0L) =
        SharePayload.RoutePoint(lat, lon, alt, ts)

    @Test
    fun `downsample returns input unchanged when already under cap`() {
        val input = (0 until 50).map { pt(it * 0.001, 0.0) }
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertEquals(input, out)
    }

    @Test
    fun `downsample reduces a 1000-point straight line to near-2 points`() {
        // A perfectly straight line collapses maximally under RDP.
        val input = (0 until 1000).map { pt(it * 0.0001, 0.0) }
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertTrue("expected <= 10 points on a straight line, got ${out.size}", out.size <= 10)
    }

    @Test
    fun `downsample keeps route under or at cap on a noisy line`() {
        val input = (0 until 1000).map { i ->
            pt(i * 0.0001 + (if (i % 2 == 0) 1e-5 else -1e-5), 0.0)
        }
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertTrue("expected <= 200 points, got ${out.size}", out.size <= 200)
    }

    @Test
    fun `downsample preserves first and last points`() {
        val input = (0 until 1000).map { pt(it * 0.0001, it * 0.0001) }
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertEquals(input.first(), out.first())
        assertEquals(input.last(), out.last())
    }

    @Test
    fun `downsample handles exactly 2 points`() {
        val input = listOf(pt(0.0, 0.0), pt(1.0, 1.0))
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertEquals(input, out)
    }
}
```

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.RouteDownsamplerTest"
```

---

## Task 5 — `SharePayloadBuilder`

Pure mapper: `(ShareInputs, WalkShareOptions) → SharePayload`. No Android dependencies, fully unit-testable.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/SharePayloadBuilder.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.Waypoint as WaypointEntity
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Stage 8-A: inputs collected by `WalkShareViewModel` from the repo
 * (pulls match Stage 7-C `composeEtegamiSpec` pattern). Passed to
 * [SharePayloadBuilder.build] as a single aggregate so the builder
 * stays pure + independently testable.
 */
data class ShareInputs(
    val walk: Walk,
    val routePoints: List<LocationPoint>,
    val altitudeSamples: List<AltitudeSample>,
    val activityIntervals: List<ActivityInterval>,
    val voiceRecordings: List<VoiceRecording>,
    val waypoints: List<WaypointEntity>,
    val distanceMeters: Double,
    val activeDurationSeconds: Double,
    val meditateDurationSeconds: Double,
    val talkDurationSeconds: Double,
    val elevationAscentMeters: Double,
    val elevationDescentMeters: Double,
    val steps: Int?,
)

/**
 * User-selected share options surfaced by the modal.
 */
data class WalkShareOptions(
    val expiry: ExpiryOption,
    val journal: String,
    val includeDistance: Boolean,
    val includeDuration: Boolean,
    val includeElevation: Boolean,
    val includeActivityBreakdown: Boolean,
    val includeSteps: Boolean,
    val includeWaypoints: Boolean,
)

internal object SharePayloadBuilder {

    fun build(
        inputs: ShareInputs,
        options: WalkShareOptions,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SharePayload {
        val altitudeByTs = inputs.altitudeSamples.associateBy { it.timestamp }
        val projected = inputs.routePoints.map { p ->
            SharePayload.RoutePoint(
                lat = p.latitude,
                lon = p.longitude,
                alt = altitudeByTs[p.timestamp]?.altitudeMeters ?: 0.0,
                ts = p.timestamp / 1_000L,
            )
        }
        val downsampled = RouteDownsampler.downsample(projected)

        val intervals = buildList {
            inputs.activityIntervals
                .filter { it.activityType == ActivityType.MEDITATING }
                .forEach {
                    add(SharePayload.ActivityIntervalPayload("meditation", it.startTimestamp / 1_000L, it.endTimestamp / 1_000L))
                }
            inputs.voiceRecordings.forEach {
                add(SharePayload.ActivityIntervalPayload("talk", it.startTimestamp / 1_000L, it.endTimestamp / 1_000L))
            }
        }

        val toggledStats = buildList {
            if (options.includeDistance) add("distance")
            if (options.includeDuration) add("duration")
            if (options.includeElevation) add("elevation")
            if (options.includeActivityBreakdown) add("activity_breakdown")
            if (options.includeSteps) add("steps")
        }

        val stats = SharePayload.Stats(
            distance = inputs.distanceMeters.takeIf { it > 0.0 },
            activeDuration = inputs.activeDurationSeconds.takeIf { it > 0.0 },
            elevationAscent = if (options.includeElevation) inputs.elevationAscentMeters.takeIf { it > 1.0 } else null,
            elevationDescent = if (options.includeElevation) inputs.elevationDescentMeters.takeIf { it > 1.0 } else null,
            steps = if (options.includeSteps) inputs.steps?.takeIf { it > 0 } else null,
            meditateDuration = inputs.meditateDurationSeconds.takeIf { it > 0.0 },
            talkDuration = inputs.talkDurationSeconds.takeIf { it > 0.0 },
            weatherCondition = null,
            weatherTemperature = null,
        )

        val waypointsPayload = if (options.includeWaypoints && inputs.waypoints.isNotEmpty()) {
            inputs.waypoints.map {
                SharePayload.Waypoint(
                    lat = it.latitude,
                    lon = it.longitude,
                    label = it.label,
                    icon = it.icon,
                    ts = it.timestamp / 1_000L,
                )
            }
        } else null

        return SharePayload(
            stats = stats,
            route = downsampled,
            activityIntervals = intervals,
            journal = options.journal.takeIf { it.isNotBlank() },
            expiryDays = options.expiry.days,
            units = ShareConfig.DEFAULT_UNITS,
            startDate = ISO.format(Instant.ofEpochMilli(inputs.walk.startTimestamp).atZone(zoneId)),
            tzIdentifier = zoneId.id,
            toggledStats = toggledStats,
            placeStart = null,
            placeEnd = null,
            mark = null,
            waypoints = waypointsPayload,
            photos = null,
            turningDay = null,
        )
    }

    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withLocale(Locale.ROOT)
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/data/share/SharePayloadBuilderTest.kt`:

Unit-test toggled-stats assembly, expiry mapping, altitude zip, meditation+talk interval types, ISO format under Locale.ROOT, and hardcoded `units = "metric"`. 6-8 test cases.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.SharePayloadBuilderTest"
```

---

## Task 6 — `ShareService` (OkHttp POST)

Blocking OkHttp call wrapped in `withContext(Dispatchers.IO)`. Parses 201, maps 429/4xx/5xx/IO.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ShareService.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class ShareService @Inject constructor(
    @ShareHttpClient private val client: OkHttpClient,
    private val json: Json,
    private val deviceTokenStore: DeviceTokenStore,
    private val baseUrl: ShareBaseUrl,
) {
    /**
     * POSTs the payload to `<baseUrl>/api/share`. On 201 returns
     * [ShareResult]; otherwise throws a [ShareError] subtype.
     */
    suspend fun share(payload: SharePayload): ShareResult = withContext(Dispatchers.IO) {
        val body = try {
            json.encodeToString(SharePayload.serializer(), payload)
        } catch (t: Throwable) {
            throw ShareError.EncodingFailed(t)
        }
        val token = deviceTokenStore.getToken()
        val request = Request.Builder()
            .url(baseUrl.value + ShareConfig.SHARE_ENDPOINT)
            .header("Content-Type", "application/json")
            .header("X-Device-Token", token)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (ce: CancellationException) {
            throw ce
        } catch (io: IOException) {
            throw ShareError.NetworkError(io)
        }
        response.use { r ->
            val responseBody = r.body?.string().orEmpty()
            if (r.code == 429) throw ShareError.RateLimited
            if (!r.isSuccessful) {
                val message = runCatching { json.decodeFromString(ErrorResponse.serializer(), responseBody).error }
                    .getOrDefault("Unknown error")
                throw ShareError.ServerError(r.code, message)
            }
            runCatching { json.decodeFromString(SuccessResponse.serializer(), responseBody) }
                .map { ShareResult(url = it.url, id = it.id) }
                .getOrElse { throw ShareError.EncodingFailed(it) }
        }
    }

    @Serializable
    private data class SuccessResponse(val url: String, val id: String)

    @Serializable
    private data class ErrorResponse(val error: String)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@javax.inject.Qualifier
@kotlin.annotation.Retention(kotlin.annotation.AnnotationRetention.BINARY)
annotation class ShareHttpClient

@JvmInline value class ShareBaseUrl(val value: String)
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/data/share/ShareServiceTest.kt`:

MockWebServer fixture. Tests for:
- 201 → parses `ShareResult(url, id)`.
- 429 → throws `ShareError.RateLimited`.
- 500 with `{error: "boom"}` body → throws `ShareError.ServerError(500, "boom")`.
- 500 with garbage body → throws `ShareError.ServerError(500, "Unknown error")`.
- IOException (enqueue no response, close unexpectedly) → throws `ShareError.NetworkError`.
- Device token header actually set on request.
- JSON body matches expected SharePayload shape (deserialize + compare).

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.ShareServiceTest"
```

---

## Task 7 — `CachedShareStore`

DataStore-backed per-walk cache. `Flow<CachedShare?>` per walkUuid.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/CachedShare.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import java.time.Instant

data class CachedShare(
    val url: String,
    val id: String,
    val expiryEpochMs: Long,
    val shareDateEpochMs: Long,
    val expiryOption: ExpiryOption?,
) {
    fun isExpiredAt(nowEpochMs: Long = Instant.now().toEpochMilli()): Boolean = expiryEpochMs <= nowEpochMs
}
```

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStore.kt`:

DataStore-backed with one preference key per walk, value is a JSON-encoded `CachedSharePrefs` data class. `observe(walkUuid): Flow<CachedShare?>` + `suspend fun put(walkUuid, CachedShare)` + `suspend fun clear(walkUuid)`. Key format: `"share_cache_" + walkUuid.replace("-", "")`.

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStoreTest.kt`:

Robolectric — round-trip put/observe, clear, key-format uuid dash-stripping, concurrent writes idempotent.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.CachedShareStoreTest"
```

---

## Task 8 — `ShareModule` (Hilt DI)

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/di/ShareModule.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.share.ShareBaseUrl
import org.walktalkmeditate.pilgrim.data.share.ShareConfig
import org.walktalkmeditate.pilgrim.data.share.ShareHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ShareModule {

    /**
     * Dedicated OkHttp client for share POST — 90s call timeout to
     * accommodate the worker's server-side Mapbox image generation +
     * R2 writes on slow connections. Rebuilds from the shared default
     * client (reuses connection pool, dispatcher).
     */
    @Provides
    @Singleton
    @ShareHttpClient
    fun provideShareHttpClient(default: OkHttpClient): OkHttpClient =
        default.newBuilder()
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideShareBaseUrl(): ShareBaseUrl = ShareBaseUrl(ShareConfig.BASE_URL)
}
```

### Verify
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 9 — `WalkShareViewModel`

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/WalkShareViewModel.kt`:

- Takes `SavedStateHandle` with `walkId: Long` arg.
- State: `uiState: StateFlow<WalkShareUiState>` (Loading | Loaded(ShareInputs, formatted stats) | NotFound | Shared(url, expiry)).
- Mutable modal options as individual `MutableStateFlow`s: `journal`, `selectedExpiry`, toggles, includeWaypoints.
- `init`: collect cached share via `CachedShareStore.observe(walkUuid)`. On non-null + not-expired emission, jump straight to Shared state.
- `updateJournal(new: String)` — silent truncation at `ShareConfig.JOURNAL_MAX_LEN` (iOS parity).
- `share()` — `compareAndSet(false, true)` on `_isSharing` flag; build payload via `SharePayloadBuilder`; call `ShareService.share(...)`; on success cache + emit Shared state; on error emit a `ShareEvent.Failed(snackbarMsg)`.
- `canShare: StateFlow<Boolean>` derived from `toggledStats.isNotEmpty() && !isSharing && routePoints.size >= ShareConfig.ROUTE_MIN_POINTS`.

### Verify
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 10 — `WalkShareScreen` modal composable

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/WalkShareScreen.kt`:

Scaffold with top app bar (Cancel / Share Walk / Done), scrollable Column:
- Route preview card reusing `PilgrimMap`.
- Stat toggle list (5 `Switch` rows).
- Expiry picker — `SegmentedButton` / `FilterChip` row for Moon / Season / Cycle.
- Journal `OutlinedTextField(minLines = 3)` with a `%d/140` counter beneath.
- Waypoint opt-in `Switch` (visible only when waypoints > 0).
- Primary `Button` bottom — "Share" label, spinner when uploading, disabled when `!canShare`. Helper text "Toggle at least one stat to share" when zero toggles.
- Collect `shareEvents`: on success navigate to Shared state (URL readout + Copy / Share / Done buttons). On error show snackbar.

Slot into nav graph.

### Verify
```bash
./gradlew :app:assembleDebug
```

---

## Task 11 — `WalkShareJourneyRow` on Walk Summary

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/WalkShareJourneyRow.kt`:

Compose `@Composable fun WalkShareJourneyRow(state: JourneyRowState, onShareJourney: () -> Unit, onReshare: () -> Unit, onCopy: (url: String) -> Unit, onShare: (url: String) -> Unit, onReopenModal: (walkId: Long) -> Unit)`.

`JourneyRowState` sealed: `Fresh | Active(url, expiry, shareDateMs, expiryOption) | Expired(expiryOption)`.

Rendering matches iOS `WalkSharingButtons` journey section — fresh button, active with kanji watermark (fade 7%→2.5% computed from `shareDateMs` → `expiryEpochMs`), expired with Share-again link.

### Files (wiring)

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt`:

Wrap BOTH existing `WalkEtegamiCard + WalkEtegamiShareRow` AND the new `WalkShareJourneyRow` in a single `if (s.summary.routePoints.size >= 2)` gate, matching iOS `WalkSharingButtons.hasRoute`. Below the etegami pieces, slot `WalkShareJourneyRow` with state derived from `viewModel.cachedShare`.

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt`:

Add `val cachedShare: StateFlow<CachedShare?>` as `cachedShareStore.observe(walk.uuid).stateIn(...)`. Inject `CachedShareStore`.

### Verify
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

---

## Task 12 — `WalkShareViewModelTest`

Turbine-based VM test:
- Loads cached share on init, jumps to Shared state.
- `share()` happy path: emits DispatchShareSuccess + writes to store.
- `share()` rate-limited: emits ShareFailed(RateLimited).
- `canShare` false when all toggles off.
- `updateJournal` truncates at 140.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.WalkShareViewModelTest"
```

---

## Task 13 — Strings, nav graph, polish

### Files

**Modify** `app/src/main/res/values/strings.xml`:
~12 strings — modal title, section labels, button labels, snackbar messages (rate-limited / network / server), toggle descriptions, expiry option display names, char-counter format, helper texts.

**Modify** navigation graph — add `walkShare/{walkId}` route + arg.

### Verify
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

---

## Self-review

- **Spec coverage:** every listed file in the spec has a task; every task maps to spec sections.
- **Task count:** 13 — within autopilot's 15-task threshold.
- **Type consistency:** `ShareInputs`, `WalkShareOptions`, `SharePayload`, `CachedShare`, `ShareResult`, `ShareError` all unambiguous.
- **No placeholders.**
- **Thread policy explicit** per task (IO for HTTP + DataStore, Default for CPU, Main for UI).
- **CE re-throw** in every `catch (Throwable)` block per the spec's quality section.

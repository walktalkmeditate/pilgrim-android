// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.weather

import android.util.Log
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.walktalkmeditate.pilgrim.di.WeatherHttpClient

/**
 * Stage 12-A seam for fetching current weather. Production binding is
 * [OpenMeteoClient]; tests substitute a fake to drive
 * [WalkViewModel]'s `+2s` / `+10s` retry policy without standing up
 * MockWebServer or touching the network. Same shape as the
 * `MilestoneStorage`/`MilestoneChecking` seam from Stage 11.
 *
 * Returns `null` on any failure (no fix, non-2xx, empty body, parse
 * error, network exception). Callers treat `null` as "weather
 * unavailable" and may retry per their own policy.
 */
interface WeatherFetching {
    suspend fun fetchCurrent(latitude: Double, longitude: Double): WeatherSnapshot?
}

/**
 * Stage 12-A — Item A: Open-Meteo current-weather client.
 *
 * Fetches `/v1/forecast?...current=...` from
 * `https://api.open-meteo.com/`, parses the response, and folds the
 * WMO weather code + wind speed into the 10-case [WeatherCondition]
 * enum that walks persist. The mapping is verbatim from iOS
 * `OpenMeteoClient.mapWmoCode` so persisted `weather_condition`
 * column values mean the same thing across platforms.
 *
 * iOS-faithful mapping rules:
 *  - `wind_speed_10m > 10 m/s` collapses ANY condition to [WeatherCondition.WIND].
 *  - Drizzle family (51/53/55/56/57) is **never** wind-checked.
 *  - Rain family (61/63/65/80/81/82) is wind-checked uniformly:
 *    `> 5 m/s` → `HEAVY_RAIN`, else `LIGHT_RAIN`.
 *  - WMO 0 and WMO 1 both → `CLEAR` (iOS lumps `mostlyClear` into `clear`).
 *  - Unknown codes → `CLEAR` (iOS `@unknown default`).
 *
 * Failure policy: every error path (NaN coordinates, non-2xx, empty
 * body, parse failure, network exception) returns `null`. Callers
 * (the walk-start path) treat `null` as "weather unavailable" and
 * persist the walk row without weather columns.
 *
 * The [fetchInternal] seam takes a `baseUrl` parameter so tests can
 * front the client with `MockWebServer.url("/")`. Production callers
 * use [fetchCurrent], which holds the hard-coded Open-Meteo URL.
 *
 * See `docs/superpowers/specs/2026-04-30-stage-12-weather-distance-locale-haptics-design.md`
 * "Item A — Weather domain + Open-Meteo client".
 */
@Singleton
class OpenMeteoClient @Inject constructor(
    @WeatherHttpClient private val client: OkHttpClient,
    private val json: Json,
) : WeatherFetching {

    /** Production entrypoint — uses the hard-coded Open-Meteo base URL. */
    override suspend fun fetchCurrent(latitude: Double, longitude: Double): WeatherSnapshot? =
        fetchInternal(BASE_URL, latitude, longitude)

    /**
     * Test seam: takes a configurable `baseUrl` so MockWebServer can
     * stand in for Open-Meteo. `internal` so the test source set sees
     * it without exposing it on the public API.
     */
    internal suspend fun fetchInternal(
        baseUrl: String,
        latitude: Double,
        longitude: Double,
    ): WeatherSnapshot? = withContext(Dispatchers.IO) {
        // Defensive: a malformed location source (NaN/Infinity) would
        // emit literal `latitude=NaN` URL params; Open-Meteo replies
        // with a 400 in that case, but we'd still pay the round-trip
        // and a noisy log line. Bail before issuing the request.
        if (!latitude.isFinite() || !longitude.isFinite()) return@withContext null
        try {
            // Format with %.6f (≈11cm precision) and Locale.US for guaranteed
            // `.` decimal separator + ASCII digits. Naive Double.toString
            // emits scientific notation (e.g. `1.0E-4`) for absolute values
            // < 10^-3 — coordinates near 0,0 (Gulf of Guinea) would 400 the
            // Open-Meteo endpoint. Matches the codebase's Locale.US numeric
            // formatting convention (PracticeSummaryHeader, VoiceCard, etc.).
            val latStr = String.format(Locale.US, "%.6f", latitude)
            val lngStr = String.format(Locale.US, "%.6f", longitude)
            val url = buildString {
                append(baseUrl)
                append("v1/forecast")
                append("?latitude=").append(latStr)
                append("&longitude=").append(lngStr)
                append("&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
                append("&temperature_unit=celsius")
                append("&wind_speed_unit=ms")
            }
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body.string()
                if (body.isEmpty()) return@withContext null
                val parsed = json.decodeFromString(OpenMeteoResponse.serializer(), body)
                val current = parsed.current ?: return@withContext null
                val temperature = current.temperature2m ?: return@withContext null
                val windSpeedForMapping = current.windSpeed10m ?: 0.0
                val condition = mapWmoCode(current.weatherCode, windSpeedForMapping)
                WeatherSnapshot(
                    condition = condition,
                    temperatureCelsius = temperature,
                    humidityFraction = current.relativeHumidity2m?.let { it / 100.0 },
                    windSpeedMps = current.windSpeed10m,
                )
            }
        } catch (ce: CancellationException) {
            // Structured-concurrency invariant: never swallow.
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "weather fetch failed", t)
            null
        }
    }

    private fun mapWmoCode(code: Int?, windSpeedMs: Double): WeatherCondition {
        if (windSpeedMs > 10.0) return WeatherCondition.WIND
        return when (code) {
            0, 1 -> WeatherCondition.CLEAR
            2 -> WeatherCondition.PARTLY_CLOUDY
            3 -> WeatherCondition.OVERCAST
            45, 48 -> WeatherCondition.FOG
            // Drizzle 51/53/55 — iOS `case .drizzle: .lightRain` (no wind check)
            51, 53, 55 -> WeatherCondition.LIGHT_RAIN
            // Freezing drizzle 56/57 — iOS WeatherService.swift:132 lumps
            // `.freezingDrizzle` into `.snow` alongside `.freezingRain`,
            // `.sleet`, etc. (all sub-zero precipitation). Match exactly
            // so cross-platform `.pilgrim` import doesn't display the
            // wrong icon for the same weather event.
            56, 57 -> WeatherCondition.SNOW
            61, 63, 65, 80, 81, 82 ->
                if (windSpeedMs > 5.0) WeatherCondition.HEAVY_RAIN
                else WeatherCondition.LIGHT_RAIN
            66, 67 -> WeatherCondition.SNOW
            71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
            95, 96, 99 -> WeatherCondition.THUNDERSTORM
            else -> WeatherCondition.CLEAR
        }
    }

    private companion object {
        const val TAG = "OpenMeteo"
        const val BASE_URL = "https://api.open-meteo.com/"
    }
}

@Serializable
internal data class OpenMeteoResponse(val current: Current? = null) {
    @Serializable
    internal data class Current(
        @SerialName("temperature_2m") val temperature2m: Double? = null,
        @SerialName("relative_humidity_2m") val relativeHumidity2m: Double? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
        @SerialName("wind_speed_10m") val windSpeed10m: Double? = null,
    )
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.weather

import android.app.Application
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 12-A — Item A: [OpenMeteoClient] iOS-faithful WMO mapping.
 *
 * MockWebServer fronts the Open-Meteo `/v1/forecast` endpoint by
 * pointing the client's `fetchInternal(baseUrl, ...)` seam at the
 * server URL. Production callers use the public `fetchCurrent(...)`
 * which holds the hard-coded `https://api.open-meteo.com/` base.
 *
 * The mapping table under test is the verbatim Android port of
 * iOS `OpenMeteoClient.mapWmoCode`:
 *   - wind > 10 m/s overrides everything → WIND
 *   - rain family (61/63/65/80/81/82) wind-checked uniformly:
 *       wind > 5 m/s → HEAVY_RAIN, else LIGHT_RAIN
 *   - drizzle family (51/53/55/56/57) **never** wind-checked
 *   - WMO 0 + WMO 1 both → CLEAR (iOS lumps mostlyClear into clear)
 *   - unknown / unrecognized codes → CLEAR (iOS @unknown default)
 *
 * Runs under Robolectric so `android.util.Log` calls inside the
 * client's failure path (`networkFailureReturnsNull`) resolve to the
 * Robolectric stub rather than the JVM's `RuntimeException("Stub!")`
 * fallback. Mirrors the established pattern from
 * `VoiceGuideManifestServiceTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenMeteoClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenMeteoClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString()
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        val json = Json { ignoreUnknownKeys = true }
        client = OpenMeteoClient(okHttp, json)
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Throwable) {
            // already shut down by a test (e.g. networkFailureReturnsNull)
        }
    }

    @Test
    fun mapsCleanCodes() = runTest {
        // Two codes in one go: WMO 0 (CLEAR) and WMO 3 (OVERCAST).
        // wmoCode1MapsToClearPerIosMostlyClear and unknownCodeMapsToClear
        // cover code 1 and the @unknown default branch separately.
        server.enqueue(
            MockResponse().setBody(
                """{"current":{"temperature_2m":18.0,"relative_humidity_2m":50.0,"weather_code":0,"wind_speed_10m":2.0}}"""
            )
        )
        assertEquals(
            WeatherCondition.CLEAR,
            client.fetchInternal(baseUrl, 0.0, 0.0)?.condition,
        )

        server.enqueue(
            MockResponse().setBody(
                """{"current":{"temperature_2m":12.0,"weather_code":3,"wind_speed_10m":2.0}}"""
            )
        )
        assertEquals(
            WeatherCondition.OVERCAST,
            client.fetchInternal(baseUrl, 0.0, 0.0)?.condition,
        )

        server.enqueue(
            MockResponse().setBody(
                """{"current":{"temperature_2m":20.0,"weather_code":95,"wind_speed_10m":2.0}}"""
            )
        )
        assertEquals(
            WeatherCondition.THUNDERSTORM,
            client.fetchInternal(baseUrl, 0.0, 0.0)?.condition,
        )
    }

    @Test
    fun wmoCode1MapsToClearPerIosMostlyClear() = runTest {
        // Pinned regression: WMO 1 (mostly clear) MUST collapse to CLEAR,
        // not PARTLY_CLOUDY. Drift here would diverge from iOS.
        server.enqueue(
            MockResponse().setBody(
                """{"current":{"temperature_2m":18.0,"weather_code":1,"wind_speed_10m":2.0}}"""
            )
        )
        assertEquals(
            WeatherCondition.CLEAR,
            client.fetchInternal(baseUrl, 0.0, 0.0)?.condition,
        )
    }

    @Test
    fun windSpeedOverridesCondition() = runTest {
        // wind > 10 m/s flips to WIND regardless of weather_code.
        server.enqueue(
            MockResponse().setBody(
                """{"current":{"temperature_2m":15.0,"weather_code":0,"wind_speed_10m":12.0}}"""
            )
        )
        assertEquals(
            WeatherCondition.WIND,
            client.fetchInternal(baseUrl, 0.0, 0.0)?.condition,
        )
    }

    @Test
    fun rainWindRuleAppliesUniformlyToAllRainCodes() = runTest {
        // wind > 5 m/s → HEAVY_RAIN for ALL rain codes (not just 63/81).
        listOf(61, 63, 65, 80, 81, 82).forEach { code ->
            server.enqueue(
                MockResponse().setBody(
                    """{"current":{"temperature_2m":15.0,"weather_code":$code,"wind_speed_10m":6.0}}"""
                )
            )
            assertEquals(
                "code $code at wind 6 should be HEAVY_RAIN",
                WeatherCondition.HEAVY_RAIN,
                client.fetchInternal(baseUrl, 0.0, 0.0)?.condition,
            )
        }
        // wind < 5 m/s → LIGHT_RAIN for ALL rain codes.
        listOf(61, 63, 65, 80, 81, 82).forEach { code ->
            server.enqueue(
                MockResponse().setBody(
                    """{"current":{"temperature_2m":15.0,"weather_code":$code,"wind_speed_10m":4.0}}"""
                )
            )
            assertEquals(
                "code $code at wind 4 should be LIGHT_RAIN",
                WeatherCondition.LIGHT_RAIN,
                client.fetchInternal(baseUrl, 0.0, 0.0)?.condition,
            )
        }
    }

    @Test
    fun drizzleSkipsWindCheck() = runTest {
        // Drizzle codes 51/53/55 stay LIGHT_RAIN even at wind 6 m/s.
        listOf(51, 53, 55).forEach { code ->
            server.enqueue(
                MockResponse().setBody(
                    """{"current":{"temperature_2m":15.0,"weather_code":$code,"wind_speed_10m":6.0}}"""
                )
            )
            assertEquals(
                "drizzle code $code never wind-checked",
                WeatherCondition.LIGHT_RAIN,
                client.fetchInternal(baseUrl, 0.0, 0.0)?.condition,
            )
        }
    }

    @Test
    fun unknownCodeMapsToClear() = runTest {
        // iOS @unknown default → .clear. Code 999 is not in the WMO table.
        server.enqueue(
            MockResponse().setBody(
                """{"current":{"temperature_2m":15.0,"weather_code":999,"wind_speed_10m":2.0}}"""
            )
        )
        assertEquals(
            WeatherCondition.CLEAR,
            client.fetchInternal(baseUrl, 0.0, 0.0)?.condition,
        )
    }

    @Test
    fun nanLatitudeReturnsNullWithoutNetworkCall() = runTest {
        val before = server.requestCount
        assertNull(client.fetchInternal(baseUrl, Double.NaN, 0.0))
        assertEquals(before, server.requestCount)
    }

    @Test
    fun nullableHumidityAndWindSpeedPropagate() = runTest {
        // API omits relative_humidity_2m and wind_speed_10m.
        // Both should land as null on the snapshot (NOT 0.0).
        server.enqueue(
            MockResponse().setBody(
                """{"current":{"temperature_2m":18.0,"weather_code":0}}"""
            )
        )
        val snapshot = client.fetchInternal(baseUrl, 0.0, 0.0)
        assertNull(snapshot?.humidityFraction)
        assertNull(snapshot?.windSpeedMps)
        // Sanity: temperature still came through.
        assertEquals(18.0, snapshot?.temperatureCelsius!!, 0.001)
    }

    @Test
    fun networkFailureReturnsNull() = runTest {
        server.shutdown()
        assertNull(client.fetchInternal(baseUrl, 0.0, 0.0))
    }
}

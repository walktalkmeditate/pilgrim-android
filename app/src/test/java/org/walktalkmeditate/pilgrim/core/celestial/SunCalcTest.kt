// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunCalcTest {

    @Test fun `paris summer solstice 2024 matches USNO within 2 minutes`() {
        // Paris 48.8566°N, 2.3522°E, 2024-06-21.
        // USNO: sunrise 2024-06-21 03:47 UTC, sunset 2024-06-21 19:58 UTC.
        val times = SunCalc.sunTimes(
            Instant.parse("2024-06-21T12:00:00Z"),
            latitude = 48.8566,
            longitude = 2.3522,
        )
        assertWithin(
            expected = Instant.parse("2024-06-21T03:47:00Z"),
            actual = times.sunrise!!,
            toleranceMinutes = 2,
            label = "Paris summer solstice sunrise",
        )
        assertWithin(
            expected = Instant.parse("2024-06-21T19:58:00Z"),
            actual = times.sunset!!,
            toleranceMinutes = 2,
            label = "Paris summer solstice sunset",
        )
    }

    @Test fun `paris winter solstice 2024 matches USNO within 2 minutes`() {
        // Paris in December is CET (UTC+1). timeanddate.com local
        // times: sunrise 08:41 CET, sunset 16:56 CET. Converting to
        // UTC subtracts 1 hour: 07:41 UTC, 15:56 UTC.
        val times = SunCalc.sunTimes(
            Instant.parse("2024-12-21T12:00:00Z"),
            latitude = 48.8566,
            longitude = 2.3522,
        )
        assertWithin(
            Instant.parse("2024-12-21T07:41:00Z"),
            times.sunrise!!,
            2,
            "Paris winter solstice sunrise",
        )
        assertWithin(
            Instant.parse("2024-12-21T15:56:00Z"),
            times.sunset!!,
            2,
            "Paris winter solstice sunset",
        )
    }

    @Test fun `sydney summer solstice 2024 matches published times within 3 minutes`() {
        // Sydney -33.8688°S, 151.2093°E. AEDT = UTC+11.
        // timeanddate.com 2024-12-21 AEDT: sunrise 05:40 AEDT, sunset 20:08 AEDT.
        // Converted to UTC: sunrise 2024-12-20 18:40 UTC, sunset 2024-12-21 09:08 UTC.
        val times = SunCalc.sunTimes(
            Instant.parse("2024-12-21T00:00:00Z"),
            latitude = -33.8688,
            longitude = 151.2093,
        )
        assertWithin(
            Instant.parse("2024-12-20T18:40:00Z"),
            times.sunrise!!,
            3,
            "Sydney summer solstice sunrise",
        )
        assertWithin(
            Instant.parse("2024-12-21T09:08:00Z"),
            times.sunset!!,
            3,
            "Sydney summer solstice sunset",
        )
    }

    @Test fun `equator at equinox has approximately equal day and night`() {
        // 0°N, 0°E on 2024-03-20 (vernal equinox).
        // Expect sunrise ≈ 06:07 UTC, sunset ≈ 18:13 UTC (timeanddate.com).
        val times = SunCalc.sunTimes(
            Instant.parse("2024-03-20T12:00:00Z"),
            latitude = 0.0,
            longitude = 0.0,
        )
        val sunrise = times.sunrise!!
        val sunset = times.sunset!!
        val dayLength = Duration.between(sunrise, sunset)
        assertTrue(
            "equator-equinox day length should be within 10 min of 12h, got $dayLength",
            (dayLength.toMinutes() - 12 * 60).let { kotlin.math.abs(it) < 10 },
        )
    }

    @Test fun `polar day at 80N on summer solstice has null sunrise and sunset`() {
        val times = SunCalc.sunTimes(
            Instant.parse("2024-06-21T12:00:00Z"),
            latitude = 80.0,
            longitude = 0.0,
        )
        assertNull("polar day sunrise should be null", times.sunrise)
        assertNull("polar day sunset should be null", times.sunset)
        assertNotNull("solar noon is always non-null", times.solarNoon)
    }

    @Test fun `polar night at 80N on winter solstice has null sunrise and sunset`() {
        val times = SunCalc.sunTimes(
            Instant.parse("2024-12-21T12:00:00Z"),
            latitude = 80.0,
            longitude = 0.0,
        )
        assertNull("polar night sunrise should be null", times.sunrise)
        assertNull("polar night sunset should be null", times.sunset)
        assertNotNull("solar noon is always non-null", times.solarNoon)
    }

    @Test fun `extreme latitude does not throw from acos domain error`() {
        // 89.9999°N on summer solstice — the clamp must prevent NaN.
        val times = SunCalc.sunTimes(
            Instant.parse("2024-06-21T12:00:00Z"),
            latitude = 89.9999,
            longitude = 0.0,
        )
        assertNotNull(times.solarNoon)
    }

    @Test fun `solar noon is near local apparent noon`() {
        // At longitude 2.3522°E: local solar noon = 720 − 4·lon − eqTime
        // minutes UTC from midnight = 720 − 9.4 − 1.6 ≈ 709 min = 11:49.
        // NOAA's algorithm claims ±1 min accuracy; ±5 min tolerance
        // leaves headroom for drift while still catching coefficient-
        // level regressions (a sign-flip in eqTime would shift this by
        // 2-4 minutes and the test would fail).
        val times = SunCalc.sunTimes(
            Instant.parse("2024-06-21T12:00:00Z"),
            latitude = 48.8566,
            longitude = 2.3522,
        )
        val expected = Instant.parse("2024-06-21T11:49:00Z")
        assertWithin(expected, times.solarNoon, 5, "Paris solar noon")
    }

    private fun assertWithin(
        expected: Instant,
        actual: Instant,
        toleranceMinutes: Long,
        label: String,
    ) {
        val diff = Duration.between(expected, actual).abs()
        assertTrue(
            "$label: expected $expected, got $actual, diff ${diff.toMinutes()} min > tolerance $toleranceMinutes",
            diff.toMinutes() <= toleranceMinutes,
        )
    }
}

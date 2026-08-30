// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition

/**
 * Drift test: every Android-storable [WeatherCondition] rawValue must
 * map to a known [WeatherBucket] (parity spec
 * `docs/parity/2026-08-26-threads-senses-port.md`). Android's 10-string
 * vocabulary is verified identical to iOS's — no Android-only condition
 * strings exist, so there is nothing extra to map beyond the 10.
 */
class WeatherBucketTest {

    @Test fun `every storable Android WeatherCondition maps to a known (non-UNKNOWN) bucket`() {
        for (condition in WeatherCondition.entries) {
            val bucket = DossierSensesTracks.bucketForStoredCondition(condition.rawValue)
            assertNotEquals(
                "WeatherCondition.${condition.name} (rawValue=\"${condition.rawValue}\") must not fall through to UNKNOWN",
                WeatherBucket.UNKNOWN,
                bucket,
            )
        }
    }

    @Test fun `the exhaustive rawValue-to-bucket mapping is exactly as pinned`() {
        val expected = mapOf(
            "clear" to WeatherBucket.CLEAR,
            "partlyCloudy" to WeatherBucket.CLOUD,
            "overcast" to WeatherBucket.CLOUD,
            "haze" to WeatherBucket.CLOUD,
            "lightRain" to WeatherBucket.RAIN,
            "heavyRain" to WeatherBucket.RAIN,
            "thunderstorm" to WeatherBucket.RAIN,
            "snow" to WeatherBucket.SNOW,
            "fog" to WeatherBucket.FOG,
            "wind" to WeatherBucket.WIND,
        )
        assertEquals(10, expected.size)
        assertEquals(WeatherCondition.entries.size, expected.size)
        for ((raw, bucket) in expected) {
            assertEquals(raw, bucket, DossierSensesTracks.bucketForStoredCondition(raw))
        }
    }

    @Test fun `an unrecognized or legacy-corrupted string falls back to UNKNOWN`() {
        assertEquals(WeatherBucket.UNKNOWN, DossierSensesTracks.bucketForStoredCondition("bogus-condition"))
        assertEquals(WeatherBucket.UNKNOWN, DossierSensesTracks.bucketForStoredCondition(""))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.LocationPoint

class LightReadingTest {

    @Test fun `with location produces all four primitives`() {
        val startedAt = Instant.parse("2024-06-21T10:00:00Z").toEpochMilli()
        val reading = LightReading.from(
            walkId = 42L,
            startedAtEpochMs = startedAt,
            location = LocationPoint(
                timestamp = startedAt,
                latitude = 48.8566,
                longitude = 2.3522,
            ),
            zoneId = ZoneId.of("Europe/Paris"),
        )
        assertNotNull(reading.moon)
        assertTrue(reading.moon.illumination in 0.0..1.0)
        assertNotNull(reading.sun)
        assertNotNull(reading.sun!!.sunrise)
        assertNotNull(reading.sun!!.sunset)
        // 2024-06-21 was a Friday → dayRuler = Venus.
        assertEquals(Planet.Venus, reading.planetaryHour.dayRuler)
        assertTrue(reading.koan.text.isNotBlank())
        assertTrue(reading.koan.text in Koans.all.map { it.text })
        // Stage 6-B: zoneId is stored on the aggregate so the UI
        // never displays a different zone than the one the planetary
        // hour was computed with.
        assertEquals(ZoneId.of("Europe/Paris"), reading.zoneId)
    }

    @Test fun `without location sun is null but moon planetaryHour koan still computed`() {
        val startedAt = Instant.parse("2024-06-21T10:00:00Z").toEpochMilli()
        val reading = LightReading.from(
            walkId = 42L,
            startedAtEpochMs = startedAt,
            location = null,
            zoneId = ZoneOffset.UTC,
        )
        assertNull(reading.sun)
        assertNotNull(reading.moon)
        assertNotNull(reading.planetaryHour)
        // Friday 10:00 UTC fallback: hourIndex 4, dayRuler Venus (idx 4).
        // Chaldean[(4+4) % 7] = Chaldean[1] = Jupiter.
        assertEquals(Planet.Venus, reading.planetaryHour.dayRuler)
        assertEquals(Planet.Jupiter, reading.planetaryHour.planet)
        assertTrue(reading.koan.text.isNotBlank())
    }

    @Test fun `deterministic - same inputs produce equal aggregates`() {
        val loc = LocationPoint(
            timestamp = 0L,
            latitude = 48.8566,
            longitude = 2.3522,
        )
        val a = LightReading.from(
            walkId = 42L,
            startedAtEpochMs = 1_700_000_000_000L,
            location = loc,
            zoneId = ZoneId.of("Europe/Paris"),
        )
        val b = LightReading.from(
            walkId = 42L,
            startedAtEpochMs = 1_700_000_000_000L,
            location = loc,
            zoneId = ZoneId.of("Europe/Paris"),
        )
        assertEquals(a, b)
    }

    @Test fun `polar-day walk returns null sunrise and sunset but usable aggregate`() {
        val startedAt = Instant.parse("2024-06-21T12:00:00Z").toEpochMilli()
        val reading = LightReading.from(
            walkId = 7L,
            startedAtEpochMs = startedAt,
            location = LocationPoint(
                timestamp = startedAt,
                latitude = 80.0,
                longitude = 0.0,
            ),
            zoneId = ZoneOffset.UTC,
        )
        assertNotNull(reading.sun)
        assertNull(reading.sun!!.sunrise)
        assertNull(reading.sun!!.sunset)
        assertNotNull(reading.sun!!.solarNoon)
        // PlanetaryHour falls back to 6am-6pm when sunrise/sunset null.
        assertNotNull(reading.planetaryHour)
        assertTrue(reading.koan.text.isNotBlank())
    }

    @Test fun `distinct walks can pick distinct readings`() {
        // Not a strict requirement — but for sanity across 50 synthetic
        // walks on the same day with different ids, we expect >= 10
        // distinct koans.
        val readings = (1L..50L).map {
            LightReading.from(
                walkId = it,
                startedAtEpochMs = 1_700_000_000_000L,
                location = null,
                zoneId = ZoneOffset.UTC,
            ).koan
        }.toSet()
        assertTrue(
            "expected ≥ 10 distinct koans, got ${readings.size}",
            readings.size >= 10,
        )
    }

}

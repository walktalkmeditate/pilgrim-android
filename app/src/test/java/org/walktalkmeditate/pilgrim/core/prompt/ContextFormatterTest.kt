// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.ElementBalance
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.core.celestial.PlanetaryHour
import org.walktalkmeditate.pilgrim.core.celestial.PlanetaryPosition
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.ZodiacPosition
import org.walktalkmeditate.pilgrim.core.celestial.ZodiacSign
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition

class ContextFormatterTest {

    private val nyZone: ZoneId = ZoneId.of("America/New_York")
    private val utcZone: ZoneId = ZoneOffset.UTC

    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    private fun nyTimestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()

    private val sampleWeatherLabel: (WeatherCondition) -> String = { condition ->
        when (condition) {
            WeatherCondition.CLEAR -> "Sunny"
            WeatherCondition.PARTLY_CLOUDY -> "Partly Cloudy"
            WeatherCondition.OVERCAST -> "Overcast"
            WeatherCondition.LIGHT_RAIN -> "Light Rain"
            WeatherCondition.HEAVY_RAIN -> "Heavy Rain"
            WeatherCondition.THUNDERSTORM -> "Thunderstorm"
            WeatherCondition.SNOW -> "Snow"
            WeatherCondition.FOG -> "Fog"
            WeatherCondition.WIND -> "Wind"
            WeatherCondition.HAZE -> "Haze"
        }
    }

    // --- formatRecordings ----------------------------------------------------

    @Test
    fun `formatRecordings empty list returns empty string`() {
        assertEquals("", ContextFormatter.formatRecordings(emptyList(), nyZone))
    }

    @Test
    fun `formatRecordings single no coords basic header`() {
        val ts = nyTimestamp(2026, 5, 4, 9, 41)
        val recordings = listOf(
            RecordingContext(
                uuid = "1",
                timestamp = ts,
                startCoordinate = null,
                endCoordinate = null,
                wordsPerMinute = null,
                text = "hello world",
            ),
        )
        assertEquals(
            "[9:41 AM] hello world",
            ContextFormatter.formatRecordings(recordings, nyZone),
        )
    }

    @Test
    fun `formatRecordings with GPS adds gps block`() {
        val ts = nyTimestamp(2026, 5, 4, 9, 41)
        val recordings = listOf(
            RecordingContext(
                uuid = "1",
                timestamp = ts,
                startCoordinate = LatLng(40.71280, -74.00600),
                endCoordinate = LatLng(40.71290, -74.00610),
                wordsPerMinute = null,
                text = "first thought",
            ),
        )
        assertEquals(
            "[9:41 AM] [GPS: 40.71280, -74.00600 → 40.71290, -74.00610] first thought",
            ContextFormatter.formatRecordings(recordings, nyZone),
        )
    }

    @Test
    fun `formatRecordings GPS equals start and end drops arrow`() {
        val ts = nyTimestamp(2026, 5, 4, 9, 41)
        val recordings = listOf(
            RecordingContext(
                uuid = "1",
                timestamp = ts,
                startCoordinate = LatLng(40.71280, -74.00600),
                endCoordinate = LatLng(40.71280, -74.00600),
                wordsPerMinute = null,
                text = "stationary",
            ),
        )
        assertEquals(
            "[9:41 AM] [GPS: 40.71280, -74.00600] stationary",
            ContextFormatter.formatRecordings(recordings, nyZone),
        )
    }

    @Test
    fun `formatRecordings with GPS and WPM appends wpm and label`() {
        val ts = nyTimestamp(2026, 5, 4, 9, 41)
        val recordings = listOf(
            RecordingContext(
                uuid = "1",
                timestamp = ts,
                startCoordinate = LatLng(40.71280, -74.00600),
                endCoordinate = LatLng(40.71290, -74.00610),
                wordsPerMinute = 152.0,
                text = "narration",
            ),
        )
        assertEquals(
            "[9:41 AM] [GPS: 40.71280, -74.00600 → 40.71290, -74.00610] " +
                "[~152 wpm, conversational] narration",
            ContextFormatter.formatRecordings(recordings, nyZone),
        )
    }

    @Test
    fun `formatRecordings multiple entries joined by blank line`() {
        val t1 = nyTimestamp(2026, 5, 4, 9, 41)
        val t2 = nyTimestamp(2026, 5, 4, 9, 50)
        val recordings = listOf(
            RecordingContext("1", t1, null, null, null, "first"),
            RecordingContext("2", t2, null, null, null, "second"),
        )
        assertEquals(
            "[9:41 AM] first\n\n[9:50 AM] second",
            ContextFormatter.formatRecordings(recordings, nyZone),
        )
    }

    // --- formatPlaceNames ----------------------------------------------------

    @Test
    fun `formatPlaceNames empty returns null`() {
        assertNull(ContextFormatter.formatPlaceNames(emptyList()))
    }

    @Test
    fun `formatPlaceNames start only`() {
        val places = listOf(
            PlaceContext("Central Park", LatLng(40.7829, -73.9654), PlaceRole.Start),
        )
        assertEquals(
            "**Location:** Near Central Park",
            ContextFormatter.formatPlaceNames(places),
        )
    }

    @Test
    fun `formatPlaceNames start and end`() {
        val places = listOf(
            PlaceContext("Central Park", LatLng(40.7829, -73.9654), PlaceRole.Start),
            PlaceContext("Bryant Park", LatLng(40.7536, -73.9832), PlaceRole.End),
        )
        assertEquals(
            "**Location:** Started near Central Park → ended near Bryant Park",
            ContextFormatter.formatPlaceNames(places),
        )
    }

    @Test
    fun `formatPlaceNames end only returns null`() {
        val places = listOf(
            PlaceContext("Bryant Park", LatLng(40.7536, -73.9832), PlaceRole.End),
        )
        assertNull(ContextFormatter.formatPlaceNames(places))
    }

    // --- formatMeditations ---------------------------------------------------

    @Test
    fun `formatMeditations empty returns null`() {
        assertNull(ContextFormatter.formatMeditations(emptyList(), nyZone))
    }

    @Test
    fun `formatMeditations under 60 sec uses sec only`() {
        val start = nyTimestamp(2026, 5, 4, 10, 0)
        val end = nyTimestamp(2026, 5, 4, 10, 1)
        val meditations = listOf(
            MeditationContext(startDate = start, endDate = end, durationSeconds = 45),
        )
        assertEquals(
            "[10:00 AM – 10:01 AM] Meditated for 45 sec",
            ContextFormatter.formatMeditations(meditations, nyZone),
        )
    }

    @Test
    fun `formatMeditations over 60 sec uses min and sec`() {
        val start = nyTimestamp(2026, 5, 4, 10, 0)
        val end = nyTimestamp(2026, 5, 4, 10, 6)
        val meditations = listOf(
            MeditationContext(startDate = start, endDate = end, durationSeconds = 330),
        )
        assertEquals(
            "[10:00 AM – 10:06 AM] Meditated for 5 min 30 sec",
            ContextFormatter.formatMeditations(meditations, nyZone),
        )
    }

    // --- formatPaceContext ---------------------------------------------------

    @Test
    fun `formatPaceContext fewer than 10 moving samples returns null`() {
        val speeds = List(9) { 1.5 }
        assertNull(ContextFormatter.formatPaceContext(speeds, imperial = false))
    }

    @Test
    fun `formatPaceContext stationary samples filtered out below threshold`() {
        val speeds = List(9) { 1.5 } + List(5) { 0.1 }
        assertNull(ContextFormatter.formatPaceContext(speeds, imperial = false))
    }

    @Test
    fun `formatPaceContext with 10 moving samples builds line`() {
        val speeds = List(10) { 1.5 }
        val result = ContextFormatter.formatPaceContext(speeds, imperial = false)
        assertEquals(
            "**Pace:** Average 11:06 min/km (range: 11:06 min/km–11:06 min/km)",
            result,
        )
    }

    // --- formatRecentWalks ---------------------------------------------------

    @Test
    fun `formatRecentWalks empty returns null`() {
        assertNull(
            ContextFormatter.formatRecentWalks(
                snippets = emptyList(),
                weatherLabel = sampleWeatherLabel,
                zone = nyZone,
            ),
        )
    }

    @Test
    fun `formatRecentWalks minimal snippet no place no weather`() {
        val snippets = listOf(
            WalkSnippet(
                date = nyTimestamp(2026, 5, 1, 7, 30),
                placeName = null,
                weatherCondition = null,
                celestialSummary = null,
                transcriptionPreview = "soft start",
            ),
        )
        val result = ContextFormatter.formatRecentWalks(snippets, sampleWeatherLabel, nyZone)
        assertEquals(
            "**Recent Walk Context (for continuity):**\n\n[May 1] \"soft start\"",
            result,
        )
    }

    @Test
    fun `formatRecentWalks with weather celestial and place`() {
        val snippets = listOf(
            WalkSnippet(
                date = nyTimestamp(2026, 5, 2, 7, 30),
                placeName = "Riverside Park",
                weatherCondition = "clear",
                celestialSummary = "waxing crescent",
                transcriptionPreview = "river light",
            ),
        )
        val result = ContextFormatter.formatRecentWalks(snippets, sampleWeatherLabel, nyZone)
        assertEquals(
            "**Recent Walk Context (for continuity):**\n\n" +
                "[May 2 – Riverside Park in sunny, waxing crescent] \"river light\"",
            result,
        )
    }

    // --- formatWeather -------------------------------------------------------

    @Test
    fun `formatWeather missing condition returns null`() {
        val walk = sampleWalk(condition = null, temp = 21.0)
        assertNull(ContextFormatter.formatWeather(walk, sampleWeatherLabel, imperial = false))
    }

    @Test
    fun `formatWeather missing temp returns null`() {
        val walk = sampleWalk(condition = "clear", temp = null)
        assertNull(ContextFormatter.formatWeather(walk, sampleWeatherLabel, imperial = false))
    }

    @Test
    fun `formatWeather minimal condition and temp`() {
        val walk = sampleWalk(condition = "clear", temp = 21.0)
        val result = ContextFormatter.formatWeather(walk, sampleWeatherLabel, imperial = false)
        assertEquals("Weather: Sunny, 21°C", result)
    }

    @Test
    fun `formatWeather full humidity and wind imperial`() {
        val walk = sampleWalk(
            condition = "clear",
            temp = 21.0,
            humidity = 0.65,
            windSpeed = 3.0,
        )
        val result = ContextFormatter.formatWeather(walk, sampleWeatherLabel, imperial = true)
        assertEquals("Weather: Sunny, 70°F, humidity 65%, gentle breeze", result)
    }

    private fun sampleWalk(
        condition: String?,
        temp: Double?,
        humidity: Double? = null,
        windSpeed: Double? = null,
    ): Walk = Walk(
        startTimestamp = 0L,
        endTimestamp = 1_000L,
        weatherCondition = condition,
        weatherTemperature = temp,
        weatherHumidity = humidity,
        weatherWindSpeed = windSpeed,
    )

    // --- formatMetadata ------------------------------------------------------

    @Test
    fun `formatMetadata smoke test contains all four pipe segments`() {
        val ts = nyTimestamp(2026, 5, 4, 9, 41)
        val moon = MoonPhase(name = "Waxing Crescent", illumination = 0.32, ageInDays = 4.5)
        val result = ContextFormatter.formatMetadata(
            durationSeconds = 30 * 60L,
            distanceMeters = 2_500.0,
            startTimestamp = ts,
            lunarPhase = moon,
            imperial = false,
            zone = nyZone,
        )
        assertTrue("contains duration: $result", result.contains("Walk duration: 30 minutes"))
        assertTrue("contains distance: $result", result.contains("Distance: 2.50 km"))
        assertTrue("contains time of day: $result", result.contains("Time: morning on "))
        assertTrue("contains moon: $result", result.contains("Moon: Waxing Crescent (32% illumination)"))
        assertEquals(3, result.split(" | ").size - 1)
    }

    // --- timeOfDayDescription ------------------------------------------------

    @Test
    fun `timeOfDayDescription early morning bucket`() {
        assertEquals("early morning", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 5, 0), nyZone))
        assertEquals("early morning", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 8, 59), nyZone))
    }

    @Test
    fun `timeOfDayDescription morning bucket`() {
        assertEquals("morning", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 9, 0), nyZone))
        assertEquals("morning", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 11, 59), nyZone))
    }

    @Test
    fun `timeOfDayDescription midday bucket`() {
        assertEquals("midday", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 12, 0), nyZone))
        assertEquals("midday", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 13, 59), nyZone))
    }

    @Test
    fun `timeOfDayDescription afternoon bucket`() {
        assertEquals("afternoon", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 14, 0), nyZone))
        assertEquals("afternoon", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 16, 59), nyZone))
    }

    @Test
    fun `timeOfDayDescription evening bucket`() {
        assertEquals("evening", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 17, 0), nyZone))
        assertEquals("evening", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 19, 59), nyZone))
    }

    @Test
    fun `timeOfDayDescription night bucket`() {
        assertEquals("night", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 20, 0), nyZone))
        assertEquals("night", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 0, 0), nyZone))
        assertEquals("night", ContextFormatter.timeOfDayDescription(nyTimestamp(2026, 5, 4, 4, 59), nyZone))
    }

    // --- speakingPaceLabel ---------------------------------------------------

    @Test
    fun `speakingPaceLabel buckets`() {
        assertEquals("slow/thoughtful", ContextFormatter.speakingPaceLabel(80.0))
        assertEquals("slow/thoughtful", ContextFormatter.speakingPaceLabel(99.99))
        assertEquals("measured", ContextFormatter.speakingPaceLabel(100.0))
        assertEquals("measured", ContextFormatter.speakingPaceLabel(120.0))
        assertEquals("conversational", ContextFormatter.speakingPaceLabel(140.0))
        assertEquals("conversational", ContextFormatter.speakingPaceLabel(150.0))
        assertEquals("rapid/energized", ContextFormatter.speakingPaceLabel(170.0))
        assertEquals("rapid/energized", ContextFormatter.speakingPaceLabel(200.0))
    }

    // --- formatCoord ---------------------------------------------------------

    @Test
    fun `formatCoord 5 decimals locale stable`() {
        assertEquals("40.71280, -74.00600", ContextFormatter.formatCoord(40.71280, -74.00600))
    }

    @Test
    fun `formatCoord stable under non-US default locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("40.71280, -74.00600", ContextFormatter.formatCoord(40.71280, -74.00600))
    }

    // --- formatPace ----------------------------------------------------------

    @Test
    fun `formatPace zero meters per second returns em dash`() {
        assertEquals("—", ContextFormatter.formatPace(0.0, imperial = false))
    }

    @Test
    fun `formatPace negative returns em dash`() {
        assertEquals("—", ContextFormatter.formatPace(-1.0, imperial = false))
    }

    @Test
    fun `formatPace metric builds line`() {
        // 1.5 m/s → 1000 / 1.5 = 666.66s → 11m 06s
        assertEquals("11:06 min/km", ContextFormatter.formatPace(1.5, imperial = false))
    }

    @Test
    fun `formatPace imperial builds line`() {
        // 1.5 m/s → 1609.34 / 1.5 = 1072.89s → 17m 52s (toInt truncates)
        assertEquals("17:52 min/mi", ContextFormatter.formatPace(1.5, imperial = true))
    }

    // --- formatCelestial -----------------------------------------------------

    @Test
    fun `formatCelestial smoke test`() {
        val sunPos = PlanetaryPosition(
            planet = Planet.Sun,
            longitude = 5.0,
            tropical = ZodiacPosition(ZodiacSign.Aries, 5.0),
            sidereal = ZodiacPosition(ZodiacSign.Pisces, 11.0),
            isRetrograde = false,
            isIngress = false,
        )
        val mercuryPos = PlanetaryPosition(
            planet = Planet.Mercury,
            longitude = 31.0,
            tropical = ZodiacPosition(ZodiacSign.Taurus, 1.0),
            sidereal = ZodiacPosition(ZodiacSign.Aries, 7.0),
            isRetrograde = true,
            isIngress = true,
        )
        val snapshot = CelestialSnapshot(
            positions = listOf(sunPos, mercuryPos),
            planetaryHour = PlanetaryHour(planet = Planet.Venus, dayRuler = Planet.Sun),
            elementBalance = ElementBalance(
                counts = ZodiacSign.Element.entries.associateWith { 1 },
                dominant = ZodiacSign.Element.Fire,
            ),
            system = ZodiacSystem.Tropical,
            seasonalMarker = SeasonalMarker.SpringEquinox,
        )
        val result = ContextFormatter.formatCelestial(snapshot)
        assertNotNull(result)
        assertTrue("header: $result", result.startsWith("**Celestial Context (Tropical):** "))
        assertTrue("sun entry: $result", result.contains("Sun in Aries (5°)"))
        assertTrue("mercury Rx entry: $result", result.contains("Mercury in Taurus (1°) Rx"))
        assertTrue("hour: $result", result.contains(" | Hour of Venus"))
        assertTrue("dominant: $result", result.contains(" | Fire predominates"))
        assertTrue("ingress: $result", result.contains(" | Mercury enters Taurus"))
        assertTrue("seasonal marker: $result", result.endsWith(" — Spring Equinox"))
    }

    @Test
    fun `formatCelestial sidereal uses sidereal positions`() {
        val sunPos = PlanetaryPosition(
            planet = Planet.Sun,
            longitude = 5.0,
            tropical = ZodiacPosition(ZodiacSign.Aries, 5.0),
            sidereal = ZodiacPosition(ZodiacSign.Pisces, 11.0),
            isRetrograde = false,
            isIngress = false,
        )
        val snapshot = CelestialSnapshot(
            positions = listOf(sunPos),
            planetaryHour = PlanetaryHour(planet = Planet.Sun, dayRuler = Planet.Sun),
            elementBalance = ElementBalance(counts = emptyMap(), dominant = null),
            system = ZodiacSystem.Sidereal,
            seasonalMarker = null,
        )
        val result = ContextFormatter.formatCelestial(snapshot)
        assertTrue("header: $result", result.startsWith("**Celestial Context (Sidereal):** "))
        assertTrue("sun sidereal: $result", result.contains("Sun in Pisces (11°)"))
    }

    @Test
    fun `formatCelestial omits dominant when null`() {
        val snapshot = CelestialSnapshot(
            positions = emptyList(),
            planetaryHour = PlanetaryHour(planet = Planet.Sun, dayRuler = Planet.Sun),
            elementBalance = ElementBalance(counts = emptyMap(), dominant = null),
            system = ZodiacSystem.Tropical,
            seasonalMarker = null,
        )
        val result = ContextFormatter.formatCelestial(snapshot)
        assertEquals("**Celestial Context (Tropical):**  | Hour of Sun", result)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition

/**
 * Pins the hour-light tokens, celestial tint precedence, and seek voice
 * mappings against the port spec
 * (`docs/parity/2026-07-14-port-seek-crescent-u7.md`; iOS `SeekSkyLight`
 * in SeekFogModel.swift + `SeekSky`/`SeekVoice` in SeekGatewayView.swift
 * @c1745e8).
 */
class SeekSkyLightTest {

    // Daypart boundaries

    @Test
    fun `daypart thresholds and the no-fix default are exact`() {
        assertEquals(
            "no fix = the seek's home light",
            SeekSkyLight.Daypart.GOLDEN,
            SeekSkyLight.daypart(null),
        )
        assertEquals(SeekSkyLight.Daypart.NIGHT, SeekSkyLight.daypart(-10.0))
        assertEquals(SeekSkyLight.Daypart.GOLDEN, SeekSkyLight.daypart(-4.0))
        assertEquals(SeekSkyLight.Daypart.GOLDEN, SeekSkyLight.daypart(0.0))
        assertEquals(SeekSkyLight.Daypart.GOLDEN, SeekSkyLight.daypart(7.9))
        assertEquals(SeekSkyLight.Daypart.MIDDAY, SeekSkyLight.daypart(8.0))
        assertEquals(SeekSkyLight.Daypart.MIDDAY, SeekSkyLight.daypart(45.0))
        assertEquals(SeekSkyLight.Daypart.NIGHT, SeekSkyLight.daypart(-4.0000001))
    }

    // Hexes + tokens

    @Test
    fun `golden dawn keeps the seek signature`() {
        assertEquals("#C4956A", SeekSkyLight.hex(SeekSkyLight.Daypart.GOLDEN, starlight = false))
    }

    @Test
    fun `night dawn is the full moon silver`() {
        assertEquals(
            "night crescents share the full-moon fog tint — one moonlight vocabulary",
            "#A9AFBC",
            SeekSkyLight.hex(SeekSkyLight.Daypart.NIGHT, starlight = false),
        )
    }

    @Test
    fun `constellation night is the pucks exact starlight`() {
        // The constellation palette's stone (ui/theme/Color.kt) is
        // Color(0xFFC8C0FF) — the crescent at night under the
        // constellation sky must match the puck exactly.
        assertEquals("#C8C0FF", SeekSkyLight.hex(SeekSkyLight.Daypart.NIGHT, starlight = true))
    }

    @Test
    fun `all six lights and tokens are distinct`() {
        val lights = SeekSkyLight.Daypart.entries.flatMap { daypart ->
            listOf(daypart to false, daypart to true)
        }
        val hexes = lights.map { (daypart, starlight) -> SeekSkyLight.hex(daypart, starlight) }
        val tokens = lights.map { (daypart, starlight) -> SeekSkyLight.token(daypart, starlight) }
        assertEquals(hexes.size, hexes.toSet().size)
        assertEquals(tokens.size, tokens.toSet().size)
    }

    @Test
    fun `token carries family and hour so theme and hour swaps invalidate the image cache`() {
        assertEquals("star-night", SeekSkyLight.token(SeekSkyLight.Daypart.NIGHT, starlight = true))
        assertEquals("dawn-golden", SeekSkyLight.token(SeekSkyLight.Daypart.GOLDEN, starlight = false))
    }

    // Celestial tint precedence

    @Test
    fun `each turning tints the fog and speaks its own line`() {
        val expectations = listOf(
            Triple(SeasonalMarker.SpringEquinox, "#74B495", R.string.seek_tint_line_spring_equinox),
            Triple(SeasonalMarker.SummerSolstice, "#C9A646", R.string.seek_tint_line_summer_solstice),
            Triple(SeasonalMarker.AutumnEquinox, "#8B4455", R.string.seek_tint_line_autumn_equinox),
            Triple(SeasonalMarker.WinterSolstice, "#2377A4", R.string.seek_tint_line_winter_solstice),
        )
        for ((marker, hex, lineRes) in expectations) {
            val tint = SeekSky.tint(marker, isFullMoon = false)
            assertEquals("$marker fog hex", hex, tint?.fogHex)
            assertEquals("$marker gateway line", lineRes, tint?.gatewayLineRes)
        }
    }

    @Test
    fun `turnings outrank the full moon`() {
        val tint = SeekSky.tint(SeasonalMarker.WinterSolstice, isFullMoon = true)
        assertEquals("#2377A4", tint?.fogHex)
        assertNotEquals(R.string.seek_tint_line_full_moon, tint?.gatewayLineRes)
    }

    @Test
    fun `full moon tints silver when no turning speaks`() {
        val tint = SeekSky.tint(marker = null, isFullMoon = true)
        assertEquals("#A9AFBC", tint?.fogHex)
        assertEquals(R.string.seek_tint_line_full_moon, tint?.gatewayLineRes)
    }

    @Test
    fun `cross-quarter days keep the ordinary fog but let the moon speak`() {
        for (marker in listOf(
            SeasonalMarker.Imbolc,
            SeasonalMarker.Beltane,
            SeasonalMarker.Lughnasadh,
            SeasonalMarker.Samhain,
        )) {
            assertNull("$marker keeps ordinary fog", SeekSky.tint(marker, isFullMoon = false))
            assertEquals(
                "$marker still lets the full moon tint",
                "#A9AFBC",
                SeekSky.tint(marker, isFullMoon = true)?.fogHex,
            )
        }
    }

    @Test
    fun `ordinary sky has no tint`() {
        assertNull(SeekSky.tint(marker = null, isFullMoon = false))
    }

    // Seek voice

    @Test
    fun `every weather condition maps to its own seek greeting`() {
        val expected = mapOf(
            WeatherCondition.CLEAR to R.string.seek_greeting_weather_clear,
            WeatherCondition.PARTLY_CLOUDY to R.string.seek_greeting_weather_partly_cloudy,
            WeatherCondition.OVERCAST to R.string.seek_greeting_weather_overcast,
            WeatherCondition.LIGHT_RAIN to R.string.seek_greeting_weather_light_rain,
            WeatherCondition.HEAVY_RAIN to R.string.seek_greeting_weather_heavy_rain,
            WeatherCondition.THUNDERSTORM to R.string.seek_greeting_weather_thunderstorm,
            WeatherCondition.SNOW to R.string.seek_greeting_weather_snow,
            WeatherCondition.FOG to R.string.seek_greeting_weather_fog,
            WeatherCondition.WIND to R.string.seek_greeting_weather_wind,
            WeatherCondition.HAZE to R.string.seek_greeting_weather_haze,
        )
        for (condition in WeatherCondition.entries) {
            assertEquals(
                "seek greeting for $condition",
                expected.getValue(condition),
                SeekVoice.greetingRes(condition),
            )
        }
        assertEquals(
            "greetings must be distinct per condition",
            WeatherCondition.entries.size,
            WeatherCondition.entries.map(SeekVoice::greetingRes).toSet().size,
        )
    }
}

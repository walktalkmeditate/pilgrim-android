// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import androidx.annotation.StringRes
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition

/**
 * The crescent carries the hour: dawn-amber in the golden hours, pale gold
 * through midday, moonlight silver after dark — and under the constellation
 * sky the same hours move through the puck's starlight instead, exactly the
 * puck's lavender at night, its home hour. All values are fixed hexes
 * (adaptive colors invert on the map and become halos). Port spec
 * `docs/parity/2026-07-14-port-seek-crescent-u7.md`
 * (iOS `SeekSkyLight`, `SeekFogModel.swift:192-224@c1745e8`).
 */
object SeekSkyLight {

    enum class Daypart(val token: String) {
        GOLDEN("golden"),
        MIDDAY("midday"),
        NIGHT("night"),
    }

    /**
     * Golden spans civil twilight into the low sun. No elevation (no fix
     * yet) stays golden — the seek's home light.
     */
    fun daypart(solarElevationDegrees: Double?): Daypart = when {
        solarElevationDegrees == null -> Daypart.GOLDEN
        solarElevationDegrees < -4.0 -> Daypart.NIGHT
        solarElevationDegrees < 8.0 -> Daypart.GOLDEN
        else -> Daypart.MIDDAY
    }

    fun hex(daypart: Daypart, starlight: Boolean): String = when {
        !starlight && daypart == Daypart.GOLDEN -> "#C4956A"
        !starlight && daypart == Daypart.MIDDAY -> "#D2B283"
        // Night crescents share the full-moon fog tint — one moonlight
        // vocabulary. Constellation night is the puck's exact starlight
        // (the constellation palette's stone, ui/theme/Color.kt).
        !starlight -> "#A9AFBC"
        daypart == Daypart.GOLDEN -> "#D3BCE8"
        daypart == Daypart.MIDDAY -> "#DAD4F5"
        else -> "#C8C0FF"
    }

    /** Cache token for pre-rendered crescent images — one per (span, light). */
    fun token(daypart: Daypart, starlight: Boolean): String =
        "${if (starlight) "star" else "dawn"}-${daypart.token}"
}

/**
 * The sky's mark on a seek: a turning or a full moon tints the fog and
 * speaks its own gateway line. Fixed per walk (computed once at seek setup,
 * gated on the celestial-awareness preference — the U8 call site). Hexes
 * come from the seal palette's turning overrides (fixed values — adaptive
 * colors become halos on the map). iOS `SeekTint`
 * (`SeekGatewayView.swift:110-113@c1745e8`).
 */
data class SeekTint(
    val fogHex: String,
    @StringRes val gatewayLineRes: Int,
)

/**
 * Turnings outrank the moon — four days a year beat thirteen nights.
 * Cross-quarter days keep the ordinary fog (the moon may still speak).
 * iOS `SeekSky` (`SeekGatewayView.swift:115-141@c1745e8`).
 */
object SeekSky {

    fun tint(marker: SeasonalMarker?, isFullMoon: Boolean): SeekTint? {
        when (marker) {
            SeasonalMarker.SpringEquinox ->
                return SeekTint("#74B495", R.string.seek_tint_line_spring_equinox)
            SeasonalMarker.SummerSolstice ->
                return SeekTint("#C9A646", R.string.seek_tint_line_summer_solstice)
            SeasonalMarker.AutumnEquinox ->
                return SeekTint("#8B4455", R.string.seek_tint_line_autumn_equinox)
            SeasonalMarker.WinterSolstice ->
                return SeekTint("#2377A4", R.string.seek_tint_line_winter_solstice)
            SeasonalMarker.Imbolc,
            SeasonalMarker.Beltane,
            SeasonalMarker.Lughnasadh,
            SeasonalMarker.Samhain,
            null,
            -> Unit
        }
        if (isFullMoon) {
            return SeekTint("#A9AFBC", R.string.seek_tint_line_full_moon)
        }
        return null
    }
}

/**
 * Seek speaks its own weather: the wander greetings name the path; these
 * name the search. Replaces the wander greeting on seek walks (the U8
 * call site). iOS `SeekVoice` (`SeekGatewayView.swift:145-160@c1745e8`).
 */
object SeekVoice {

    @StringRes
    fun greetingRes(condition: WeatherCondition): Int = when (condition) {
        WeatherCondition.CLEAR -> R.string.seek_greeting_weather_clear
        WeatherCondition.PARTLY_CLOUDY -> R.string.seek_greeting_weather_partly_cloudy
        WeatherCondition.OVERCAST -> R.string.seek_greeting_weather_overcast
        WeatherCondition.LIGHT_RAIN -> R.string.seek_greeting_weather_light_rain
        WeatherCondition.HEAVY_RAIN -> R.string.seek_greeting_weather_heavy_rain
        WeatherCondition.THUNDERSTORM -> R.string.seek_greeting_weather_thunderstorm
        WeatherCondition.SNOW -> R.string.seek_greeting_weather_snow
        WeatherCondition.FOG -> R.string.seek_greeting_weather_fog
        WeatherCondition.WIND -> R.string.seek_greeting_weather_wind
        WeatherCondition.HAZE -> R.string.seek_greeting_weather_haze
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.weather

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.walktalkmeditate.pilgrim.R

/**
 * Stage 12-A weather classification. Ten cases mirror iOS
 * `WeatherCondition.swift` raw values verbatim — `clear`,
 * `partlyCloudy`, … — so the persisted `weather_condition` column
 * round-trips between platforms.
 *
 * `rawValue` is the on-disk + on-wire identifier (matches iOS
 * `String, Codable` rawValue). `labelRes` and `iconRes` resolve at
 * display time so theme + locale flow through naturally.
 */
enum class WeatherCondition(
    val rawValue: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    CLEAR("clear", R.string.weather_clear, R.drawable.ic_weather_clear),
    PARTLY_CLOUDY("partlyCloudy", R.string.weather_partly_cloudy, R.drawable.ic_weather_partly_cloudy),
    OVERCAST("overcast", R.string.weather_overcast, R.drawable.ic_weather_overcast),
    LIGHT_RAIN("lightRain", R.string.weather_light_rain, R.drawable.ic_weather_light_rain),
    HEAVY_RAIN("heavyRain", R.string.weather_heavy_rain, R.drawable.ic_weather_heavy_rain),
    THUNDERSTORM("thunderstorm", R.string.weather_thunderstorm, R.drawable.ic_weather_thunderstorm),
    SNOW("snow", R.string.weather_snow, R.drawable.ic_weather_snow),
    FOG("fog", R.string.weather_fog, R.drawable.ic_weather_fog),
    WIND("wind", R.string.weather_wind, R.drawable.ic_weather_wind),
    HAZE("haze", R.string.weather_haze, R.drawable.ic_weather_haze);

    companion object {
        fun fromRawValue(raw: String?): WeatherCondition? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

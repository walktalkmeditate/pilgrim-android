// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.weather

/**
 * Stage 12-A snapshot of weather conditions captured at walk-start
 * via [OpenMeteoClient]. Mirrors the iOS `WeatherSnapshot` struct
 * field-for-field so the per-walk weather payload (`condition`,
 * `temperature`, `humidity`, `wind_speed`) round-trips between
 * platforms.
 *
 * - [temperatureCelsius] is always populated when a snapshot exists;
 *   `OpenMeteoClient` returns `null` outright when the API omits it.
 * - [humidityFraction] is `0.0`–`1.0` (Open-Meteo reports 0–100; the
 *   client divides by 100 before constructing this snapshot). `null`
 *   when the API omits the field.
 * - [windSpeedMps] is meters/second (Open-Meteo's `wind_speed_unit=ms`
 *   query string). `null` when the API omits the field.
 */
data class WeatherSnapshot(
    val condition: WeatherCondition,
    val temperatureCelsius: Double,
    val humidityFraction: Double?,
    val windSpeedMps: Double?,
)

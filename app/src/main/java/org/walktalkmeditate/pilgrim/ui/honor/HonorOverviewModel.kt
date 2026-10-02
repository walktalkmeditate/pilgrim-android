// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.content.res.Resources
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.honor.HonorTuning
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayWeather
import org.walktalkmeditate.pilgrim.domain.wgs84MidLatitudeMeters
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds

/**
 * Every line on the overview's card, as iOS's `HonorOverviewModel` and
 * `HonorOverviewView` build them (`HonorOverviewView.swift:7-59,227-351@7c200bf`,
 * parity spec F §10). Numbers are POSIX-formatted, as Swift's interpolation
 * and `String(format:)` without a locale are.
 */
object HonorOverviewModel {

    /** The Way's route points' bounding box; the pins are not included (F §9.1). */
    fun bounds(way: Way): MapCameraBounds? {
        if (way.route.isEmpty()) return null
        return MapCameraBounds(
            swLat = way.route.minOf { it.lat },
            swLng = way.route.minOf { it.lon },
            neLat = way.route.maxOf { it.lat },
            neLng = way.route.maxOf { it.lon },
        )
    }

    /** iOS `DateFormatter.localizedString(dateStyle: .long, timeStyle: .short)`, in the phone's zone. */
    fun departureLine(way: Way, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(way.departedAt)

    /** `"1h 5m"`, `"42m"`, `"0m"`: minutes truncate, seconds never show. */
    fun durationText(seconds: Double): String {
        val clamped = if (seconds.isNaN()) 0L else seconds.coerceIn(0.0, MAX_SECONDS).toLong()
        val hours = clamped / 3600
        val minutes = (clamped % 3600) / 60
        return if (hours > 0) {
            String.format(Locale.US, "%dh %dm", hours, minutes)
        } else {
            String.format(Locale.US, "%dm", minutes)
        }
    }

    /** Voices then photos, `" · "`-joined; waypoints, rests, and sittings are not counted. */
    fun countsLine(resources: Resources, way: Way): String = countsLine(resources, way.voiceCount, way.photoCount)

    /**
     * The counts a Way declares, whether or not their files are on the
     * phone (S4 §6.4), each word picked by iOS's `count == 1` in every locale.
     */
    fun countsLine(resources: Resources, voiceCount: Int, photoCount: Int): String {
        val parts = buildList {
            if (voiceCount > 0) {
                add(count(resources, voiceCount, R.string.honor_overview_voice_one, R.string.honor_overview_voices))
            }
            if (photoCount > 0) {
                add(count(resources, photoCount, R.string.honor_overview_photo_one, R.string.honor_overview_photos))
            }
        }
        if (parts.isEmpty()) return resources.getString(R.string.honor_overview_quiet_way)
        return parts.joinToString(" · ")
    }

    /**
     * `"they walked this in light rain at 9°. Today is clear."`. The
     * temperature is rounded Celsius with a bare degree sign whatever the
     * walker's units, and own walks speak of "they" too: both as iOS ships
     * them (pilgrim-ios #109, matched).
     */
    fun weatherLine(resources: Resources, theirs: WayWeather?, today: String?, locale: Locale): String? {
        theirs ?: return null
        val condition = spoken(resources, theirs.condition, locale)
        val temperature = theirs.temperatureC?.takeIf { it.isFinite() }?.let {
            String.format(Locale.US, "%d", roundedHalfAwayFromZero(it).coerceIn(-1000.0, 1000.0).toLong())
        }
        val todaySpoken = today?.let { spoken(resources, it, locale) }
        return when {
            temperature != null && todaySpoken != null -> resources.getString(
                R.string.honor_overview_weather_temperature_today,
                condition,
                temperature,
                todaySpoken,
            )
            temperature != null ->
                resources.getString(R.string.honor_overview_weather_temperature, condition, temperature)
            todaySpoken != null -> resources.getString(R.string.honor_overview_weather_today, condition, todaySpoken)
            else -> resources.getString(R.string.honor_overview_weather, condition)
        }
    }

    /**
     * At or under 60 m, "you're on the way". Metric: whole metres truncated
     * under 1000, else one decimal of km. Imperial: whole feet truncated
     * under 0.2 mi, else one decimal of miles. The overview's own
     * thresholds, not the walk card's (F §10.6).
     */
    fun statusLine(resources: Resources, distanceToStartMeters: Double?, units: UnitSystem): String? {
        val meters = distanceToStartMeters ?: return null
        if (meters <= HonorTuning.ON_WAY_METERS) return resources.getString(R.string.honor_overview_on_the_way)
        val distance = when (units) {
            UnitSystem.Imperial -> {
                val miles = meters / METERS_PER_MILE
                if (miles < 0.2) {
                    String.format(Locale.US, "%d ft", (meters * FEET_PER_METER).toLong())
                } else {
                    String.format(Locale.US, "%.1f mi", miles)
                }
            }
            UnitSystem.Metric -> if (meters < 1000) {
                String.format(Locale.US, "%d m", meters.toLong())
            } else {
                String.format(Locale.US, "%.1f km", meters / 1000)
            }
        }
        return resources.getString(R.string.honor_overview_from_start, distance)
    }

    /** One straight-line probe from the phone's last fix to the Way's first point, by iOS's `CLLocation.distance`. */
    fun distanceToStartMeters(here: LocationPoint, way: Way): Double? {
        val first = way.route.firstOrNull() ?: return null
        return wgs84MidLatitudeMeters(here.latitude, here.longitude, first.lat, first.lon)
    }

    /**
     * A `WeatherCondition` raw value ("lightRain") reads as its lowercased
     * label; anything outside that vocabulary passes through untouched.
     */
    private fun spoken(resources: Resources, condition: String, locale: Locale): String =
        WeatherCondition.fromRawValue(condition)
            ?.let { resources.getString(it.labelRes).lowercase(locale) }
            ?: condition

    private fun count(resources: Resources, count: Int, one: Int, other: Int): String =
        resources.getString(if (count == 1) one else other, String.format(Locale.US, "%d", count))

    /** Swift's `rounded()`. */
    private fun roundedHalfAwayFromZero(value: Double): Double = sign(value) * floor(abs(value) + 0.5)

    private const val MAX_SECONDS = 999_999_999.0
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Verbatim port of iOS `ContextFormatter.swift`. Produces LLM-facing
 * prompt-context strings; output must match iOS shape (whitespace,
 * separators, casing) so the same downstream prompts work on both
 * platforms.
 *
 * Locale handling:
 * - Numeric formatters ([formatCoord], [formatPace], temperature/wind
 *   helpers) pin [Locale.US] for ASCII-digit + `.` decimal stability.
 *   The LLM treats output as a token stream; non-ASCII digits change
 *   tokenization across device locales (Stage 5-A audit lesson).
 * - Date formatters use [Locale.getDefault] — matches iOS's default-
 *   locale `DateFormatter` behavior.
 *
 * `imperial: Boolean` is injected (vs reading global preferences) per
 * Stage 13-Cel divergence — caller pulls `UnitsPreferences.distanceUnits`
 * and passes through. iOS's `formatPace` reads `Locale.current.measurementSystem`;
 * Android prefers the user's explicit setting from UnitsPreferences.
 *
 * `WeatherCondition` labels are resolved via an injected
 * `weatherLabel: (WeatherCondition) -> String` lambda so this object
 * stays pure — call sites provide a closure over an Android `Context`
 * (`{ ctx.getString(it.labelRes) }`) without coupling ContextFormatter
 * to Android framework types.
 */
object ContextFormatter {

    private val timeFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())

    private val dateTimeFormatter: DateTimeFormatter
        get() = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())

    private val shortDateFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

    fun formatRecordings(
        recordings: List<RecordingContext>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = recordings.joinToString(separator = "\n\n") { item ->
        val header = StringBuilder()
        header.append('[').append(formatTime(item.timestamp, zone)).append(']')
        val start = item.startCoordinate
        if (start != null) {
            header.append(" [GPS: ").append(formatCoord(start.latitude, start.longitude))
            val end = item.endCoordinate
            if (end != null && (end.latitude != start.latitude || end.longitude != start.longitude)) {
                header.append(" → ").append(formatCoord(end.latitude, end.longitude))
            }
            header.append(']')
        }
        val wpm = item.wordsPerMinute
        if (wpm != null) {
            header.append(" [~").append(wpm.toInt()).append(" wpm, ")
                .append(speakingPaceLabel(wpm)).append(']')
        }
        "$header ${item.text}"
    }

    fun formatPlaceNames(places: List<PlaceContext>): String? {
        if (places.isEmpty()) return null
        val start = places.firstOrNull { it.role == PlaceRole.Start }
        val end = places.firstOrNull { it.role == PlaceRole.End }
        return when {
            start != null && end != null ->
                "**Location:** Started near ${start.name} → ended near ${end.name}"
            start != null ->
                "**Location:** Near ${start.name}"
            else -> null
        }
    }

    fun formatMeditations(
        meditations: List<MeditationContext>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (meditations.isEmpty()) return null
        return meditations.joinToString(separator = "\n") { m ->
            val durationSec = m.durationSeconds.toInt()
            val durationStr = if (durationSec < 60) {
                "$durationSec sec"
            } else {
                "${durationSec / 60} min ${durationSec % 60} sec"
            }
            "[${formatTime(m.startDate, zone)} – ${formatTime(m.endDate, zone)}] " +
                "Meditated for $durationStr"
        }
    }

    fun formatPaceContext(speeds: List<Double>, imperial: Boolean): String? {
        val moving = speeds.filter { it >= 0.3 }
        if (moving.size < 10) return null
        val avgSpeed = moving.sum() / moving.size
        val minSpeed = moving.min()
        val maxSpeed = moving.max()
        val avgPace = formatPace(avgSpeed, imperial)
        val slowPace = formatPace(minSpeed, imperial)
        val fastPace = formatPace(maxSpeed, imperial)
        return "**Pace:** Average $avgPace (range: $fastPace–$slowPace)"
    }

    fun formatRecentWalks(
        snippets: List<WalkSnippet>,
        weatherLabel: (WeatherCondition) -> String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (snippets.isEmpty()) return null
        val lines = snippets.map { snippet ->
            val dateStr = formatShortDate(snippet.date, zone)
            val weatherStr = snippet.weatherCondition
                ?.let { WeatherCondition.fromRawValue(it) }
                ?.let { " in ${weatherLabel(it).lowercase(Locale.getDefault())}" }
                ?: ""
            val celestialStr = snippet.celestialSummary?.let { ", $it" } ?: ""
            val place = snippet.placeName
            if (place != null) {
                "[$dateStr – $place$weatherStr$celestialStr] \"${snippet.transcriptionPreview}\""
            } else {
                "[$dateStr$weatherStr$celestialStr] \"${snippet.transcriptionPreview}\""
            }
        }
        return "**Recent Walk Context (for continuity):**\n\n" +
            lines.joinToString(separator = "\n\n")
    }

    fun formatWeather(
        walk: Walk,
        weatherLabel: (WeatherCondition) -> String,
        imperial: Boolean,
    ): String? {
        val condition = walk.weatherCondition?.let { WeatherCondition.fromRawValue(it) }
            ?: return null
        val temp = walk.weatherTemperature ?: return null

        val parts = mutableListOf(
            weatherLabel(condition),
            formatTemperature(temp, imperial),
        )

        walk.weatherHumidity?.let { humidity ->
            parts.add("humidity ${(humidity * 100).toInt()}%")
        }

        walk.weatherWindSpeed?.let { wind ->
            parts.add(describeWind(wind))
        }

        return "Weather: ${parts.joinToString(separator = ", ")}"
    }

    fun formatMetadata(
        durationSeconds: Long,
        distanceMeters: Double,
        startTimestamp: Long,
        lunarPhase: MoonPhase,
        imperial: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val durationMin = (durationSeconds / 60L).toInt()
        val units = if (imperial) UnitSystem.Imperial else UnitSystem.Metric
        val distanceStr = WalkFormat.distance(distanceMeters, units)
        val timeOfDay = timeOfDayDescription(startTimestamp, zone)
        val illuminationPct = (lunarPhase.illumination * 100.0).roundToInt()
        return "Walk duration: $durationMin minutes | " +
            "Distance: $distanceStr | " +
            "Time: $timeOfDay on ${formatDateTime(startTimestamp, zone)} | " +
            "Moon: ${lunarPhase.name} ($illuminationPct% illumination)"
    }

    fun timeOfDayDescription(
        timestamp: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val hour = Instant.ofEpochMilli(timestamp).atZone(zone).hour
        return when (hour) {
            in 5..8 -> "early morning"
            in 9..11 -> "morning"
            in 12..13 -> "midday"
            in 14..16 -> "afternoon"
            in 17..19 -> "evening"
            else -> "night"
        }
    }

    fun speakingPaceLabel(wpm: Double): String = when {
        wpm < 100.0 -> "slow/thoughtful"
        wpm < 140.0 -> "measured"
        wpm < 170.0 -> "conversational"
        else -> "rapid/energized"
    }

    fun formatCoord(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.5f, %.5f", lat, lon)

    fun formatPace(metersPerSecond: Double, imperial: Boolean): String {
        if (metersPerSecond <= 0.0) return "—"
        val metersPerUnit = if (imperial) 1609.34 else 1000.0
        val label = if (imperial) "min/mi" else "min/km"
        val secondsPerUnit = metersPerUnit / metersPerSecond
        val totalSec = secondsPerUnit.toInt()
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%d:%02d %s", minutes, seconds, label)
    }

    fun formatCelestial(snapshot: CelestialSnapshot): String {
        val tropical = snapshot.system == ZodiacSystem.Tropical
        val systemLabel = if (tropical) "Tropical" else "Sidereal"

        val parts = snapshot.positions.map { position ->
            val zodiac = if (tropical) position.tropical else position.sidereal
            val entry = StringBuilder()
            entry.append(position.planet.displayName)
                .append(" in ")
                .append(zodiac.sign.displayName)
                .append(" (")
                .append(zodiac.degree.toInt())
                .append('°')
                .append(')')
            if (position.isRetrograde) entry.append(" Rx")
            entry.toString()
        }

        val line = StringBuilder()
        line.append("**Celestial Context (").append(systemLabel).append("):** ")
            .append(parts.joinToString(separator = " | "))
        line.append(" | Hour of ").append(snapshot.planetaryHour.planet.displayName)

        snapshot.elementBalance.dominant?.let { dominant ->
            line.append(" | ").append(dominant.displayName).append(" predominates")
        }

        snapshot.positions.filter { it.isIngress }.forEach { ingress ->
            val zodiac = if (tropical) ingress.tropical else ingress.sidereal
            line.append(" | ").append(ingress.planet.displayName)
                .append(" enters ").append(zodiac.sign.displayName)
        }

        snapshot.seasonalMarker?.let { marker ->
            line.append(" — ").append(marker.displayName)
        }

        return line.toString()
    }

    /**
     * Short localized time-of-day string ("9:41 AM"). Promoted from
     * `private` to `internal` so [PromptAssembler] can render waypoint
     * + photo headers using the same time format the recordings and
     * meditations sections use.
     */
    internal fun formatTime(timestamp: Long, zone: ZoneId): String =
        timeFormatter.format(Instant.ofEpochMilli(timestamp).atZone(zone))

    private fun formatDateTime(timestamp: Long, zone: ZoneId): String =
        dateTimeFormatter.format(Instant.ofEpochMilli(timestamp).atZone(zone))

    private fun formatShortDate(timestamp: Long, zone: ZoneId): String =
        shortDateFormatter.format(Instant.ofEpochMilli(timestamp).atZone(zone))

    private fun formatTemperature(celsius: Double, imperial: Boolean): String =
        if (imperial) {
            String.format(Locale.US, "%.0f°F", celsius * 9.0 / 5.0 + 32.0)
        } else {
            String.format(Locale.US, "%.0f°C", celsius)
        }

    private fun describeWind(metersPerSecond: Double): String = when {
        metersPerSecond < 2.0 -> "calm"
        metersPerSecond < 5.0 -> "gentle breeze"
        metersPerSecond < 10.0 -> "moderate wind"
        metersPerSecond < 15.0 -> "strong wind"
        else -> "very strong wind"
    }
}

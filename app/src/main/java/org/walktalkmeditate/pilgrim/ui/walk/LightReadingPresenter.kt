// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.ui.graphics.Color
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.core.celestial.PlanetaryHour
import org.walktalkmeditate.pilgrim.core.celestial.SunTimes

/**
 * Pure mapping from `core.celestial` primitives to UI-ready strings
 * and colors. Keeps rendering logic out of the `Composable` so tests
 * can exercise the copy without a Compose runtime.
 *
 * All English literals live here for now. Phase 10 will extract to
 * `strings.xml` for localization.
 */
internal object LightReadingPresenter {

    /** "HH:mm" formatter, locale-independent. */
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Moon-phase emoji matching the 8 canonical names produced by
     * [MoonCalc]. Unknown names fall back to the full-moon glyph —
     * the calculator never emits outside the 8, so the fallback is
     * purely defensive.
     */
    fun phaseEmoji(phaseName: String): String = when (phaseName) {
        "New Moon" -> "\uD83C\uDF11"          // 🌑
        "Waxing Crescent" -> "\uD83C\uDF12"   // 🌒
        "First Quarter" -> "\uD83C\uDF13"     // 🌓
        "Waxing Gibbous" -> "\uD83C\uDF14"    // 🌔
        "Full Moon" -> "\uD83C\uDF15"         // 🌕
        "Waning Gibbous" -> "\uD83C\uDF16"    // 🌖
        "Last Quarter" -> "\uD83C\uDF17"      // 🌗
        "Waning Crescent" -> "\uD83C\uDF18"   // 🌘
        else -> "\uD83C\uDF15"                // 🌕 default
    }

    /** "Waxing Gibbous · 78% lit" */
    fun moonLine(moon: MoonPhase): String {
        val percent = (moon.illumination * 100).roundToInt().coerceIn(0, 100)
        return "${moon.name} · $percent% lit"
    }

    /** "Hour of Venus · Friday" */
    fun planetaryHourLine(hour: PlanetaryHour): String {
        val dayName = dayOfWeekForRuler(hour.dayRuler)
        return "Hour of ${hour.planet.name} · $dayName"
    }

    /**
     * "Sunrise 03:47 · Sunset 19:58" in [zoneId].
     *
     * Returns null when:
     *  - [sun] is null (no GPS fix on the walk); or
     *  - both [SunTimes.sunrise] and [SunTimes.sunset] are null (polar
     *    day/night).
     *
     * Renders only the non-null half if exactly one is null —
     * defensive; [SunCalc] nullifies both together today, but a future
     * refactor might decouple them.
     */
    fun sunLine(sun: SunTimes?, zoneId: ZoneId): String? {
        if (sun == null) return null
        val sunrise = sun.sunrise
        val sunset = sun.sunset
        if (sunrise == null && sunset == null) return null
        val fmt = timeFormatter.withZone(zoneId)
        return when {
            sunrise != null && sunset != null ->
                "Sunrise ${fmt.format(sunrise)} · Sunset ${fmt.format(sunset)}"
            sunrise != null -> "Sunrise ${fmt.format(sunrise)}"
            else -> "Sunset ${fmt.format(sunset)}"
        }
    }

    /**
     * Muted classical color per Chaldean planet. Intentionally
     * desaturated to sit on the parchment background without
     * shouting. Used as a small dot next to the planetary-hour line.
     */
    fun planetDotColor(planet: Planet): Color = when (planet) {
        Planet.Saturn -> Color(0xFF6B6359)   // desaturated gray-brown
        Planet.Jupiter -> Color(0xFFD4A87A)  // muted gold
        Planet.Mars -> Color(0xFFA0634B)     // rust
        Planet.Sun -> Color(0xFFC4956A)      // dawn yellow-gold
        Planet.Venus -> Color(0xFFC9A8B0)    // muted rose
        Planet.Mercury -> Color(0xFF7A9CB8)  // muted blue
        Planet.Moon -> Color(0xFFB8AFA2)     // fog
    }

    private fun dayOfWeekForRuler(ruler: Planet): String = when (ruler) {
        Planet.Sun -> "Sunday"
        Planet.Moon -> "Monday"
        Planet.Mars -> "Tuesday"
        Planet.Mercury -> "Wednesday"
        Planet.Jupiter -> "Thursday"
        Planet.Venus -> "Friday"
        Planet.Saturn -> "Saturday"
    }
}

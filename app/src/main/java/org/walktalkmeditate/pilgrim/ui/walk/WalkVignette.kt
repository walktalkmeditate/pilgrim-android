// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.turningMarkerForToday
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.turningAccentColor

/**
 * Small ambient pill row anchored at the bottom-end of the walk /
 * pre-walk map — iOS parity `WeatherVignetteView` + `CelestialVignetteView`
 * (`ActiveWalkView.swift:447-456@v1.6.0`).
 *
 * Each pill starts COLLAPSED and toggles to an expanded form on tap
 * (iOS `WeatherVignetteView.swift:8-45` / `CelestialVignetteView.swift:7-37`):
 *
 * - **Weather pill**: collapsed = condition glyph + temperature.
 *   Expanded adds humidity % + a wind descriptor.
 * - **Celestial pill**: collapsed = planetary-hour planet symbol +
 *   moon-sign glyph. Expanded adds the compact sun-sign / retrograde
 *   summary in [pilgrimColors] `fog`. Only rendered when
 *   [celestialAwarenessEnabled].
 *
 * Both are quiet capsule chips on `parchmentSecondary`. Either may be
 * null/absent independently; the row renders nothing when both are.
 *
 * On solstice/equinox days the celestial chip gains a soft turning-colored
 * corona (iOS `CelestialVignetteView.turningHalo`, `:36-51@v1.6.0`).
 * [turning] defaults to today's marker; callers may inject one for tests.
 */
@Composable
fun WalkVignette(
    weather: WeatherSnapshot?,
    celestial: CelestialSnapshot?,
    celestialAwarenessEnabled: Boolean,
    units: UnitSystem,
    modifier: Modifier = Modifier,
    turning: SeasonalMarker? = turningMarkerForToday(),
) {
    val showCelestial = celestialAwarenessEnabled && celestial != null
    if (weather == null && !showCelestial) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCelestial && celestial != null) {
            CelestialChip(celestial, turning)
        }
        if (weather != null) {
            WeatherChip(weather, units)
        }
    }
}

@Composable
private fun WeatherChip(weather: WeatherSnapshot, units: UnitSystem) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val tempLabel = formatTemperature(weather.temperatureCelsius, units)
    val conditionLabel = stringResource(weather.condition.labelRes)
    val a11yLabel = stringResource(
        R.string.weather_vignette_a11y_label,
        conditionLabel,
        tempLabel,
    )
    val a11yHint = stringResource(R.string.weather_vignette_a11y_hint)
    Row(
        modifier = chipModifier()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { expanded = !expanded }
            .semantics {
                contentDescription = "$a11yLabel. $a11yHint"
            }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(weather.condition.iconRes),
            contentDescription = null,
            tint = pilgrimColors.ink,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = tempLabel,
            style = pilgrimType.caption,
            color = pilgrimColors.ink,
        )
        if (expanded) {
            Text(
                text = formatHumidity(weather.humidityFraction),
                style = pilgrimType.caption,
                color = pilgrimColors.ink,
            )
            Text(
                text = stringResource(windDescriptionRes(weather.windSpeedMps)),
                style = pilgrimType.caption,
                color = pilgrimColors.ink,
            )
        }
    }
}

@Composable
private fun CelestialChip(snapshot: CelestialSnapshot, turning: SeasonalMarker?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val planetSymbol = snapshot.planetaryHour.planet.symbol
    val moonGlyph = snapshot.moonZodiacSymbol()
    val collapsedText = if (moonGlyph != null) "$planetSymbol  $moonGlyph" else planetSymbol
    val a11yLabel = celestialAccessibilityText(snapshot)
    val a11yHint = stringResource(R.string.celestial_vignette_a11y_hint)
    val summary = celestialCompactSummary(snapshot)
    val haloAccent = turningAccentColor(turning, pilgrimColors)
    Row(
        modifier = chipModifier(haloAccent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { expanded = !expanded }
            .semantics {
                contentDescription = "$a11yLabel. $a11yHint"
            }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = collapsedText,
            style = pilgrimType.caption,
            color = pilgrimColors.ink,
        )
        if (expanded && summary.isNotEmpty()) {
            Text(
                text = summary,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

/**
 * Capsule chip. When [haloAccent] is non-null (a turning day) the chip
 * gains a turning-colored corona — a 1.5dp stroke at 0.55 opacity plus a
 * soft colored shadow — matching iOS `CelestialVignetteView.turningHalo`
 * (`stroke(color.opacity(0.55), 1.5)` + `shadow(color.opacity(0.4), 4)`).
 */
@Composable
private fun chipModifier(haloAccent: Color? = null): Modifier {
    val shape = RoundedCornerShape(percent = 50)
    return Modifier
        .then(
            if (haloAccent != null) {
                // iOS `shadow(color: color.opacity(0.4), radius: 4)` — the
                // glow is dimmed to 0.4 so it reads as a soft corona, not a
                // vivid accent ring.
                val shadowColor = haloAccent.copy(alpha = 0.4f)
                Modifier.shadow(
                    elevation = 4.dp,
                    shape = shape,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
            } else {
                Modifier
            },
        )
        .clip(shape)
        .background(pilgrimColors.parchmentSecondary)
        .then(
            if (haloAccent != null) {
                Modifier.border(1.5.dp, haloAccent.copy(alpha = 0.55f), shape)
            } else {
                Modifier
            },
        )
        .padding(horizontal = 10.dp, vertical = 5.dp)
}

private fun formatTemperature(celsius: Double, units: UnitSystem): String =
    if (units == UnitSystem.Imperial) {
        String.format(Locale.US, "%d°", Math.round(celsius * 9.0 / 5.0 + 32.0))
    } else {
        String.format(Locale.US, "%d°", Math.round(celsius))
    }

private fun formatHumidity(fraction: Double?): String =
    String.format(Locale.US, "%d%%", Math.round((fraction ?: 0.0) * 100.0))

/**
 * iOS parity `WeatherService.windDescription` (verbatim thresholds,
 * m/s). Null wind speed degrades to "calm" (iOS treats a missing
 * `windSpeed` as 0 via `walk.weatherWindSpeed ?? 0`).
 */
private fun windDescriptionRes(metersPerSecond: Double?): Int {
    val mps = metersPerSecond ?: 0.0
    return when {
        mps < 2.0 -> R.string.wind_calm
        mps < 5.0 -> R.string.wind_gentle_breeze
        mps < 10.0 -> R.string.wind_moderate
        mps < 15.0 -> R.string.wind_strong
        else -> R.string.wind_very_strong
    }
}

/**
 * iOS parity `CelestialVignetteView.compactSummary` — sun symbol +
 * sun-sign symbol, then retrograde planets as "<symbol>Rx" joined by
 * spaces. The Android [CelestialSnapshot] exposes the same data
 * (`position(Planet.Sun)`, `system`, `positions[*].isRetrograde`).
 */
private fun celestialCompactSummary(snapshot: CelestialSnapshot): String {
    val parts = mutableListOf<String>()
    snapshot.position(Planet.Sun)?.let { sun ->
        val zodiac = if (snapshot.system == ZodiacSystem.Tropical) sun.tropical else sun.sidereal
        parts += "${sun.planet.symbol}${zodiac.sign.symbol}"
    }
    val retrogrades = snapshot.positions.filter { it.isRetrograde }
    if (retrogrades.isNotEmpty()) {
        parts += retrogrades.joinToString(" ") { "${it.planet.symbol}Rx" }
    }
    return parts.joinToString(" ")
}

/**
 * iOS parity `CelestialVignetteView.accessibilityText` — Sun/Moon
 * sign, planetary hour, retrograde planets, joined by ", ".
 */
private fun celestialAccessibilityText(snapshot: CelestialSnapshot): String {
    val parts = mutableListOf<String>()
    snapshot.position(Planet.Sun)?.let { sun ->
        val z = if (snapshot.system == ZodiacSystem.Tropical) sun.tropical else sun.sidereal
        parts += "Sun in ${z.sign.displayName}"
    }
    snapshot.position(Planet.Moon)?.let { moon ->
        val z = if (snapshot.system == ZodiacSystem.Tropical) moon.tropical else moon.sidereal
        parts += "Moon in ${z.sign.displayName}"
    }
    parts += "Hour of ${snapshot.planetaryHour.planet.displayName}"
    val retrogrades = snapshot.positions.filter { it.isRetrograde }
    if (retrogrades.isNotEmpty()) {
        parts += "${retrogrades.joinToString(", ") { it.planet.displayName }} retrograde"
    }
    return parts.joinToString(", ")
}

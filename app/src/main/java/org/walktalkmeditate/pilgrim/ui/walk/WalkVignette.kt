// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Small ambient pill row anchored at the bottom-end of the walk /
 * pre-walk map — iOS parity `WeatherVignetteView` + `CelestialVignetteView`
 * (`ActiveWalkView.swift:447-456`).
 *
 * - **Weather pill**: condition glyph + temperature (°C / °F by the
 *   user's distance-unit preference).
 * - **Celestial pill**: planetary-hour planet symbol + moon-sign glyph.
 *   Only rendered when [celestialAwarenessEnabled] (iOS gates it on
 *   `UserPreferences.celestialAwarenessEnabled`).
 *
 * Both are quiet capsule chips on `parchmentSecondary`. Either may be
 * null/absent independently; the row renders nothing when both are.
 * `accessibilityHidden`-equivalent: the chips carry their own concise
 * text so TalkBack reads "Clear, 18°".
 */
@Composable
fun WalkVignette(
    weather: WeatherSnapshot?,
    celestial: CelestialSnapshot?,
    celestialAwarenessEnabled: Boolean,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val showCelestial = celestialAwarenessEnabled && celestial != null
    if (weather == null && !showCelestial) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCelestial && celestial != null) {
            CelestialChip(celestial)
        }
        if (weather != null) {
            WeatherChip(weather, units)
        }
    }
}

@Composable
private fun WeatherChip(weather: WeatherSnapshot, units: UnitSystem) {
    val tempLabel = formatTemperature(weather.temperatureCelsius, units)
    Row(
        modifier = chipModifier(),
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
            text = stringResource(weather.condition.labelRes) + "  " + tempLabel,
            style = pilgrimType.caption,
            color = pilgrimColors.ink,
        )
    }
}

@Composable
private fun CelestialChip(snapshot: CelestialSnapshot) {
    val planetSymbol = snapshot.planetaryHour.planet.symbol
    val moonGlyph = snapshot.moonZodiacSymbol()
    Row(
        modifier = chipModifier(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (moonGlyph != null) "$planetSymbol  $moonGlyph" else planetSymbol,
            style = pilgrimType.caption,
            color = pilgrimColors.ink,
        )
    }
}

@Composable
private fun chipModifier(): Modifier = Modifier
    .clip(RoundedCornerShape(percent = 50))
    .background(pilgrimColors.parchmentSecondary)
    .padding(horizontal = 10.dp, vertical = 5.dp)

private fun formatTemperature(celsius: Double, units: UnitSystem): String =
    if (units == UnitSystem.Imperial) {
        String.format(Locale.US, "%d°", Math.round(celsius * 9.0 / 5.0 + 32.0))
    } else {
        String.format(Locale.US, "%d°", Math.round(celsius))
    }

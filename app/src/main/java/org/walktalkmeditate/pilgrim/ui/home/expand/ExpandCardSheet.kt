// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.ui.design.scenery.footprintPath
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Floating, all-corners-rounded card that appears when a Journal dot
 * is tapped. iOS parity `InkScrollView.swift:301-401@db4196e`:
 * VStack overlaid with `.ultraThinMaterial` background tinted by
 * `seasonColor.opacity(0.10)`, RoundedRectangle(16) corners, ink-drop
 * shadow. Previously rendered through `ModalBottomSheet` which added
 * a system scrim + drag handle + top-corner-only shape that iOS does
 * not have.
 *
 * Caller wraps this in `AnimatedVisibility(expandedSnap != null)`
 * with a slide-from-bottom + fade enter/exit; this composable itself
 * is unconditional once visible.
 *
 * Stage 4-B lesson: `rememberUpdatedState(onDismissRequest)` for the
 * dismiss callback used inside delayed lambdas — guards against stale
 * closure if the parent recomposes mid-tap.
 *
 * Stage 6-B lesson: date formatter uses
 * `DateTimeFormatter.ofLocalizedDateTime(...).withLocale(...)` —
 * never the no-Locale `ofPattern` overload.
 */
@Composable
fun ExpandCardSheet(
    snapshot: WalkSnapshot,
    celestial: CelestialSnapshot?,
    seasonColor: Color,
    units: UnitSystem,
    isShared: Boolean,
    onViewDetails: (Long) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberUpdatedState(onDismissRequest)
    BackHandler(enabled = true) { dismissState.value() }

    val dateText = remember(snapshot.startMs) {
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(snapshot.startMs))
    }

    val baseContainerColor = pilgrimColors.parchmentSecondary
    val seasonOverlay = seasonColor.copy(alpha = 0.10f)
    val dividerColor = seasonColor.copy(alpha = 0.15f)
    val buttonContainer = pilgrimColors.stone.copy(alpha = 0.8f)
    val buttonContent = pilgrimColors.parchment
    val buttonStyle = pilgrimType.annotation

    // Outer Box gets a clickable that dismisses on tap-outside-the-card
    // (iOS sheet binding has the same affordance). The card itself
    // installs its own non-dismissing clickable so taps inside don't
    // fall through. indication = null on both so neither shows ripple.
    val outerInteraction = remember { MutableInteractionSource() }
    val innerInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = outerInteraction,
                indication = null,
                onClick = { dismissState.value() },
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // The floating card. All corners rounded to 16dp (iOS
        // RoundedRectangle(16)); seasonColor.opacity(0.10) layered on
        // top of parchmentSecondary approximates iOS .ultraThinMaterial
        // tinted by seasonColor.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(baseContainerColor)
                .clickable(
                    interactionSource = innerInteraction,
                    indication = null,
                    onClick = { /* absorb taps inside the card */ },
                ),
        ) {
            // seasonColor tint layered on top of the base.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(seasonOverlay),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PilgrimSpacing.normal, vertical = 16.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HeaderRow(
                        snapshot = snapshot,
                        celestial = celestial,
                        seasonColor = seasonColor,
                        isShared = isShared,
                        dateText = dateText,
                    )
                    Box(
                        Modifier.fillMaxWidth().height(1.dp).background(dividerColor),
                    )
                    StatsRow(snapshot = snapshot, units = units)
                    MiniActivityBar(snapshot = snapshot)
                    ActivityPills(snapshot = snapshot)
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            val id = snapshot.id
                            dismissState.value()
                            onViewDetails(id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(percent = 50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonContainer,
                            contentColor = buttonContent,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.journal_expand_view_details),
                            style = buttonStyle,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(
    snapshot: WalkSnapshot,
    celestial: CelestialSnapshot?,
    seasonColor: Color,
    isShared: Boolean,
    dateText: String,
) {
    val footprintColor = seasonColor.copy(alpha = 0.3f)
    val sharedTint = pilgrimColors.stone.copy(alpha = 0.5f)
    val celestialColor = pilgrimColors.fog
    val annotationStyle = pilgrimType.annotation
    val inkColor = pilgrimColors.ink
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Canvas(modifier = Modifier.size(width = 12.dp, height = 18.dp)) {
            drawPath(
                path = footprintPath(Size(size.width, size.height)),
                color = footprintColor,
            )
        }
        snapshot.favicon?.let { key ->
            WalkFavicon.fromRawValue(key)?.let { fav ->
                Icon(
                    imageVector = fav.icon,
                    contentDescription = null,
                    tint = seasonColor,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = dateText,
            style = annotationStyle,
            color = inkColor,
        )
        Spacer(Modifier.weight(1f))
        if (isShared) {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = sharedTint,
                modifier = Modifier.size(10.dp),
            )
        }
        if (celestial != null) {
            val moonSymbol = celestial.moonZodiacSymbol().orEmpty()
            Text(
                text = "${celestial.planetaryHour.planet.symbol}$moonSymbol",
                style = TextStyle(fontSize = 10.sp),
                color = celestialColor,
            )
        }
        snapshot.weatherCondition?.let { raw ->
            WeatherCondition.fromRawValue(raw)?.let { wc ->
                Icon(
                    painter = painterResource(wc.iconRes),
                    contentDescription = null,
                    tint = celestialColor,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun StatsRow(snapshot: WalkSnapshot, units: UnitSystem) {
    val distLabel = WalkFormat.distanceLabel(snapshot.distanceM, units)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpandStat(
            value = "${distLabel.value} ${distLabel.unit}",
            labelRes = R.string.journal_expand_label_distance,
        )
        Spacer(Modifier.weight(1f))
        ExpandStat(
            value = WalkFormat.duration((snapshot.durationSec * 1000.0).toLong()),
            labelRes = R.string.journal_expand_label_duration,
        )
        Spacer(Modifier.weight(1f))
        ExpandStat(
            value = WalkFormat.pace(
                secondsPerKm = snapshot.averagePaceSecPerKm.takeIf { it > 0 },
                units = units,
            ),
            labelRes = R.string.journal_expand_label_pace,
        )
    }
}

@Composable
private fun ExpandStat(value: String, labelRes: Int) {
    val statValue = pilgrimType.statValue
    val microStyle = pilgrimType.micro
    val inkColor = pilgrimColors.ink
    val fogColor = pilgrimColors.fog
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = statValue, color = inkColor)
        Text(stringResource(labelRes), style = microStyle, color = fogColor)
    }
}

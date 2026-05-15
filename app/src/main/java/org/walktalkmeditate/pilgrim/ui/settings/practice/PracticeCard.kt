// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.practice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingPicker
import org.walktalkmeditate.pilgrim.ui.settings.SettingToggle
import org.walktalkmeditate.pilgrim.ui.settings.SettingsDivider
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Settings → Practice card. iOS-faithful port of
 * `pilgrim-ios/Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift`.
 *
 * Surfaces five toggles + two segmented pickers covering how the user
 * walks: intention prompt, celestial awareness (with conditional zodiac
 * picker), distance units (with derived caption), collective opt-in,
 * and the photo reliquary (with a denial note when access is refused).
 *
 * iOS's "Auto-play nearby whispers" toggle is INTENTIONALLY DEFERRED —
 * Android has no whispers feature in this milestone (Stage 10 master
 * spec Non-goals).
 *
 * State is driven entirely by the parent — Chunk F wires
 * [SettingsViewModel] StateFlows into these parameters; Chunk E adds
 * the photo-permission integration that flips
 * [showPhotosDeniedNote].
 */
@Composable
fun PracticeCard(
    beginWithIntention: Boolean,
    onSetBeginWithIntention: (Boolean) -> Unit,
    celestialAwareness: Boolean,
    onSetCelestialAwareness: (Boolean) -> Unit,
    zodiacSystem: ZodiacSystem,
    onSetZodiacSystem: (ZodiacSystem) -> Unit,
    distanceUnits: UnitSystem,
    onSetDistanceUnits: (UnitSystem) -> Unit,
    walkWithCollective: Boolean,
    onSetWalkWithCollective: (Boolean) -> Unit,
    walkReliquary: Boolean,
    onSetWalkReliquary: (Boolean) -> Unit,
    showPhotosDeniedNote: Boolean,
    modifier: Modifier = Modifier,
) {
    // Resolve string resources once, then remember the resulting
    // List<Pair<String, T>>s so the segmented rows don't see freshly-
    // allocated lists on every theme recompose (Stage 10-A polish
    // lesson — keyed on resolved strings, not the lambda call).
    val zodiacOptions = run {
        val tropical = stringResource(R.string.settings_zodiac_tropical)
        val sidereal = stringResource(R.string.settings_zodiac_sidereal)
        remember(tropical, sidereal) {
            listOf(
                tropical to ZodiacSystem.Tropical,
                sidereal to ZodiacSystem.Sidereal,
            )
        }
    }
    val unitsOptions = run {
        val metric = stringResource(R.string.settings_units_metric)
        val imperial = stringResource(R.string.settings_units_imperial)
        remember(metric, imperial) {
            listOf(
                metric to UnitSystem.Metric,
                imperial to UnitSystem.Imperial,
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .settingsCard(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_practice_title),
            subtitle = stringResource(R.string.settings_practice_subtitle),
        )

        SettingToggle(
            label = stringResource(R.string.settings_begin_with_intention_label),
            description = stringResource(R.string.settings_begin_with_intention_description),
            checked = beginWithIntention,
            onCheckedChange = onSetBeginWithIntention,
        )

        SettingToggle(
            label = stringResource(R.string.settings_celestial_awareness_label),
            description = stringResource(R.string.settings_celestial_awareness_description),
            checked = celestialAwareness,
            onCheckedChange = onSetCelestialAwareness,
        )

        // Mirrors iOS's `.animation(.easeInOut(duration: 0.2), value: celestialAwareness)`.
        AnimatedVisibility(
            visible = celestialAwareness,
            enter = fadeIn(animationSpec = tween(200)) +
                expandVertically(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)) +
                shrinkVertically(animationSpec = tween(200)),
        ) {
            SettingPicker(
                label = stringResource(R.string.settings_zodiac_system_label),
                options = zodiacOptions,
                selected = zodiacSystem,
                onSelect = onSetZodiacSystem,
            )
        }

        SettingsDivider()

        SettingPicker(
            label = stringResource(R.string.settings_units_label),
            options = unitsOptions,
            selected = distanceUnits,
            onSelect = onSetDistanceUnits,
        )

        // The Imperial caption ("mi · min/mi · ft · °F") is wider
        // than the Metric one ("km · min/km · m · °C") and clips on a
        // 360dp screen if `maxLines = 1`. Allow up to 2 lines so °F
        // never disappears off the right edge. iOS lets it wrap freely.
        Text(
            text = stringResource(
                if (distanceUnits == UnitSystem.Metric) {
                    R.string.settings_units_caption_metric
                } else {
                    R.string.settings_units_caption_imperial
                },
            ),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        SettingsDivider()

        SettingToggle(
            label = stringResource(R.string.settings_collective_label),
            description = stringResource(R.string.settings_collective_description),
            checked = walkWithCollective,
            onCheckedChange = onSetWalkWithCollective,
        )

        SettingToggle(
            label = stringResource(R.string.settings_walk_reliquary_label),
            description = stringResource(R.string.settings_walk_reliquary_description),
            checked = walkReliquary,
            onCheckedChange = onSetWalkReliquary,
        )

        // Mirrors iOS's `.animation(.easeInOut(duration: 0.2), value: showPhotosDeniedNote)`.
        AnimatedVisibility(
            visible = showPhotosDeniedNote,
            enter = fadeIn(animationSpec = tween(200)) +
                expandVertically(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)) +
                shrinkVertically(animationSpec = tween(200)),
        ) {
            Text(
                text = stringResource(R.string.settings_walk_reliquary_photos_denied_note),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

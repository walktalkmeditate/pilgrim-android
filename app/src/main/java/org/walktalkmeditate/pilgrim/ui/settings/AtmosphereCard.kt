// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode

/**
 * Settings → Atmosphere card. Surfaces the 3-option appearance picker,
 * the master Sounds toggle, and (when sounds are enabled) a nav row
 * leading into the Bells & Soundscapes sub-screen. Visually mirrors
 * iOS's flat parchment card (no border) via the shared [settingsCard]
 * modifier.
 *
 * Stage 10-B added the conditional nav row. The row is only visible
 * when [soundsEnabled] is true — when the master toggle is off there
 * is nothing to configure inside, so iOS hides the affordance to
 * avoid leading the user into a dead-end sub-screen.
 */
@Composable
fun AtmosphereCard(
    currentMode: AppearanceMode,
    onSelectMode: (AppearanceMode) -> Unit,
    soundsEnabled: Boolean,
    onSetSoundsEnabled: (Boolean) -> Unit,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `settingsCard()` bakes in both the 16dp horizontal screen indent
    // AND the 16dp card-internal padding (32dp total content-from-edge).
    // Matches iOS, where the parent VStack supplies the screen indent
    // once and each card's `.settingsCard()` adds its internal — but
    // doing it via the modifier means callers can't forget the outer.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .settingsCard(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_atmosphere_title),
            subtitle = stringResource(R.string.settings_atmosphere_subtitle),
        )
        // Resolve string resources once, then remember the resulting
        // List<Pair<String, AppearanceMode>> so SingleChoiceSegmentedButtonRow
        // doesn't see a freshly-allocated List on every PilgrimTheme
        // recompose (which would force re-measure of the segmented row).
        val autoLabel = stringResource(R.string.settings_appearance_auto)
        val lightLabel = stringResource(R.string.settings_appearance_light)
        val darkLabel = stringResource(R.string.settings_appearance_dark)
        val options = remember(autoLabel, lightLabel, darkLabel) {
            listOf(
                autoLabel to AppearanceMode.System,
                lightLabel to AppearanceMode.Light,
                darkLabel to AppearanceMode.Dark,
            )
        }
        SettingPicker(
            label = stringResource(R.string.settings_appearance_label),
            options = options,
            selected = currentMode,
            onSelect = onSelectMode,
        )
        SettingsDivider()
        SettingToggle(
            label = stringResource(R.string.settings_sounds_label),
            description = stringResource(R.string.settings_sounds_description),
            checked = soundsEnabled,
            onCheckedChange = onSetSoundsEnabled,
        )
        // Stage 10-B: when sounds are enabled, surface the nav row to
        // the SoundSettingsScreen sub-surface. AnimatedVisibility
        // matches iOS's easeInOut(0.2) reveal — same animation spec as
        // the SoundSettingsScreen's section gating, so toggling the
        // master from inside Atmosphere feels consistent with toggling
        // it from inside the sub-screen.
        AnimatedVisibility(
            visible = soundsEnabled,
            enter = fadeIn(animationSpec = tween(200)) +
                expandVertically(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)) +
                shrinkVertically(animationSpec = tween(200)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsDivider()
                SettingNavRow(
                    label = stringResource(R.string.settings_atmosphere_bells_soundscapes_row),
                    detail = stringResource(R.string.settings_atmosphere_bells_soundscapes_subtitle),
                    onClick = { onAction(SettingsAction.OpenBellsAndSoundscapes) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

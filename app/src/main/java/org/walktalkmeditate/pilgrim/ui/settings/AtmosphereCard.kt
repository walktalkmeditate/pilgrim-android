// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode

/**
 * Settings → Atmosphere card. Currently surfaces a 3-option appearance
 * picker; the iOS counterpart also has a Sounds master toggle, which
 * is deferred to a later stage (gating every audio call site is a
 * larger scope). Visually mirrors iOS's flat parchment card (no
 * border) via the shared [settingsCard] modifier.
 */
@Composable
fun AtmosphereCard(
    currentMode: AppearanceMode,
    onSelectMode: (AppearanceMode) -> Unit,
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
    }
}


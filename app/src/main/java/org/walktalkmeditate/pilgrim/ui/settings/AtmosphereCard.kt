// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
    // The outer 16dp horizontal padding indents the card slab from
    // the screen edge; `settingsCard()` then adds another 16dp of
    // padding INSIDE the card for content. Total content-from-screen-
    // edge is 32dp — same pattern as iOS, where the parent VStack
    // applies `.padding(.horizontal, 16)` and each card's
    // `.settingsCard()` applies a 16dp internal padding (totaling 32dp
    // from screen to content).
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .settingsCard(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_atmosphere_title),
            subtitle = stringResource(R.string.settings_atmosphere_subtitle),
        )
        SettingPicker(
            label = stringResource(R.string.settings_appearance_label),
            options = APPEARANCE_OPTIONS.map { stringResource(it.labelRes) to it.mode },
            selected = currentMode,
            onSelect = onSelectMode,
        )
    }
}

private data class AppearanceOption(val mode: AppearanceMode, val labelRes: Int)

private val APPEARANCE_OPTIONS: List<AppearanceOption> = listOf(
    AppearanceOption(AppearanceMode.System, R.string.settings_appearance_auto),
    AppearanceOption(AppearanceMode.Light, R.string.settings_appearance_light),
    AppearanceOption(AppearanceMode.Dark, R.string.settings_appearance_dark),
)

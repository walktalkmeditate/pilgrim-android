// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Settings → Atmosphere card. Currently surfaces a 3-option appearance
 * picker; the iOS counterpart also has a Sounds master toggle, which
 * is deferred to a later stage (gating every audio call site is a
 * larger scope). Visually mirrors iOS's flat card with subtle ink-stroke
 * border.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtmosphereCard(
    currentMode: AppearanceMode,
    onSelectMode: (AppearanceMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.5f))
            .border(1.dp, pilgrimColors.fog.copy(alpha = 0.2f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.settings_atmosphere_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
            Text(
                text = stringResource(R.string.settings_atmosphere_subtitle),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.settings_appearance_label),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            APPEARANCE_OPTIONS.forEachIndexed { index, option ->
                val selected = option.mode == currentMode
                SegmentedButton(
                    selected = selected,
                    onClick = { onSelectMode(option.mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = APPEARANCE_OPTIONS.size,
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = pilgrimColors.stone,
                        activeContentColor = pilgrimColors.parchment,
                        inactiveContainerColor = pilgrimColors.parchmentSecondary.copy(alpha = 0f),
                        inactiveContentColor = pilgrimColors.fog,
                        activeBorderColor = pilgrimColors.stone,
                        inactiveBorderColor = pilgrimColors.fog.copy(alpha = 0.3f),
                    ),
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }
    }
}

private data class AppearanceOption(val mode: AppearanceMode, val labelRes: Int)

private val APPEARANCE_OPTIONS: List<AppearanceOption> = listOf(
    AppearanceOption(AppearanceMode.System, R.string.settings_appearance_auto),
    AppearanceOption(AppearanceMode.Light, R.string.settings_appearance_light),
    AppearanceOption(AppearanceMode.Dark, R.string.settings_appearance_dark),
)

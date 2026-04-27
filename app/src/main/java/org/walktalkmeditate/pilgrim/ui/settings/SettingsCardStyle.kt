// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Shared visual + interaction language for every Settings card. iOS
 * pulls these from `Constants.UI.cornerRadius` + `parchmentSecondary`;
 * we centralize them here so individual cards (Atmosphere, Voice,
 * Bells, Recordings, ExportImport, About, Feedback) only declare their
 * content, not the chrome.
 *
 * Note: a `Modifier` extension that needs to read CompositionLocals
 * (`pilgrimColors`) MUST go through `Modifier.composed { ... }` —
 * making the extension itself `@Composable` doesn't work because
 * `Modifier` is not a Composable function receiver.
 */
fun Modifier.settingsCard(): Modifier = composed {
    clip(RoundedCornerShape(16.dp))
        .background(pilgrimColors.parchmentSecondary)
        .padding(16.dp)
}

/**
 * Card title + caption header. Used at the top of every settings card
 * to introduce the section.
 */
@Composable
fun CardHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
        )
        Text(
            text = subtitle,
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

/**
 * Toggle row: label + description on the left, M3 `Switch` on the
 * right. Mirrors iOS's `Toggle` row with description copy.
 */
@Composable
fun SettingToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = pilgrimType.body,
                color = pilgrimColors.ink,
            )
            Text(
                text = description,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = pilgrimColors.parchment,
                checkedTrackColor = pilgrimColors.stone,
                uncheckedThumbColor = pilgrimColors.fog,
                uncheckedTrackColor = pilgrimColors.parchmentTertiary,
            ),
        )
    }
}

/**
 * Single-choice picker rendered with M3 `SingleChoiceSegmentedButtonRow`.
 * Used by AtmosphereCard's Appearance picker and (Stage 10-B onward)
 * any other 2-3-option setting (Hemisphere, Distance Units).
 *
 * `widthIn(max = 180.dp)` caps the segmented row so a long label
 * column on the left can't get squeezed; iOS uses `Picker` with
 * `.pickerStyle(.segmented)` which auto-equal-widths to the trailing
 * edge — we approximate via the cap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingPicker(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.widthIn(max = 180.dp),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option.second == selected
                SegmentedButton(
                    selected = isSelected,
                    onClick = { onSelect(option.second) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = pilgrimColors.stone,
                        activeContentColor = pilgrimColors.parchment,
                        inactiveContainerColor = pilgrimColors.parchmentSecondary.copy(alpha = 0f),
                        // Faded ink instead of fog: fog (#6B6359 in dark mode)
                        // on parchmentSecondary at alpha 0.5 falls below WCAG
                        // AA; ink-at-60% holds contrast in both light and dark.
                        inactiveContentColor = pilgrimColors.ink.copy(alpha = 0.6f),
                        activeBorderColor = pilgrimColors.stone,
                        inactiveBorderColor = pilgrimColors.fog.copy(alpha = 0.3f),
                    ),
                    label = {
                        Text(
                            text = option.first,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Tappable row leading into a sub-screen. Optional leading icon
 * (24dp, stone-tinted) + optional trailing detail caption (e.g.
 * "Brother Crane"). Defaults to a chevron-right; pass `external =
 * true` for system-intent destinations (Custom Tabs, Play Store) so
 * the trailing glyph reads as "outbound" instead of "deeper into the
 * app".
 */
@Composable
fun SettingNavRow(
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    leadingIcon: ImageVector? = null,
    external: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = label,
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.weight(1f))
        if (detail != null) {
            Text(
                text = detail,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Compose has no `minimumScaleFactor` equivalent; cap
                // the detail caption width so it never elbows out the
                // chevron.
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = if (external) {
                Icons.AutoMirrored.Filled.OpenInNew
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            },
            contentDescription = null,
            tint = pilgrimColors.fog,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Hairline divider between rows inside a card. Subtler than
 * `HorizontalDivider`'s default outline tint.
 */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = pilgrimColors.fog.copy(alpha = 0.2f),
    )
}

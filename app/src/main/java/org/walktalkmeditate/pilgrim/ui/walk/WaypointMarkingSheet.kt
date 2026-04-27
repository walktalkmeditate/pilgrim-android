// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

internal const val MAX_WAYPOINT_CUSTOM_CHARS = 50
internal const val WAYPOINT_CUSTOM_ICON_KEY = "mappin"

internal data class WaypointPresetChip(
    val labelRes: Int,
    val iconKey: String,
)

private val PRESET_CHIPS: List<WaypointPresetChip> = listOf(
    WaypointPresetChip(R.string.walk_waypoint_chip_peaceful, "leaf"),
    WaypointPresetChip(R.string.walk_waypoint_chip_beautiful, "eye"),
    WaypointPresetChip(R.string.walk_waypoint_chip_grateful, "heart"),
    WaypointPresetChip(R.string.walk_waypoint_chip_resting, "figure.seated.side"),
    WaypointPresetChip(R.string.walk_waypoint_chip_inspired, "sparkles"),
    WaypointPresetChip(R.string.walk_waypoint_chip_arrived, "flag.fill"),
)

/**
 * Maps an iOS-canonical SF Symbol icon key (stored in Room's `waypoints.icon`
 * column) to a Material Icon for Android display. Storing the iOS key
 * round-trips `.pilgrim` ZIP exports across platforms; unknown keys fall
 * back to `LocationOn` so a future iOS-introduced symbol still renders.
 */
internal fun iconKeyToVector(key: String): ImageVector = when (key) {
    "leaf" -> Icons.Outlined.Spa
    "eye" -> Icons.Outlined.Visibility
    "heart" -> Icons.Outlined.FavoriteBorder
    "figure.seated.side" -> Icons.Outlined.Chair
    "sparkles" -> Icons.Outlined.AutoAwesome
    "flag.fill" -> Icons.Filled.Flag
    "mappin" -> Icons.Filled.LocationOn
    else -> Icons.Filled.LocationOn
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointMarkingSheet(
    onMark: (label: String, icon: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current
    var customText by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = PilgrimSpacing.big,
                vertical = PilgrimSpacing.normal,
            ),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        ) {
            Text(
                text = stringResource(R.string.walk_waypoint_marking_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(bottom = PilgrimSpacing.small),
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
                verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
                userScrollEnabled = false,
                modifier = Modifier.height(200.dp),
            ) {
                items(PRESET_CHIPS) { chip ->
                    val label = stringResource(chip.labelRes)
                    PresetChip(
                        label = label,
                        iconKey = chip.iconKey,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMark(label, chip.iconKey)
                        },
                    )
                }
            }

            CustomNoteRow(
                text = customText,
                onTextChange = { incoming ->
                    customText = incoming.take(MAX_WAYPOINT_CUSTOM_CHARS)
                },
                onMark = {
                    val trimmed = customText.trim()
                    if (trimmed.isEmpty()) return@CustomNoteRow
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMark(trimmed, WAYPOINT_CUSTOM_ICON_KEY)
                },
            )

            CancelButton(onClick = onDismiss)
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    iconKey: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.4f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = PilgrimSpacing.normal),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            Icon(
                imageVector = iconKeyToVector(iconKey),
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = pilgrimType.caption,
                color = pilgrimColors.ink.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun CustomNoteRow(
    text: String,
    onTextChange: (String) -> Unit,
    onMark: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(stringResource(R.string.walk_waypoint_marking_custom_placeholder))
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onMark,
                enabled = text.trim().isNotEmpty(),
            ) {
                Text(
                    text = stringResource(R.string.walk_waypoint_marking_mark),
                    style = pilgrimType.button,
                )
            }
        }
        Text(
            text = stringResource(
                R.string.walk_waypoint_count_chars,
                text.length,
                MAX_WAYPOINT_CUSTOM_CHARS,
            ),
            style = pilgrimType.caption,
            color = pilgrimColors.fog.copy(alpha = 0.5f),
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CancelButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.walk_waypoint_marking_cancel),
            color = pilgrimColors.fog,
            style = pilgrimType.button,
        )
    }
}

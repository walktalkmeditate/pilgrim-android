// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Per-state options sheet (the "ellipsis" menu).
 *
 * Row visibility depends on the walk's lifecycle:
 *  - **Pre-walk (Idle)**: only "Set Intention" is shown. Waypoints can't
 *    be dropped before a walk row exists, so the row is hidden — not
 *    rendered at all (vs disabled-but-visible) so the sheet doesn't
 *    advertise an action that's unavailable.
 *  - **In-walk (Active|Paused)**: only "Drop Waypoint" is shown.
 *    Intention is committed at startWalk time and is no longer editable
 *    once a walk is in progress.
 *
 * If neither flag is true (e.g., Meditating, Finished), no options
 * render — the parent's auto-dismiss LaunchedEffect closes the sheet
 * before this state is reachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkOptionsSheet(
    canSetIntention: Boolean,
    intention: String?,
    onSetIntention: () -> Unit,
    waypointCount: Int,
    canDropWaypoint: Boolean,
    onDropWaypoint: () -> Unit,
    onDismiss: () -> Unit,
    // iOS parity `WalkOptionsSheet.swift:14, 222@db4196e` — whisper +
    // stone rows surface in the "Traces" section. `whispersRemaining`
    // is `7 - whispersPlacedThisWalk` (caller computes); a zero value
    // renders disabled per iOS `canPlaceWhisper`. `isWhisperUnlocked`
    // gates the visible subtitle ("Unlocks at 7 min" vs
    // "N remaining"); the row is rendered even when locked so the
    // user sees the unlock countdown.
    isWhisperUnlocked: Boolean = false,
    canPlaceWhisper: Boolean = false,
    whispersRemaining: Int = 0,
    onLeaveWhisper: () -> Unit = {},
    isStoneUnlocked: Boolean = false,
    canPlaceStone: Boolean = false,
    stonePlaced: Boolean = false,
    onPlaceStone: () -> Unit = {},
    // iOS parity `WalkOptionsSheet.swift:130-165@db4196e` — "Audio"
    // section soundscape row. Shown only in-walk when a soundscape is
    // selected (`soundscapeName != null`). Tap toggles the walk-long
    // ambient loop; long-press opens a picker of downloaded soundscapes.
    soundscapeName: String? = null,
    isSoundscapePlaying: Boolean = false,
    selectedSoundscapeId: String? = null,
    availableSoundscapes: List<SoundscapeChoice> = emptyList(),
    onToggleSoundscape: () -> Unit = {},
    onSelectSoundscape: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Text(
                text = stringResource(R.string.walk_options_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(bottom = PilgrimSpacing.small),
            )
            if (canSetIntention) {
                OptionRow(
                    // iOS uses a leaf glyph for the intention row.
                    icon = Icons.Outlined.Eco,
                    title = stringResource(R.string.walk_options_intention_title),
                    // iOS: subtitle = currentIntention (nil pre-walk →
                    // the subtitle line is omitted entirely, not a
                    // placeholder). Match: null when unset.
                    subtitle = intention?.takeIf { it.isNotBlank() },
                    onClick = onSetIntention,
                )
            }
            if (canDropWaypoint) {
                OptionRow(
                    icon = Icons.Outlined.LocationOn,
                    title = stringResource(R.string.walk_options_waypoint_title),
                    // Android plurals on en-US never select `quantity="zero"` —
                    // CLDR maps 0 to `other`, which would render "0 marked".
                    // Special-case the empty count with a non-plural string.
                    subtitle = if (waypointCount == 0) {
                        stringResource(R.string.walk_options_waypoint_count_none)
                    } else {
                        pluralStringResource(
                            R.plurals.walk_options_waypoint_count,
                            waypointCount,
                            waypointCount,
                        )
                    },
                    onClick = onDropWaypoint,
                )
            }
            // iOS parity `WalkOptionsSheet.swift:90-180@db4196e` —
            // "Traces" section: whisper + stone rows. Visible only when
            // the walk is in-progress (canDropWaypoint stands in as a
            // proxy for Active|Paused state); rendered even when locked
            // so the user sees the unlock countdown. The connectivity
            // gate iOS uses (`!isConnected` → hide entire section) is
            // deferred to a follow-up PR — Android currently relies on
            // the place-call's network error to surface the failure.
            if (canDropWaypoint) {
                OptionRow(
                    icon = Icons.Outlined.Air,
                    title = stringResource(R.string.walk_options_whisper_title),
                    subtitle = when {
                        !isWhisperUnlocked -> stringResource(R.string.walk_options_whisper_locked)
                        whispersRemaining <= 0 -> stringResource(R.string.walk_options_whisper_cap_reached)
                        else -> stringResource(R.string.walk_options_whisper_remaining, whispersRemaining)
                    },
                    enabled = canPlaceWhisper,
                    onClick = onLeaveWhisper,
                )
                OptionRow(
                    icon = Icons.Outlined.Terrain,
                    title = stringResource(R.string.walk_options_stone_title),
                    subtitle = when {
                        !isStoneUnlocked -> stringResource(R.string.walk_options_stone_locked)
                        stonePlaced -> stringResource(R.string.walk_options_stone_placed)
                        else -> stringResource(R.string.walk_options_stone_available)
                    },
                    enabled = canPlaceStone,
                    onClick = onPlaceStone,
                )
            }
            // iOS parity `WalkOptionsSheet.swift:130-165@db4196e` — "Audio"
            // section. Only in-walk and only when a soundscape is selected.
            if (canDropWaypoint && soundscapeName != null) {
                Text(
                    text = stringResource(R.string.walk_options_audio_section),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    modifier = Modifier.padding(
                        top = PilgrimSpacing.small,
                        start = PilgrimSpacing.xs,
                    ),
                )
                SoundscapeOptionRow(
                    name = soundscapeName,
                    isPlaying = isSoundscapePlaying,
                    selectedId = selectedSoundscapeId,
                    choices = availableSoundscapes,
                    onToggle = onToggleSoundscape,
                    onSelect = onSelectSoundscape,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SoundscapeOptionRow(
    name: String,
    isPlaying: Boolean,
    selectedId: String?,
    choices: List<SoundscapeChoice>,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val onLabel = stringResource(R.string.walk_options_soundscape_on)
    val offLabel = stringResource(R.string.walk_options_soundscape_off)
    val pickLabel = stringResource(R.string.walk_options_soundscape_pick)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.4f))
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onToggle,
                    // iOS uses a contextMenu (long-press) to switch
                    // soundscapes; mirror that with a long-press picker.
                    // onLongClickLabel makes the action announce to TalkBack.
                    onLongClickLabel = pickLabel,
                    onLongClick = { if (choices.isNotEmpty()) pickerOpen = true },
                )
                // Announce the on/off state to screen readers (the subtitle
                // text carries it visually; semantics carry it for TalkBack).
                .semantics { stateDescription = if (isPlaying) onLabel else offLabel }
                .padding(
                    horizontal = PilgrimSpacing.normal,
                    vertical = PilgrimSpacing.small,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Outlined.MusicNote else Icons.Outlined.MusicOff,
                contentDescription = null,
                tint = if (isPlaying) pilgrimColors.moss else pilgrimColors.fog,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.walk_options_soundscape_title),
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                )
                Text(
                    text = if (isPlaying) name else stringResource(R.string.walk_options_soundscape_off),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = pilgrimColors.fog,
            )
        }
        DropdownMenu(
            expanded = pickerOpen,
            onDismissRequest = { pickerOpen = false },
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.displayName) },
                    leadingIcon = {
                        if (choice.id == selectedId) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(
                                    R.string.walk_options_soundscape_selected,
                                ),
                            )
                        }
                    },
                    onClick = {
                        pickerOpen = false
                        onSelect(choice.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) pilgrimColors.moss else pilgrimColors.fog
    val titleColor = if (enabled) pilgrimColors.ink else pilgrimColors.fog
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.4f))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                horizontal = PilgrimSpacing.normal,
                vertical = PilgrimSpacing.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = pilgrimType.body, color = titleColor)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = pilgrimType.caption, color = pilgrimColors.fog)
            }
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = pilgrimColors.fog,
        )
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.whisper.ExpiryDuration
import org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `WhisperPlacementSheet.swift@db4196e` — ModalBottomSheet
 * at large detent (`skipPartiallyExpanded = true`) listing the 8
 * placeable whisper categories. User picks an expiry (default
 * SevenDays) + one category, taps Leave Whisper. The sheet does NOT
 * call the server — it returns `(category, expiry)` via [onPlace] and
 * the caller (ActiveWalkScreen via WalkViewModel) drives the HTTP
 * round-trip with server-confirm-then-haptic ordering.
 *
 * Deferred from MVP (separate PR):
 *  - Audio preview (play.circle / stop.circle button per row)
 *  - Category prefetch on selection
 *  - Disabled rows when [placeableCategories] filters one out (today
 *    we just render all 8 — the manifest will surface the available
 *    set via `placeableCategories` once audio playback ships)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperPlacementSheet(
    onPlace: (category: WhisperCategory, expiry: ExpiryDuration) -> Unit,
    onDismiss: () -> Unit,
    isPreviewing: Boolean = false,
    onPreviewToggle: (WhisperCategory) -> Unit = {},
    onPreviewStop: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedExpiry by rememberSaveable { mutableStateOf(ExpiryDuration.DEFAULT) }
    var selectedCategory by rememberSaveable { mutableStateOf<WhisperCategory?>(null) }
    var previewingCategory by rememberSaveable { mutableStateOf<WhisperCategory?>(null) }
    // iOS parity `WhisperPlacementSheet.swift:69-71@db4196e` — stop
    // preview on sheet dismiss. Compose equivalent: DisposableEffect
    // tied to the sheet's composition lifetime.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onPreviewStop() }
    }
    // If preview stops externally (sheet's caller cleared it), clear
    // the local "playing-this-row" indicator so the icon flips back.
    androidx.compose.runtime.LaunchedEffect(isPreviewing) {
        if (!isPreviewing) previewingCategory = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PilgrimSpacing.big,
                    vertical = PilgrimSpacing.normal,
                ),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        ) {
            Text(
                text = stringResource(R.string.whisper_sheet_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
            Text(
                text = stringResource(R.string.whisper_sheet_duration_label),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            KanjiExpiryPicker(
                selected = selectedExpiry,
                onSelect = { selectedExpiry = it },
            )
            Text(
                text = stringResource(R.string.whisper_sheet_category_label),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = WhisperCategory.entries, key = { it.apiValue }) { category ->
                    CategoryRow(
                        category = category,
                        selected = category == selectedCategory,
                        onClick = { selectedCategory = category },
                        isPreviewing = isPreviewing && category == previewingCategory,
                        onPreviewToggle = {
                            if (isPreviewing && category == previewingCategory) {
                                onPreviewStop()
                                previewingCategory = null
                            } else {
                                onPreviewToggle(category)
                                previewingCategory = category
                                selectedCategory = category
                            }
                        },
                    )
                }
            }
            Text(
                text = stringResource(R.string.whisper_sheet_privacy),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            Spacer(Modifier.height(PilgrimSpacing.small))
            val commitEnabled = selectedCategory != null
            Button(
                onClick = {
                    selectedCategory?.let { onPlace(it, selectedExpiry) }
                },
                enabled = commitEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = pilgrimColors.stone,
                    contentColor = pilgrimColors.parchment,
                    disabledContainerColor = pilgrimColors.fog.copy(alpha = 0.3f),
                    disabledContentColor = pilgrimColors.fog,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.whisper_sheet_commit),
                    style = pilgrimType.button,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: WhisperCategory,
    selected: Boolean,
    onClick: () -> Unit,
    isPreviewing: Boolean = false,
    onPreviewToggle: () -> Unit = {},
) {
    val background = if (selected) {
        pilgrimColors.parchmentSecondary.copy(alpha = 0.5f)
    } else {
        pilgrimColors.parchmentSecondary.copy(alpha = 0.2f)
    }
    val borderAlpha = if (selected) 1.0f else 0.4f
    val borderWidth = if (selected) 2.dp else 1.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(
                width = borderWidth,
                color = category.borderColor.copy(alpha = borderAlpha),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // iOS parity `WhisperPlacementSheet.swift:98@db4196e` — play/stop
        // toggle icon, tinted with the category border color.
        androidx.compose.material3.IconButton(
            onClick = onPreviewToggle,
            modifier = Modifier.size(36.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = if (isPreviewing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isPreviewing) "Stop preview" else "Preview",
                tint = category.borderColor,
            )
        }
        Text(
            text = stringResource(category.labelRes()),
            style = pilgrimType.body.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
            color = pilgrimColors.ink,
            modifier = Modifier.weight(1f),
        )
    }
}

@StringRes
private fun WhisperCategory.labelRes(): Int = when (this) {
    WhisperCategory.Presence -> R.string.whisper_category_presence
    WhisperCategory.Lightness -> R.string.whisper_category_lightness
    WhisperCategory.Wonder -> R.string.whisper_category_wonder
    WhisperCategory.Gratitude -> R.string.whisper_category_gratitude
    WhisperCategory.Compassion -> R.string.whisper_category_compassion
    WhisperCategory.Courage -> R.string.whisper_category_courage
    WhisperCategory.Stillness -> R.string.whisper_category_stillness
    WhisperCategory.Play -> R.string.whisper_category_play
}

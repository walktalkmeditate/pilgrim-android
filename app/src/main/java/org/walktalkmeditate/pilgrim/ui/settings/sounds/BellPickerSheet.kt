// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.sounds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Stage 10-B bell picker. ModalBottomSheet listing the bundled bell
 * catalog plus a "None" row. Selecting a row passes either the
 * asset id or null back via [onSelect] and dismisses. Optional
 * [onPreview] lets the caller play the bell — defaults to a no-op
 * for tests / contexts without a player.
 *
 * `@OptIn(ExperimentalMaterial3Api::class)` is required for
 * `ModalBottomSheet` and `rememberModalBottomSheetState`. Surfaced
 * here so callers don't have to repeat the opt-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BellPickerSheet(
    currentSelection: String?,
    availableBells: List<AudioAsset>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onPreview: (AudioAsset) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_bell_picker_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item("none") {
                    BellRow(
                        label = stringResource(R.string.settings_bell_picker_none),
                        selected = currentSelection == null,
                        onClick = {
                            onSelect(null)
                            onDismiss()
                        },
                    )
                }
                items(items = availableBells, key = { it.id }) { bell ->
                    BellRow(
                        label = bell.displayName,
                        selected = currentSelection == bell.id,
                        onClick = {
                            onSelect(bell.id)
                            onDismiss()
                        },
                        onPreview = { onPreview(bell) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BellRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPreview: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPreview != null) {
            IconButton(
                onClick = onPreview,
                modifier = Modifier.size(32.dp).testTag(BELL_PREVIEW_BUTTON_TAG),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = pilgrimColors.stone,
                )
            }
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.width(40.dp))
        }
        Text(
            text = label,
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(20.dp).testTag(BELL_CHECK_ICON_TAG),
            )
        }
    }
}

internal const val BELL_PREVIEW_BUTTON_TAG = "BellPickerSheet.preview"
internal const val BELL_CHECK_ICON_TAG = "BellPickerSheet.check"

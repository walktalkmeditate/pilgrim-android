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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import org.walktalkmeditate.pilgrim.data.sounds.BreathRhythm
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Stage 10-B breath-rhythm picker. ModalBottomSheet listing every
 * entry in [BreathRhythm.all] with its name, label (e.g. "5 / 7"),
 * and description. Tapping a row passes the rhythm's [BreathRhythm.id]
 * back via [onSelect] and dismisses. Mirrors iOS's
 * `BreathRhythmPickerSection`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreathRhythmPickerSheet(
    currentRhythmId: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
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
                text = stringResource(R.string.settings_breath_rhythm_picker_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items = BreathRhythm.all, key = { it.id }) { rhythm ->
                    RhythmRow(
                        rhythm = rhythm,
                        selected = rhythm.id == currentRhythmId,
                        onClick = {
                            onSelect(rhythm.id)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RhythmRow(
    rhythm: BreathRhythm,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = rhythm.name, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rhythm.name,
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                )
                if (!rhythm.isNone) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = rhythm.label,
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                    )
                }
            }
            Text(
                text = rhythm.description,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(20.dp).testTag(BREATH_RHYTHM_CHECK_ICON_TAG),
            )
        }
    }
}

internal const val BREATH_RHYTHM_CHECK_ICON_TAG = "BreathRhythmPickerSheet.check"

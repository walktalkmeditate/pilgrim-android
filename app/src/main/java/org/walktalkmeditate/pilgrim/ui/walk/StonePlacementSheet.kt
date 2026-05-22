// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `StonePlacementSheet.swift@db4196e` — ModalBottomSheet at
 * medium detent (the M3 PartiallyExpanded ~50% height). No text input,
 * no expiry picker: tap "Place Stone" → caller fires the HTTP round
 * trip with server-confirm-then-haptic ordering.
 *
 * Two branches per iOS:
 *  - [nearbyCairn] != null → "Add your stone to this cairn" + stone
 *    count. SF symbol `mountain.2.fill` for tier >= medium (>= 7
 *    stones), else `mountain.2`.
 *  - [nearbyCairn] == null → "Start a new cairn here".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StonePlacementSheet(
    onPlace: () -> Unit,
    onDismiss: () -> Unit,
    nearbyCairn: org.walktalkmeditate.pilgrim.data.cairn.CachedCairn? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        ) {
            Text(
                text = stringResource(R.string.stone_sheet_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
            Icon(
                imageVector = Icons.Outlined.Terrain,
                contentDescription = null,
                tint = if (nearbyCairn != null) pilgrimColors.stone else pilgrimColors.moss,
                modifier = Modifier.size(56.dp),
            )
            if (nearbyCairn != null) {
                // Existing-cairn branch: show stone count + "Add your
                // stone" copy. iOS `StonePlacementSheet.swift:43-61`.
                Text(
                    text = if (nearbyCairn.stoneCount == 1) {
                        stringResource(R.string.stone_sheet_existing_count_one)
                    } else {
                        stringResource(
                            R.string.stone_sheet_existing_count,
                            nearbyCairn.stoneCount,
                        )
                    },
                    style = pilgrimType.displayMedium,
                    color = pilgrimColors.ink,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.stone_sheet_existing_title),
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = stringResource(R.string.stone_sheet_message),
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.stone_sheet_message_caption),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = stringResource(R.string.stone_sheet_privacy),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(PilgrimSpacing.small))
            Button(
                onClick = onPlace,
                colors = ButtonDefaults.buttonColors(
                    containerColor = pilgrimColors.stone,
                    contentColor = pilgrimColors.parchment,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.stone_sheet_commit),
                    style = pilgrimType.button,
                )
            }
        }
    }
}

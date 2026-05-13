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
import androidx.compose.material.icons.outlined.Landscape
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
 * Deferred from MVP:
 *  - "Add your stone to this cairn" copy + stone-count display when a
 *    nearby cairn is detected within 42m (depends on GeoCache being
 *    ported). MVP always shows the "Start a new cairn here" branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StonePlacementSheet(
    onPlace: () -> Unit,
    onDismiss: () -> Unit,
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
                imageVector = Icons.Outlined.Landscape,
                contentDescription = null,
                tint = pilgrimColors.moss,
                modifier = Modifier.size(56.dp),
            )
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

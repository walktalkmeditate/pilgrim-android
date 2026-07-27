// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.cairn.CairnTier
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `StonePlacementSheet.swift@9a418e4` — ModalBottomSheet at
 * medium detent (the M3 PartiallyExpanded ~50% height). No text input,
 * no expiry picker: tap "Place Stone" → caller fires the HTTP round
 * trip with server-confirm-then-haptic ordering.
 *
 * Two branches per iOS, each carrying the tier the walker's stone
 * makes (U16 glyph spec `docs/parity/2026-07-27-port-glyph-sheets-u16.md`):
 *  - [nearbyCairn] != null → becoming-tier art (96dp) + stone count +
 *    "Add your stone to this cairn".
 *  - [nearbyCairn] == null → ghosted faint-tier art (112dp, alpha 0.4 —
 *    view alpha, never a tint: the baked-color art ignores tints and
 *    "not yet placed" must still read as ghost) + "Start a new cairn
 *    here".
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
            if (nearbyCairn != null) {
                // Existing-cairn branch: becoming-tier art + stone count
                // + "Add your stone" copy. iOS `existingCairnSection`.
                val becoming = nearbyCairn.becomingTier
                Image(
                    painter = painterResource(becoming.glyphRes),
                    contentDescription = stringResource(
                        R.string.stone_sheet_becomes_a11y,
                        stringResource(becoming.displayNameWithArticleRes),
                    ),
                    modifier = Modifier.size(96.dp),
                )
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
                // New-cairn branch: a first stone always begins a faint
                // cairn; ghost via view alpha. iOS `newCairnSection`.
                Image(
                    painter = painterResource(CairnTier.Faint.glyphRes),
                    contentDescription = stringResource(R.string.stone_sheet_begins_a11y),
                    modifier = Modifier
                        .size(112.dp)
                        .alpha(0.4f),
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

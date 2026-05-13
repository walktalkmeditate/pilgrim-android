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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.cairn.CachedCairn
import org.walktalkmeditate.pilgrim.data.cairn.CairnTier
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `CairnDetailView.swift@db4196e` — medium-detent
 * ModalBottomSheet shown when the user taps a cairn pin on the map.
 * Read-only summary: tier glyph + stone count + tier description.
 *
 * MVP scope (this PR): no animated glow ring (deferred — requires a
 * Canvas radial-gradient + breathing animation port). No "Place a
 * Stone" button — the tap-on-pin path opens this in read-only mode
 * exactly like iOS (placement happens through the WalkOptionsSheet
 * → StonePlacementSheet flow, not from here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CairnDetailSheet(
    cairn: CachedCairn,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment.copy(alpha = 0.95f),
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
            // Hero glyph — size scaled by tier ordinal (32–68pt iOS).
            val glyphSizeDp = 32 + cairn.tier.ordinal * 6
            Icon(
                imageVector = Icons.Outlined.Landscape,
                contentDescription = null,
                tint = if (cairn.tier.ordinal >= CairnTier.Great.ordinal) {
                    pilgrimColors.stone
                } else {
                    pilgrimColors.moss
                },
                modifier = Modifier.size(glyphSizeDp.dp),
            )
            Text(
                text = cairn.stoneCount.toString(),
                style = pilgrimType.displayLarge,
                color = pilgrimColors.ink,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.cairn_detail_stones_plural,
                    cairn.stoneCount,
                    cairn.stoneCount,
                ),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            Spacer(Modifier.height(PilgrimSpacing.small))
            Text(
                text = stringResource(tierDescriptionRes(cairn.tier)),
                style = pilgrimType.body,
                color = pilgrimColors.ink,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(PilgrimSpacing.normal))
            Button(
                onClick = onDismiss,
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
                    text = stringResource(R.string.cairn_detail_close),
                    style = pilgrimType.button,
                )
            }
        }
    }
}

private fun tierDescriptionRes(tier: CairnTier): Int = when (tier) {
    CairnTier.Faint -> R.string.cairn_tier_faint_desc
    CairnTier.Small -> R.string.cairn_tier_small_desc
    CairnTier.Medium -> R.string.cairn_tier_medium_desc
    CairnTier.Large -> R.string.cairn_tier_large_desc
    CairnTier.Great -> R.string.cairn_tier_great_desc
    CairnTier.Sacred -> R.string.cairn_tier_sacred_desc
    CairnTier.Eternal -> R.string.cairn_tier_eternal_desc
}

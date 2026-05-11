// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `WalkSharingButtons.swift@db4196e`. ParchmentSecondary card
 * with 3 share actions: Goshuin image, Etegami image, and Walk Journey URL.
 * Renders only when `hasRoute = true` (walk has >= 2 GPS points).
 *
 * Per-button `isGenerating` latches disable the button and show an inline
 * spinner while bitmap render is in flight, matching the iOS in-flight
 * indicator pattern.
 *
 * Callbacks are wired in Stage 4.1 — this composable is a pure stateless
 * shell that accepts lambdas.
 */
@Composable
internal fun WalkSharingButtons(
    hasRoute: Boolean,
    isGoshuinGenerating: Boolean,
    isEtegamiGenerating: Boolean,
    onGoshuinShare: () -> Unit,
    onEtegamiShare: () -> Unit,
    onWalkJourneyShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!hasRoute) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.parchmentSecondary)
            .padding(PilgrimSpacing.normal)
            .testTag("sharing-card"),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        ) {
            ImageShareButton(
                icon = { Icon(Icons.Outlined.Bookmark, contentDescription = null) },
                label = stringResource(R.string.share_button_goshuin),
                subtitle = stringResource(R.string.share_button_goshuin_subtitle),
                isGenerating = isGoshuinGenerating,
                onClick = onGoshuinShare,
                modifier = Modifier
                    .weight(1f)
                    .testTag("share-button-goshuin"),
            )
            ImageShareButton(
                icon = { Icon(Icons.Filled.Brush, contentDescription = null) },
                label = stringResource(R.string.share_button_etegami),
                subtitle = stringResource(R.string.share_button_etegami_subtitle),
                isGenerating = isEtegamiGenerating,
                onClick = onEtegamiShare,
                modifier = Modifier
                    .weight(1f)
                    .testTag("share-button-etegami"),
            )
        }
        HorizontalDivider(color = pilgrimColors.fog.copy(alpha = 0.2f))
        OutlinedButton(
            onClick = onWalkJourneyShare,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("share-button-walk-journey"),
        ) {
            Text(
                text = stringResource(R.string.share_button_walk_journey),
                style = pilgrimType.button,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ImageShareButton(
    icon: @Composable () -> Unit,
    label: String,
    subtitle: String,
    isGenerating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !isGenerating,
        modifier = modifier,
    ) {
        Box {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
                modifier = Modifier.padding(vertical = PilgrimSpacing.small),
            ) {
                icon()
                Text(
                    text = label,
                    style = pilgrimType.button,
                    color = pilgrimColors.ink,
                )
                Text(
                    text = subtitle,
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.Center),
                    strokeWidth = 2.dp,
                    color = pilgrimColors.stone,
                )
            }
        }
    }
}

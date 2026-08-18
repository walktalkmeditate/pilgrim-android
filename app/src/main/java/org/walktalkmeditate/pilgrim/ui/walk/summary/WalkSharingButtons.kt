// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `WalkSharingButtons.swift@db4196e`. ParchmentSecondary card
 * with two icon-circle share actions (Goshuin / Etegami) on top, divider
 * in the middle, and the Walk Journey share-page CTA below.
 *
 * iOS visual:
 *   - 52pt circular stone-tinted icon background with stone-tinted 1pt
 *     ring, plain (button-style-less) Button wrapping the VStack
 *   - Caption-sized label, micro-sized fog subtitle below each
 *   - 0.5pt fog/15 horizontal divider
 *   - Below the divider: iOS `journeySection` branches on the cached
 *     share (`WalkSharingButtons.swift:148-159@2ee1185`) — a
 *     non-expired [activeCachedShare] renders [WalkSharingBlock]
 *     (issue #222); everything else (no cache, or expired) falls back
 *     to the plain text "Share Journey" button + two micro fog footer
 *     rows ("Create a web page" / "walk.pilgrimapp.org"). Per the
 *     issue #222 scope, Android does NOT port Swift's separate
 *     "returned to the trail" expired-state layout
 *     (`returnedSection(_:)`, `:310-344@2ee1185`) — an expired cached
 *     share is treated the same as never-shared.
 */
@Composable
internal fun WalkSharingButtons(
    hasRoute: Boolean,
    isGoshuinGenerating: Boolean,
    isEtegamiGenerating: Boolean,
    onGoshuinShare: () -> Unit,
    onEtegamiShare: () -> Unit,
    onWalkJourneyShare: () -> Unit,
    activeCachedShare: CachedShare?,
    onCachedShareEngaged: () -> Unit,
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
            horizontalArrangement = Arrangement.spacedBy(
                PilgrimSpacing.big,
                Alignment.CenterHorizontally,
            ),
        ) {
            ImageShareButton(
                icon = Icons.Outlined.Bookmark,
                label = stringResource(R.string.share_button_goshuin),
                subtitle = stringResource(R.string.share_button_goshuin_subtitle),
                isGenerating = isGoshuinGenerating,
                onClick = onGoshuinShare,
                modifier = Modifier.testTag("share-button-goshuin"),
            )
            ImageShareButton(
                icon = Icons.Filled.Brush,
                label = stringResource(R.string.share_button_etegami),
                subtitle = stringResource(R.string.share_button_etegami_subtitle),
                isGenerating = isEtegamiGenerating,
                onClick = onEtegamiShare,
                modifier = Modifier.testTag("share-button-etegami"),
            )
        }
        HorizontalDivider(
            color = pilgrimColors.fog.copy(alpha = 0.15f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(horizontal = PilgrimSpacing.big),
        )
        if (activeCachedShare != null) {
            WalkSharingBlock(
                cachedShare = activeCachedShare,
                onOpenJourney = onWalkJourneyShare,
                onEngaged = onCachedShareEngaged,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            JourneyFooter(
                onClick = onWalkJourneyShare,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("share-button-walk-journey"),
            )
        }
    }
}

@Composable
private fun ImageShareButton(
    icon: ImageVector,
    label: String,
    subtitle: String,
    isGenerating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .clickable(enabled = !isGenerating, onClick = onClick)
            .padding(PilgrimSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(pilgrimColors.stone.copy(alpha = 0.08f))
                .border(1.dp, pilgrimColors.stone.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = pilgrimColors.stone,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = pilgrimColors.stone,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            text = label,
            style = pilgrimType.caption,
            color = pilgrimColors.stone,
        )
        Text(
            text = subtitle,
            style = pilgrimType.statLabel,
            color = pilgrimColors.fog,
        )
    }
}

@Composable
private fun JourneyFooter(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .clickable(onClick = onClick)
            .padding(vertical = PilgrimSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Icon(
                imageVector = Icons.Outlined.IosShare,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.share_journey_action),
                style = pilgrimType.button,
                color = pilgrimColors.stone,
            )
        }
        Text(
            text = stringResource(R.string.share_journey_create_web_page),
            style = pilgrimType.statLabel,
            color = pilgrimColors.fog,
        )
        Text(
            text = stringResource(R.string.share_journey_footer_url),
            style = pilgrimType.statLabel,
            color = pilgrimColors.fog,
        )
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * The summary's Honor card, right after the Seek section and before the
 * elevation profile, with no reveal fade (iOS `HonorSummarySection`,
 * `HonorSummarySection.swift:60-93@7c200bf`): the kicker, the Way's title
 * (wrapping, no line limit), the delta line once the link holds both
 * arrival numbers, and the counts line when either count is above zero.
 * Stateless; it links nowhere on an own or shared walk.
 *
 * TalkBack reads each row as its own text in order, as VoiceOver does:
 * iOS sets no grouping, header trait, or label on any of them (G §11).
 */
@Composable
fun HonorSummarySection(
    data: HonorSummaryData,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.parchmentSecondary)
            .padding(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Text(
            text = HonorSummaryModel.kicker(resources),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Text(
            text = HonorSummaryModel.title(resources, data),
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
        )
        data.arrivedBeforeTheirsSeconds?.let { delta ->
            Text(
                text = HonorSummaryModel.deltaLine(resources, delta),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        HonorSummaryModel.countsLine(resources, data)?.let { counts ->
            Text(
                text = counts,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

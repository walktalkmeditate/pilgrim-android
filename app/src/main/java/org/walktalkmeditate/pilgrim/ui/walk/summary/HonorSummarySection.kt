// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.honor.CaptionButton
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * The stage's "your reply" button, iOS's section-owned `AudioPlayerModel`
 * (`HonorSummarySection.swift:57,77-87,92@7c200bf`): whether the reply
 * plays now, the tap that plays, pauses or resumes it, and the stop when
 * the section leaves.
 */
class HonorSummaryReplyPlayer(
    val isPlaying: Boolean,
    val toggle: () -> Unit,
    val stop: () -> Unit,
)

/**
 * The summary's Honor card, right after the Seek section and before the
 * elevation profile, with no reveal fade (iOS `HonorSummarySection`,
 * `HonorSummarySection.swift:60-93@7c200bf`): the kicker, the Way's title
 * (wrapping, no line limit), a stage's progress line, the delta line once
 * the link holds both arrival numbers (never on a stage), the counts line
 * when either count is above zero, a stage's closing line when this walk
 * arrived, and its "your reply" button while [reply] is given. It links
 * nowhere on an own or shared walk.
 *
 * TalkBack reads each row as its own text in order, as VoiceOver does:
 * iOS sets no grouping, header trait, or label on any of them (G §11).
 * The reply button reads "Play your reply to this stage" while it shows
 * "pause" too (pilgrim-ios #123 item 7, matched as shipped).
 */
@Composable
fun HonorSummarySection(
    data: HonorSummaryData,
    units: UnitSystem,
    modifier: Modifier = Modifier,
    reply: HonorSummaryReplyPlayer? = null,
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
            text = HonorSummaryModel.kicker(resources, data),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Text(
            text = HonorSummaryModel.title(resources, data),
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
        )
        data.stageProgress?.let { progress ->
            Text(
                text = HonorSummaryModel.stageProgressLine(resources, progress, units),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
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
        data.closing?.let { closing ->
            Text(
                text = closing,
                style = pilgrimType.displayMedium,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(top = PilgrimSpacing.xs),
            )
        }
        reply?.let { ReplyButton(it) }
    }
}

@Composable
private fun ReplyButton(reply: HonorSummaryReplyPlayer) {
    val stop by rememberUpdatedState(reply.stop)
    DisposableEffect(Unit) {
        onDispose { stop() }
    }
    CaptionButton(
        icon = if (reply.isPlaying) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
        title = stringResource(HonorSummaryModel.replyTitle(reply.isPlaying)),
        label = stringResource(R.string.honor_summary_reply_a11y),
        onClick = reply.toggle,
    )
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.StageFormat
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/** The arrival card's words (iOS `HonorArrivalCardView.title(for:)` and `line(for:)`, `WayPlaceCard.swift:414-433@7c200bf`). */
object HonorArrivalCopy {

    fun title(resources: Resources, summary: HonorArrivalSummary): String =
        resources.getString(if (summary.isStage) R.string.honor_arrival_stage_title else R.string.honor_arrival_title)

    /**
     * A shared or own Way counts voices heard, then places passed, joined by
     * " · ", or "the whole way, in their steps" with neither; a share walked
     * without its voices counts no voice, since only a voice that played is
     * heard. A stage counts places and the kilometres walked, in the stage
     * surfaces' numbers (owner decision 7), so its "the whole stage" can
     * never show (pilgrimage-stage spec P5 §8.1, pilgrim-ios #122, matched).
     */
    fun line(resources: Resources, summary: HonorArrivalSummary, units: UnitSystem): String {
        val parts = buildList {
            if (!summary.isStage) {
                when {
                    summary.voicesHeard == 1 -> add(resources.getString(R.string.honor_arrival_one_voice))
                    summary.voicesHeard > 1 -> add(resources.getString(R.string.honor_arrival_voices, count(summary.voicesHeard)))
                }
            }
            when {
                summary.placesPassed == 1 -> add(resources.getString(R.string.honor_arrival_one_place))
                summary.placesPassed > 1 -> add(resources.getString(R.string.honor_arrival_places, count(summary.placesPassed)))
            }
            if (summary.isStage) add(StageFormat.distance(summary.distanceWalkedMeters, units))
        }
        if (parts.isEmpty()) {
            return resources.getString(if (summary.isStage) R.string.honor_arrival_whole_stage else R.string.honor_arrival_whole_way)
        }
        return parts.joinToString(" · ")
    }

    /** The reply button's face; TalkBack reads [R.string.honor_arrival_reply_a11y] for both (pilgrim-ios #123, matched). */
    @StringRes
    fun replyTitle(hasReply: Boolean): Int = if (hasReply) R.string.honor_card_record_again else R.string.honor_card_reply_here

    private fun count(n: Int): String = String.format(Locale.US, "%d", n)
}

/** What the walker can do with a stage's closing line, from its arrival card. */
class StageReplyActions(
    val onReply: () -> Unit,
    val onStopReply: () -> Unit,
    val onPlayReply: () -> Unit,
)

/** The arrival card's reply row: whether a reply to the closing line is on the phone, and whether one records now. */
class StageReplyRow(
    val hasReply: Boolean,
    val isRecording: Boolean,
    val actions: StageReplyActions,
)

/**
 * iOS `HonorArrivalCardView` (`WayPlaceCard.swift:350-412@7c200bf`, parity
 * spec E §11, pilgrimage-stage spec P5 §8): the title, the stage's name or
 * the Way's title, the counting line, a stage's closing line and its
 * [stageReply] row, and "continue", the only way out, on the place card's
 * shell with no swipe and no ×. It outranks every place card while it is
 * up. Nothing widens it but a reply row with a reply on the phone or one
 * recording, which reaches across, as iOS's spacers do.
 */
@Composable
fun HonorArrivalCard(
    summary: HonorArrivalSummary,
    units: UnitSystem,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    stageReply: StageReplyRow? = null,
) {
    val resources = LocalResources.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(pilgrimColors.parchmentSecondary)
            .padding(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Text(text = HonorArrivalCopy.title(resources, summary), style = pilgrimType.heading, color = pilgrimColors.ink)
        Text(text = summary.stageName ?: summary.wayTitle, style = pilgrimType.body, color = pilgrimColors.fog)
        Text(
            text = HonorArrivalCopy.line(resources, summary, units),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        summary.closing?.let { closing ->
            Text(text = closing, style = pilgrimType.displayMedium, color = pilgrimColors.ink)
            stageReply?.let { StageReplyControls(it) }
        }
        Text(
            text = stringResource(R.string.honor_arrival_continue),
            style = pilgrimType.button,
            color = pilgrimColors.stone,
            modifier = Modifier.clickable(role = Role.Button, onClick = onContinue),
        )
    }
}

/**
 * iOS `replyRow` (P5 §8.3), unlike the place card's: "reply here" or
 * "record again" starts a take at once, with no confirmation, and one label
 * says both (pilgrim-ios #123, matched); the take shows as a caption with
 * no pulsing dot; "your reply" stays while a new take records.
 */
@Composable
private fun StageReplyControls(row: StageReplyRow) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        if (row.isRecording) {
            Text(
                text = stringResource(R.string.honor_card_recording_reply),
                style = pilgrimType.caption,
                color = pilgrimColors.ink,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.StopCircle,
                contentDescription = null,
                tint = pilgrimColors.rust,
                modifier = Modifier
                    .size(GLYPH_DISPLAY)
                    .labelledButton(stringResource(R.string.honor_card_stop_reply), row.actions.onStopReply),
            )
        } else {
            CaptionButton(
                icon = Icons.Outlined.Mic,
                title = stringResource(HonorArrivalCopy.replyTitle(row.hasReply)),
                label = stringResource(R.string.honor_arrival_reply_a11y),
                onClick = row.actions.onReply,
            )
        }
        if (row.hasReply) {
            Spacer(Modifier.weight(1f))
            CaptionButton(
                icon = Icons.Outlined.PlayCircle,
                title = stringResource(R.string.honor_card_your_reply),
                label = stringResource(R.string.honor_arrival_play_reply_a11y),
                onClick = row.actions.onPlayReply,
            )
        }
    }
}

/** iOS's `Label` in caption and stone with a 44 dp hit area; [label] is all TalkBack reads. */
@Composable
internal fun CaptionButton(icon: ImageVector, title: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = TAP_TARGET)
            .labelledButton(label, onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = pilgrimColors.stone, modifier = Modifier.size(GLYPH_CAPTION))
        Text(
            text = title,
            style = pilgrimType.caption,
            color = pilgrimColors.stone,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

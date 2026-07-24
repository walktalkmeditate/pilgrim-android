// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Signpost
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.summary.RevealPhase
import org.walktalkmeditate.pilgrim.ui.walk.summary.rememberRevealAlpha

/**
 * The render gate as a pure function, so it is testable without a view
 * (iOS `CollectiveTrailSection.renderedLine@9a418e4`). No collective
 * total involved — unlike the Settings line, this one still holds on
 * day twelve of a Camino with no signal. [contributionLine] is null
 * while the catalog is still loading (or failed to decode); half a
 * line is worse than none.
 */
internal fun collectiveTrailRenderedLine(
    wasContributed: Boolean,
    contributionLine: String?,
): String? = if (wasContributed) contributionLine else null

internal const val COLLECTIVE_TRAIL_REVEAL_DURATION_MS = 800

/** A beat behind the personal milestone's 300ms, so the two land as two thoughts. */
internal const val COLLECTIVE_TRAIL_REVEAL_DELAY_MS = 550

/**
 * This walk's distance against the day's collective route, and a
 * sentence naming who else has walked it. No background fill, so it
 * doesn't read as a second milestone. Ports iOS
 * `CollectiveTrailSection.swift@9a418e4` (parity spec
 * `docs/parity/2026-07-23-port-collective-trail-u6.md`).
 *
 * [contributionLine] arrives already phrased, resolved by
 * [WalkSummaryViewModel] rather than here — the summary recomposes
 * roughly thirty times during the distance count-up, and phrasing
 * costs an entry lookup, a calendar call and a formatter per pass.
 * [wasContributed] is a past-tense fact from the contribution ledger,
 * not the live preference. A gate-null body emits no node, so the
 * enclosing spacedBy Column inserts no gap.
 */
@Composable
internal fun CollectiveTrailSection(
    contributionLine: String?,
    wasContributed: Boolean,
    revealPhase: RevealPhase,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val line = collectiveTrailRenderedLine(wasContributed, contributionLine) ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PilgrimSpacing.normal)
            .alpha(
                rememberRevealAlpha(
                    revealPhase = revealPhase,
                    durationMs = COLLECTIVE_TRAIL_REVEAL_DURATION_MS,
                    delayMs = COLLECTIVE_TRAIL_REVEAL_DELAY_MS,
                    reduceMotion = reduceMotion,
                ),
            ),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(
            PilgrimSpacing.small,
            Alignment.CenterHorizontally,
        ),
    ) {
        Icon(
            imageVector = Icons.Rounded.Signpost,
            contentDescription = null,
            tint = pilgrimColors.stone,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = line,
            style = pilgrimType.caption,
            color = pilgrimColors.stone,
            // A walk distance plus a company sentence runs to ~110
            // characters, and the company sentence is curator-editable
            // after ship — the render-budget test pins the ceiling.
            maxLines = 4,
            autoSize = TextAutoSize.StepBased(
                minFontSize = pilgrimType.caption.fontSize * 0.5f,
                maxFontSize = pilgrimType.caption.fontSize,
                stepSize = 0.5.sp,
            ),
        )
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Large timer-style hero showing the walk's active duration
 * (paused-excluded, meditation-included). iOS reference:
 * `WalkSummaryView.durationHero` (`WalkSummaryView.swift:324-330`).
 *
 * Pure presentation — no animation. Stage 13-B will add the reveal
 * fade-in tied to the `revealPhase` state machine.
 */
@Composable
fun WalkDurationHero(
    durationMillis: Long,
    modifier: Modifier = Modifier,
) {
    Text(
        text = WalkFormat.duration(durationMillis),
        style = pilgrimType.timer,
        color = pilgrimColors.ink,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

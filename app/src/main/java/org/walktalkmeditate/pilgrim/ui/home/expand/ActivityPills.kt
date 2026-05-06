// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@Composable
fun ActivityPills(snapshot: WalkSnapshot, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(
            color = pilgrimColors.moss,
            seconds = snapshot.walkOnlyDurationSec,
            labelRes = R.string.journal_expand_pill_walk,
        )
        if (snapshot.hasTalk) {
            Pill(
                color = pilgrimColors.rust,
                seconds = snapshot.talkDurationSec,
                labelRes = R.string.journal_expand_pill_talk,
            )
        }
        if (snapshot.hasMeditate) {
            Pill(
                color = pilgrimColors.dawn,
                seconds = snapshot.meditateDurationSec,
                labelRes = R.string.journal_expand_pill_meditate,
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Pill(color: Color, seconds: Long, labelRes: Int) {
    val labelColor = pilgrimColors.fog
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(5.dp).clip(CircleShape).background(color),
        )
        Text(
            text = "${formatPillDuration(seconds)} ${stringResource(labelRes)}",
            style = pilgrimType.micro,
            color = labelColor,
        )
    }
}

/**
 * Pill duration: "M:SS" below 1 hour, "H:MM" otherwise. `Locale.US` is
 * intentional — Stage 5-A locale lesson; numeric body always ASCII.
 */
private fun formatPillDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        String.format(java.util.Locale.US, "%d:%02d", h, m)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }
}

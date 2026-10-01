// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/** The arrival card's counting line (iOS `HonorArrivalCardView.line(for:)`, `WayPlaceCard.swift:414-433@7c200bf`). */
object HonorArrivalCopy {

    /**
     * Voices heard, then places passed, joined by " · ", or "the whole
     * way, in their steps" with neither. A share walked without its voices
     * counts no voice, since only a voice that played is heard.
     */
    fun line(resources: Resources, summary: HonorArrivalSummary): String {
        val parts = buildList {
            when {
                summary.voicesHeard == 1 -> add(resources.getString(R.string.honor_arrival_one_voice))
                summary.voicesHeard > 1 -> add(resources.getString(R.string.honor_arrival_voices, count(summary.voicesHeard)))
            }
            when {
                summary.placesPassed == 1 -> add(resources.getString(R.string.honor_arrival_one_place))
                summary.placesPassed > 1 -> add(resources.getString(R.string.honor_arrival_places, count(summary.placesPassed)))
            }
        }
        if (parts.isEmpty()) return resources.getString(R.string.honor_arrival_whole_way)
        return parts.joinToString(" · ")
    }

    private fun count(n: Int): String = String.format(Locale.US, "%d", n)
}

/**
 * iOS `HonorArrivalCardView` for an own or shared Way (`WayPlaceCard.swift:350-375@7c200bf`,
 * parity spec E §11): the title, the Way's title, the counting line, and
 * "continue", the only way out, on the place card's shell with no swipe
 * and no ×. It outranks every place card while it is up. Unlike the place
 * card, nothing widens it: it is as wide as its widest line. The closing
 * line and its reply row are a stage's.
 */
@Composable
fun HonorArrivalCard(
    summary: HonorArrivalSummary,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(pilgrimColors.parchmentSecondary)
            .padding(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Text(text = stringResource(R.string.honor_arrival_title), style = pilgrimType.heading, color = pilgrimColors.ink)
        Text(text = summary.wayTitle, style = pilgrimType.body, color = pilgrimColors.fog)
        Text(
            text = HonorArrivalCopy.line(LocalResources.current, summary),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Text(
            text = stringResource(R.string.honor_arrival_continue),
            style = pilgrimType.button,
            color = pilgrimColors.stone,
            modifier = Modifier.clickable(role = Role.Button, onClick = onContinue),
        )
    }
}

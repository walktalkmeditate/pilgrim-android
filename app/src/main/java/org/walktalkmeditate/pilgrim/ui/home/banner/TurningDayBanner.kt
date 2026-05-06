// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.banner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.bannerTextRes
import org.walktalkmeditate.pilgrim.core.celestial.isTurning
import org.walktalkmeditate.pilgrim.core.celestial.kanji
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Banner shown above the JourneySummaryHeader on equinox/solstice days.
 *
 * Zero-height when `marker == null` or not a cardinal turning, so the
 * parent layout collapses cleanly outside turning days.
 */
@Composable
fun TurningDayBanner(marker: SeasonalMarker?, modifier: Modifier = Modifier) {
    if (marker == null || !marker.isTurning()) {
        Box(modifier = modifier.fillMaxWidth())
        return
    }
    val bannerRes = marker.bannerTextRes() ?: return
    val kanji = marker.kanji() ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(bannerRes),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Text(
            text = " · ",
            style = pilgrimType.caption,
            color = pilgrimColors.fog.copy(alpha = 0.5f),
        )
        Text(
            text = kanji,
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

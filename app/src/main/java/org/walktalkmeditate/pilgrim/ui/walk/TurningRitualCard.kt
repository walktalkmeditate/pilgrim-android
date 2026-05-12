// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.evocativePhraseRes
import org.walktalkmeditate.pilgrim.core.celestial.kanji
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `TurningRitualCard.swift@db4196e`. Contemplative card
 * shown when the user taps the turning kanji watermark on the active
 * walk. Pure ritual — no stats, no controls, just the kanji, the
 * seasonal name, and an evocative phrase.
 *
 * Returns silently (renders nothing) for non-cardinal markers since
 * iOS hides the watermark on those days too — caller can guard or
 * pass any marker and trust this gate.
 */
@Composable
fun TurningRitualCard(turning: SeasonalMarker, modifier: Modifier = Modifier) {
    val kanji = turning.kanji() ?: return
    val phraseRes = turning.evocativePhraseRes()
    val phrase = phraseRes?.let { stringResource(it) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(pilgrimColors.parchmentSecondary)
            .padding(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = kanji,
            // iOS uses `.font(.system(size: 64, weight: .ultraLight))`
            // scaled relative to .largeTitle for Dynamic Type. Android
            // approximation: 64.sp with Light weight. Larger system
            // font scale at runtime lifts this proportionally because
            // sp respects the user's font-size preference.
            fontSize = 64.sp,
            fontWeight = FontWeight.Light,
            color = pilgrimColors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = turning.displayName,
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
            textAlign = TextAlign.Center,
        )
        if (phrase != null) {
            Text(
                text = phrase,
                style = pilgrimType.body.copy(fontStyle = FontStyle.Italic),
                color = pilgrimColors.fog,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = PilgrimSpacing.big),
            )
        }
    }
}

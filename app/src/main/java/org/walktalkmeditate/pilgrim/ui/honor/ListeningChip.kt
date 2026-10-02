// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.HonorListening

/**
 * iOS `HonorListeningChip` (`WayPlaceCard.swift:327-348@7c200bf`, parity
 * spec E §10, correction 16): while a Way voice is held, the waveform, its
 * state, the player's clock, then pause or resume and skip, and nothing
 * else; replay and rate live on the card. The buttons are as large as
 * their glyphs and no larger (pilgrim-ios #108, matched). The waveform
 * glyph carries no label of its own, so TalkBack reads the symbol's name as
 * VoiceOver does.
 */
@Composable
fun ListeningChip(
    listening: HonorListening,
    onPauseResume: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(pilgrimColors.parchmentSecondary)
            .padding(horizontal = PilgrimSpacing.normal, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Icon(
            imageVector = Icons.Outlined.GraphicEq,
            contentDescription = WayMomentCopy.spokenSymbolName(WAVEFORM_SYMBOL),
            tint = pilgrimColors.stone,
            modifier = Modifier.size(GLYPH),
        )
        Text(
            text = stringResource(if (listening.paused) R.string.honor_chip_paused else R.string.honor_chip_listening),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Text(
            text = WayMomentCopy.clock(listening.elapsedSeconds),
            style = pilgrimType.caption.copy(fontFeatureSettings = "tnum"),
            color = pilgrimColors.ink,
        )
        Icon(
            imageVector = if (listening.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = stringResource(if (listening.paused) R.string.honor_chip_resume else R.string.honor_moment_pause),
            tint = pilgrimColors.stone,
            modifier = Modifier
                .size(GLYPH)
                .clickable(role = Role.Button, onClick = onPauseResume),
        )
        Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = stringResource(R.string.honor_chip_skip),
            tint = pilgrimColors.stone,
            modifier = Modifier
                .size(GLYPH)
                .clickable(role = Role.Button, onClick = onSkip),
        )
    }
}

private const val WAVEFORM_SYMBOL = "waveform"

/** iOS's chip glyphs take the body font's size. */
private val GLYPH = 17.dp

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.empty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyPath
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Empty-state Composable shown when no walks exist. Verbatim port of
 * iOS `InkScrollView.swift:714`:
 *   - Tapered single calligraphy stroke (120 dp tall, 1 dp ↘ 0.2 dp ↗ 1 dp)
 *   - 14 dp stone-color filled circle
 *   - "Begin" caption (caption typography, fog color)
 */
@Composable
fun EmptyJournalState(modifier: Modifier = Modifier) {
    val stoneColor = pilgrimColors.stone
    val fogColor = pilgrimColors.fog
    val captionStyle = pilgrimType.caption
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CalligraphyPath(
                strokes = emptyList(),
                modifier = Modifier.fillMaxWidth().height(120.dp),
                emptyMode = true,
            )
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(stoneColor),
            )
            Text(
                text = stringResource(R.string.home_empty_begin),
                style = captionStyle,
                color = fogColor,
            )
        }
    }
}

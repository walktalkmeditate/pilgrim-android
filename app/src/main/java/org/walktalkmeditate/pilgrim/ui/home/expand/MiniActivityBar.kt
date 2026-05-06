// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Three-segment fraction capsule. Mirrors iOS `InkScrollView.swift:425-441`.
 * Hides any segment whose fraction is below 1 % to avoid hairline slivers.
 */
@Composable
fun MiniActivityBar(snapshot: WalkSnapshot, modifier: Modifier = Modifier) {
    val total = kotlin.math.max(1L, snapshot.durationSec.toLong())
    val walkFrac = snapshot.walkOnlyDurationSec.toFloat() / total.toFloat()
    val talkFrac = snapshot.talkDurationSec.toFloat() / total.toFloat()
    val meditateFrac = snapshot.meditateDurationSec.toFloat() / total.toFloat()
    val capsule = RoundedCornerShape(2.dp)
    val mossColor = pilgrimColors.moss.copy(alpha = 0.5f)
    val rustColor = pilgrimColors.rust.copy(alpha = 0.6f)
    val dawnColor = pilgrimColors.dawn.copy(alpha = 0.6f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(percent = 50)),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (walkFrac > 0.01f) {
            Box(
                Modifier.weight(walkFrac).fillMaxHeight()
                    .clip(capsule)
                    .background(mossColor),
            )
        }
        if (talkFrac > 0.01f) {
            Box(
                Modifier.weight(talkFrac).fillMaxHeight()
                    .clip(capsule)
                    .background(rustColor),
            )
        }
        if (meditateFrac > 0.01f) {
            Box(
                Modifier.weight(meditateFrac).fillMaxHeight()
                    .clip(capsule)
                    .background(dawnColor),
            )
        }
    }
}

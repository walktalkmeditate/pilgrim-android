// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * The recurring parchment-surface text-action chip: stone-tinted pill,
 * caption label, disabled state dimmed to M3's 0.38 content alpha.
 * Used by the U11 pending-row affordances (Transcribe, Retry) and the
 * model-download sheet's failure retry.
 */
@Composable
internal fun StoneChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    verticalPadding: Dp = 4.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(pilgrimColors.stone.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 12.dp, vertical = verticalPadding),
    ) {
        Text(
            text = text,
            style = pilgrimType.caption,
            color = pilgrimColors.stone,
        )
    }
}

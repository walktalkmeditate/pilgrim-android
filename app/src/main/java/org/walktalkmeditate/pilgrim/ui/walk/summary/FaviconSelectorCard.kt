// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * "Mark this walk" card with three toggle buttons (flame/leaf/star).
 * iOS reference: `FaviconSelectorView.swift`. Tap selects, tap same
 * again deselects.
 */
@Composable
fun FaviconSelectorCard(
    selected: WalkFavicon?,
    onSelect: (WalkFavicon?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Text(
                text = stringResource(R.string.summary_favicon_caption),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.big),
            ) {
                WalkFavicon.entries.forEach { fav ->
                    FaviconButton(
                        favicon = fav,
                        isSelected = selected == fav,
                        onTap = { onSelect(if (selected == fav) null else fav) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FaviconButton(
    favicon: WalkFavicon,
    isSelected: Boolean,
    onTap: () -> Unit,
) {
    val fillColor by animateColorAsState(
        targetValue = if (isSelected) pilgrimColors.stone
        else pilgrimColors.fog.copy(alpha = 0.15f),
        animationSpec = tween(durationMillis = 200),
        label = "favicon-fill",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) pilgrimColors.parchment else pilgrimColors.fog,
        animationSpec = tween(durationMillis = 200),
        label = "favicon-content",
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected) pilgrimColors.ink else pilgrimColors.fog,
        animationSpec = tween(durationMillis = 200),
        label = "favicon-label",
    )
    Column(
        modifier = Modifier.clickable(onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(fillColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = favicon.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = stringResource(favicon.labelRes),
            style = pilgrimType.micro,
            color = labelColor,
        )
    }
}

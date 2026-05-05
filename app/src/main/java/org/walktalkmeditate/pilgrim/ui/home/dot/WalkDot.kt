// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

/**
 * Per-row dot rendered at the calligraphy-path stroke origin. Stage 14
 * primary unit of the LazyColumn; Bucket 14-B layers favicon + arcs +
 * label text on top. For 14-A the dot is a flat circle + optional
 * favicon glyph + tap-to-expand handler.
 *
 * Stage 5-A lesson: animated alpha uses the
 * `Modifier.graphicsLayer { alpha = ... }` lambda form so updates run
 * in the draw phase, not composition.
 */
@Composable
fun WalkDot(
    snapshot: WalkSnapshot,
    sizeDp: Float,
    color: Color,
    opacity: Float,
    isNewest: Boolean,
    contentDescription: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .graphicsLayer { alpha = opacity }
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(sizeDp.dp)) {
            drawCircle(
                color = color,
                radius = size.minDimension / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
        snapshot.favicon?.let { faviconKey ->
            val favicon = WalkFavicon.entries.firstOrNull { it.rawValue == faviconKey }
            if (favicon != null) {
                Icon(
                    imageVector = favicon.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size((sizeDp * 0.5f).dp),
                )
            }
        }
    }
}

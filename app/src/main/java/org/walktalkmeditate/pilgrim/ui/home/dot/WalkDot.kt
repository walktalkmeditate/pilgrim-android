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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

private const val HALO_SCALE = 3.5f
private const val HALO_PEAK_ALPHA = 0.3f
private const val NEWEST_RING_OFFSET_DP = 16f
private const val NEWEST_RING_STROKE_DP = 1.5f
private const val NEWEST_RING_ALPHA = 0.7f

/**
 * Per-row dot rendered at the calligraphy-path stroke origin. Mirrors
 * iOS WalkDotView ambient glow + core fill + favicon overlay + newest-
 * walk ring stack.
 *
 * Layers (bottom → top):
 *  1. Ambient halo — radial gradient at [HALO_SCALE]× the core size,
 *     fading from `color` at [HALO_PEAK_ALPHA] alpha to transparent.
 *  2. Newest-walk ring (when [isNewest]) — outlined circle at
 *     `coreSize + NEWEST_RING_OFFSET_DP`, stroke 1.5dp, parchment-tone
 *     so it reads against the halo. Static (Reduce-Motion safe);
 *     animated breathing ripple is Bucket 14-D.
 *  3. Core fill — solid disc at the dot's pace-derived size.
 *  4. Favicon glyph (if [WalkSnapshot.favicon] non-null) — Material
 *     ImageVector at 50% of core size, parchment-tinted on the dot.
 *
 * Stage 5-A lesson: animated alpha runs through `Modifier.graphicsLayer
 * { alpha = ... }` lambda form so updates land in the draw phase, not
 * composition.
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
    val haloSizeDp = sizeDp * HALO_SCALE
    val ringSizeDp = sizeDp + NEWEST_RING_OFFSET_DP
    Box(
        modifier = modifier
            .size(haloSizeDp.dp)
            .graphicsLayer { alpha = opacity }
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        // 1. Halo — radial gradient at full Box size.
        Canvas(Modifier.size(haloSizeDp.dp)) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = HALO_PEAK_ALPHA),
                        Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = r,
                ),
                radius = r,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }

        // 2. Newest-walk ring (above halo, below core).
        if (isNewest) {
            Canvas(Modifier.size(ringSizeDp.dp)) {
                val r = size.minDimension / 2f
                drawCircle(
                    color = color.copy(alpha = NEWEST_RING_ALPHA),
                    radius = r,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(width = NEWEST_RING_STROKE_DP.dp.toPx()),
                )
            }
        }

        // 3. Core dot.
        Canvas(Modifier.size(sizeDp.dp)) {
            drawCircle(
                color = color,
                radius = size.minDimension / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }

        // 4. Favicon glyph.
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

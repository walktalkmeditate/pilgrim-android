// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.design.scenery.footprintPath

/**
 * One dot of the seek trail. [x]/[y] are frame fractions (y grows
 * downward, so the trail rises toward y = 0), [radiusDp] is dp (iOS
 * points), [alpha] multiplies the glyph color's own opacity.
 */
internal data class TrailDot(
    val x: Float,
    val y: Float,
    val radiusDp: Float,
    val alpha: Float,
)

/**
 * The seek trail's dot table — verbatim iOS
 * `WalkModeFootprints.swift:35-42@c1745e8`: 6 dots dissolving upward
 * with shrinking radius and fading opacity, x jittered around center.
 * Exposed as a function so JVM tests pin the geometry directly
 * (Stage 3-C rule: Robolectric proves composition, never draw).
 */
internal fun walkModeTrailDots(): List<TrailDot> = listOf(
    TrailDot(x = 0.5f, y = 0.85f, radiusDp = 1.6f, alpha = 1.0f),
    TrailDot(x = 0.3f, y = 0.65f, radiusDp = 1.3f, alpha = 0.85f),
    TrailDot(x = 0.7f, y = 0.55f, radiusDp = 1.3f, alpha = 0.7f),
    TrailDot(x = 0.4f, y = 0.38f, radiusDp = 1.0f, alpha = 0.5f),
    TrailDot(x = 0.6f, y = 0.20f, radiusDp = 1.0f, alpha = 0.35f),
    TrailDot(x = 0.5f, y = 0.05f, radiusDp = 0.7f, alpha = 0.22f),
)

/**
 * Static miniature of the path screen's mode language, for compact
 * rows (the journal quick view). Wander: the grounded pair. Seek: one
 * print beside a trail of dots dissolving upward into the unknown. No
 * animation — these are glances, not scenes; the drifting versions
 * live on the path screen only (`ui/path/PathFootprints.kt`).
 *
 * Port of iOS `WalkModeFootprints.swift@c1745e8`. Decorative:
 * semantics cleared, exactly like iOS `.accessibilityHidden(true)`.
 */
@Composable
fun WalkModeFootprints(
    isSeek: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clearAndSetSemantics { },
    ) {
        FootprintGlyph(color = color, rotationDegrees = -12f, mirror = true)
        if (isSeek) {
            DissolvingTrail(
                color = color,
                modifier = Modifier
                    .size(width = 10.dp, height = 18.dp)
                    .rotate(12f),
            )
        } else {
            FootprintGlyph(
                color = color.copy(alpha = color.alpha * 0.75f),
                rotationDegrees = 12f,
            )
        }
    }
}

@Composable
private fun FootprintGlyph(
    color: Color,
    rotationDegrees: Float,
    mirror: Boolean = false,
) {
    Canvas(
        modifier = Modifier
            .size(width = 10.dp, height = 16.dp)
            .rotate(rotationDegrees)
            .scale(scaleX = if (mirror) -1f else 1f, scaleY = 1f),
    ) {
        drawPath(
            path = footprintPath(Size(size.width, size.height)),
            color = color,
        )
    }
}

@Composable
private fun DissolvingTrail(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        walkModeTrailDots().forEach { dot ->
            drawCircle(
                color = color,
                radius = dot.radiusDp.dp.toPx(),
                center = Offset(dot.x * size.width, dot.y * size.height),
                // DrawScope modulates this with the color's own alpha —
                // same math as iOS `context.opacity` over the passed color.
                alpha = dot.alpha,
            )
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Static seasonal-tinted tree silhouette for AboutView's vignette.
 * Three layered canopy ovals (decreasing size, increasing alpha) +
 * a narrow trunk rectangle. Simpler than iOS's animated SceneryItemView
 * — same wabi-sabi intent, no TimelineView animation. Acknowledged
 * port-time degradation in the spec.
 */
@Composable
fun SeasonalTree(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        drawTree(color)
    }
}

private fun DrawScope.drawTree(color: Color) {
    val w = this.size.width
    val h = this.size.height

    // Trunk: narrow rectangle bottom-center.
    val trunkWidth = w * 0.14f
    val trunkHeight = h * 0.30f
    drawRect(
        color = color.copy(alpha = 0.40f),
        topLeft = Offset((w - trunkWidth) / 2f, h - trunkHeight),
        size = Size(trunkWidth, trunkHeight),
    )

    // Outer canopy shadow: largest, lowest alpha, slightly offset.
    drawOval(
        color = color.copy(alpha = 0.12f),
        topLeft = Offset(w * -0.04f, h * 0.08f),
        size = Size(w * 1.08f, h * 0.66f),
    )

    // Mid canopy.
    drawOval(
        color = color.copy(alpha = 0.30f),
        topLeft = Offset(0f, h * 0.10f),
        size = Size(w, h * 0.62f),
    )

    // Inner canopy: smallest, highest alpha, top-centered.
    drawOval(
        color = color.copy(alpha = 0.50f),
        topLeft = Offset(w * 0.06f, h * 0.05f),
        size = Size(w * 0.88f, h * 0.50f),
    )
}

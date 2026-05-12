// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.scenery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/**
 * Footprint outline — verbatim port of iOS `FootprintShape.swift@db4196e`.
 *
 * Anatomical 8-oval foot: heel + outer-edge + ball-of-foot pads, then
 * big toe + 4 descending small toes above. The prior 6-oval version
 * (1 body + 1 big toe + 4 small toes) was a simplification that read
 * as a generic 5-bubble blob rather than a print.
 *
 * Pure path math. Consumers call
 * `Canvas(size) { drawPath(footprintPath(size), ...) }` directly.
 */
fun footprintPath(size: Size): Path {
    val p = Path()
    if (size.width <= 0f || size.height <= 0f) return p
    val w = size.width
    val h = size.height

    // Heel — rounded oval at the bottom
    p.addOval(
        Rect(
            offset = Offset(w * 0.22f, h * 0.75f),
            size = Size(w * 0.50f, h * 0.25f),
        ),
    )
    // Outer edge — connects heel to ball along the pinky side
    p.addOval(
        Rect(
            offset = Offset(w * 0.50f, h * 0.48f),
            size = Size(w * 0.22f, h * 0.34f),
        ),
    )
    // Ball of foot — wide pad below the toes
    p.addOval(
        Rect(
            offset = Offset(w * 0.08f, h * 0.38f),
            size = Size(w * 0.62f, h * 0.22f),
        ),
    )
    // Big toe — largest, on the inner (left) side
    p.addOval(
        Rect(
            offset = Offset(w * 0.10f, h * 0.18f),
            size = Size(w * 0.24f, h * 0.24f),
        ),
    )
    // Second toe — slightly smaller, tucked next to big toe
    p.addOval(
        Rect(
            offset = Offset(w * 0.32f, h * 0.10f),
            size = Size(w * 0.18f, h * 0.22f),
        ),
    )
    // Third toe — middle
    p.addOval(
        Rect(
            offset = Offset(w * 0.48f, h * 0.06f),
            size = Size(w * 0.16f, h * 0.20f),
        ),
    )
    // Fourth toe — smaller
    p.addOval(
        Rect(
            offset = Offset(w * 0.62f, h * 0.10f),
            size = Size(w * 0.14f, h * 0.18f),
        ),
    )
    // Pinky toe — smallest, set back
    p.addOval(
        Rect(
            offset = Offset(w * 0.72f, h * 0.18f),
            size = Size(w * 0.12f, h * 0.14f),
        ),
    )
    return p
}

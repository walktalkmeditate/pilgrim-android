// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.scenery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/**
 * Footprint outline — verbatim port of iOS `FootprintShape.swift`.
 *
 * Asymmetric oval-with-toes: body is an oval covering the lower 65 %
 * of the box, with one big toe + four descending small toes above it.
 * Used by `ExpandCardSheet` header glyph.
 *
 * Pure path math. Consumers call
 * `Canvas(size) { drawPath(footprintPath(size), ...) }` directly.
 */
fun footprintPath(size: Size): Path {
    val p = Path()
    if (size.width <= 0f || size.height <= 0f) return p
    val w = size.width
    val h = size.height
    val bodyRect = Rect(
        offset = Offset(w * 0.20f, h * 0.30f),
        size = Size(w * 0.60f, h * 0.65f),
    )
    p.addOval(bodyRect)
    p.addOval(
        Rect(
            offset = Offset(w * 0.05f, h * 0.05f),
            size = Size(w * 0.30f, h * 0.28f),
        ),
    )
    val smallToes = listOf(
        Rect(Offset(w * 0.40f, h * 0.00f), Size(w * 0.20f, h * 0.22f)),
        Rect(Offset(w * 0.55f, h * 0.04f), Size(w * 0.18f, h * 0.20f)),
        Rect(Offset(w * 0.68f, h * 0.10f), Size(w * 0.16f, h * 0.18f)),
        Rect(Offset(w * 0.78f, h * 0.18f), Size(w * 0.14f, h * 0.16f)),
    )
    smallToes.forEach { p.addOval(it) }
    return p
}

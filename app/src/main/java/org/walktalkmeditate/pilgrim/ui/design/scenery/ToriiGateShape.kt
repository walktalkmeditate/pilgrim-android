// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.scenery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/**
 * Torii gate outline — verbatim port of iOS `ToriiGateShape.swift`.
 *
 * Two horizontal beams (top kasagi + lintel) plus two vertical pillars.
 * Used by MilestoneMarker; shareable with future ToriiScenery refactor.
 *
 * Pure path math.
 */
fun toriiGatePath(size: Size): Path {
    val p = Path()
    if (size.width <= 0f || size.height <= 0f) return p
    val w = size.width
    val h = size.height
    val pillarWidth = w * 0.10f
    val beamHeight = h * 0.12f

    p.addRect(
        Rect(
            offset = Offset(0f, 0f),
            size = Size(w, beamHeight),
        ),
    )
    p.addRect(
        Rect(
            offset = Offset(w * 0.05f, beamHeight + h * 0.10f),
            size = Size(w * 0.90f, beamHeight),
        ),
    )
    p.addRect(
        Rect(
            offset = Offset(w * 0.18f, beamHeight),
            size = Size(pillarWidth, h - beamHeight),
        ),
    )
    p.addRect(
        Rect(
            offset = Offset(w - w * 0.18f - pillarWidth, beamHeight),
            size = Size(pillarWidth, h - beamHeight),
        ),
    )
    return p
}

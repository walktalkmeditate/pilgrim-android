// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/**
 * 10-ellipse footprint silhouette mirroring iOS `FootprintShape.swift`:
 * heel + outer edge + ball + 5 toes. Path is constructed in the
 * caller's `width × height` rectangle; tints + scales applied at draw
 * time via Canvas drawPath.
 */
object FootprintShape {

    fun path(width: Float, height: Float): Path = Path().apply {
        // Heel — rounded oval at the bottom
        addOval(rect(width * 0.22f, height * 0.75f, width * 0.50f, height * 0.25f))
        // Outer edge — connects heel to ball along the pinky side
        addOval(rect(width * 0.50f, height * 0.48f, width * 0.22f, height * 0.34f))
        // Ball of foot
        addOval(rect(width * 0.08f, height * 0.38f, width * 0.62f, height * 0.22f))
        // Big toe — largest, on the inner (left) side
        addOval(rect(width * 0.10f, height * 0.18f, width * 0.24f, height * 0.24f))
        // Second toe
        addOval(rect(width * 0.32f, height * 0.10f, width * 0.18f, height * 0.22f))
        // Third toe — middle
        addOval(rect(width * 0.48f, height * 0.06f, width * 0.16f, height * 0.20f))
        // Fourth toe
        addOval(rect(width * 0.62f, height * 0.10f, width * 0.14f, height * 0.18f))
        // Pinky toe — smallest, set back
        addOval(rect(width * 0.72f, height * 0.18f, width * 0.12f, height * 0.14f))
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float): Rect =
        Rect(offset = Offset(x, y), size = Size(w, h))
}

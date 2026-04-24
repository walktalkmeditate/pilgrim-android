// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import android.graphics.Matrix
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stage 7-C: Moon terminator glyph. Mirrors iOS's approach — a
 * filled polygon built from a semicircle of lit edge + a reverse arc
 * of terminator ellipse (x scaled by `2*illumination - 1`), mirrored
 * for waxing vs waning so the lit limb is on the correct side.
 *
 * Pure geometry; testable via [Path.computeBounds]. The renderer fills
 * this path with the palette's ink at a low alpha and strokes the
 * outline at even lower alpha — both handled in the main renderer.
 */
internal object EtegamiMoonGlyph {

    private const val SEMI_CIRCLE_STEPS = 64

    /**
     * Returns a closed [Path] representing the lit portion of the moon
     * centered at ([cx], [cy]) with [radius] on the canvas.
     *
     * [illumination] in `[0.0, 1.0]` — 0 is fully dark, 1 is full moon.
     * [isWaxing] true when the moon is waxing (lit limb on the right in
     * northern-hemisphere convention); false when waning.
     */
    fun terminatorPath(
        illumination: Double,
        isWaxing: Boolean,
        cx: Float,
        cy: Float,
        radius: Float,
    ): Path {
        // k = 1 - 2*illumination so the terminator ellipse carves the
        // correct shape:
        //   illumination=0 → k= 1 → terminator overlays lit edge → zero area (new moon)
        //   illumination=0.5 (first quarter) → k= 0 → terminator on y-axis → right half
        //   illumination=1 (full) → k=-1 → terminator on left edge → full disc
        val k = (1.0 - 2.0 * illumination.coerceIn(0.0, 1.0)).toFloat()
        val path = Path()
        // Lit-edge semicircle: from top (angle = -π/2) sweeping to
        // bottom (angle = +π/2) along x > 0 (right half-disk).
        for (step in 0..SEMI_CIRCLE_STEPS) {
            val t = -PI / 2.0 + PI * step / SEMI_CIRCLE_STEPS
            val x = cx + radius * cos(t).toFloat()
            val y = cy + radius * sin(t).toFloat()
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Terminator ellipse: from bottom back to top along x scaled by k.
        for (step in 0..SEMI_CIRCLE_STEPS) {
            val t = PI / 2.0 - PI * step / SEMI_CIRCLE_STEPS
            val x = cx + radius * k * cos(t).toFloat()
            val y = cy + radius * sin(t).toFloat()
            path.lineTo(x, y)
        }
        path.close()
        if (!isWaxing) {
            // Mirror across the vertical axis through cx: flip x so the
            // lit limb appears on the left (waning / post-full).
            val matrix = Matrix().apply { setScale(-1f, 1f, cx, 0f) }
            path.transform(matrix)
        }
        return path
    }
}

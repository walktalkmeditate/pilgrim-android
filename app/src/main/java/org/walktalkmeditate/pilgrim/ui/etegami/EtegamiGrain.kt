// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import java.util.Random

/**
 * Stage 7-C: seeded paper-grain dots overlaid on the etegami canvas.
 *
 * Mirrors iOS's seed = 12345 constant so the grain pattern is
 * reproducible across platforms + runs. Renderer draws each dot as
 * an ink-tinted circle at low alpha.
 */
internal data class GrainDot(
    val x: Float,
    val y: Float,
    val radius: Float,
)

internal object EtegamiGrain {

    const val DEFAULT_COUNT = 3000
    private const val MIN_RADIUS = 0.5f
    private const val MAX_RADIUS = 1.5f

    /**
     * Produces [count] deterministic dots for the given [seed] across
     * a canvas of [width] × [height]. Dots are uniformly distributed;
     * radii span [MIN_RADIUS, MAX_RADIUS]. Deterministic: same seed →
     * identical output List.
     */
    fun dots(
        seed: Long,
        count: Int = DEFAULT_COUNT,
        width: Int,
        height: Int,
    ): List<GrainDot> {
        require(width > 0) { "width must be > 0 (got $width)" }
        require(height > 0) { "height must be > 0 (got $height)" }
        require(count >= 0) { "count must be >= 0 (got $count)" }
        val random = Random(seed)
        return List(count) {
            GrainDot(
                x = random.nextFloat() * width,
                y = random.nextFloat() * height,
                radius = MIN_RADIUS + random.nextFloat() * (MAX_RADIUS - MIN_RADIUS),
            )
        }
    }
}

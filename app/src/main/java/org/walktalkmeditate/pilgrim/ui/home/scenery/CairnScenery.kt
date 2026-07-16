// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import java.time.Instant
import java.time.ZoneId

/**
 * Port of `SceneryItemView.swift` cairn branch — stones raised by a seek
 * that found places. Deliberately static: stones do not sway, so this is
 * the one standing scenery with no time subscription at all. The stack
 * grows with the walk's arrivals ([SceneryPlacement.stones]), winter caps
 * the top stone with snow, and a trace of the dawn halo the clearing
 * wore on the map glows over the stack.
 */
@Composable
internal fun CairnScenery(
    sizeDp: Dp,
    tintColor: Color,
    walkDateMs: Long,
    stones: Int,
) {
    val month = remember(walkDateMs) {
        Instant.ofEpochMilli(walkDateMs).atZone(ZoneId.systemDefault()).monthValue
    }
    val isWinter = cairnHasWinterCap(month)

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        // Ghost stones — slight offset, low alpha (iOS blur 1.2 dropped).
        translate(left = cx - s * 1.06f / 2f + 1.5f, top = cy - s * 1.06f / 2f + 1.5f) {
            drawPath(
                path = cairnStonesPath(GeomSize(s * 1.06f, s * 1.06f), stones),
                color = tintColor.copy(alpha = 0.10f),
            )
        }

        // Main stack
        translate(left = cx - s / 2f, top = cy - s / 2f) {
            drawPath(
                path = cairnStonesPath(GeomSize(s, s), stones),
                color = tintColor.copy(alpha = 0.35f),
            )
        }

        // Snow cap on the top stone (the winter month idiom).
        if (isWinter) {
            drawOval(
                color = Color.White.copy(alpha = 0.35f),
                topLeft = Offset(
                    cx + s * 0.02f - s * 0.30f / 2f,
                    cy - s * 0.46f - s * 0.10f / 2f,
                ),
                size = GeomSize(s * 0.30f, s * 0.10f),
            )
        }

        // A trace of the dawn halo the clearing wore on the map. iOS
        // layers a blur-7 disc on top; a radial gradient reads as light
        // rather than a translucent disk (the LanternScenery precedent).
        val haloRadius = s * 0.75f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(DAWN_HALO.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(cx, cy),
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = Offset(cx, cy),
        )
    }
}

internal fun cairnHasWinterCap(month: Int): Boolean = month == 12 || month <= 2

/** iOS literal Color(0.77, 0.58, 0.42) — the map clearing's dawn halo. */
private val DAWN_HALO = Color(0xFFC4946B)

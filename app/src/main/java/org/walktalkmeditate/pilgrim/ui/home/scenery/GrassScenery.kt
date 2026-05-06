// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin

/**
 * Port of `SceneryItemView.swift` grass branch — 5 blades with per-blade
 * sway + gust + optional seed heads + dewdrops (5-9 hour gate). Each
 * blade has its own height + xPos + delay so wind ripple feels organic.
 */
@Composable
internal fun GrassScenery(
    sizeDp: Dp,
    grassColor: Color,
    walkDateMs: Long,
) {
    val zonedDate = remember(walkDateMs) {
        Instant.ofEpochMilli(walkDateMs).atZone(ZoneId.systemDefault())
    }
    val hour = zonedDate.hour
    val hasDew = hour in 5..8

    val timeSec by sceneryTimeSeconds()

    val blades = remember {
        listOf(
            Quintuple5(0.1f, 0.55f, false),
            Quintuple5(0.28f, 0.8f, true),
            Quintuple5(0.45f, 0.5f, false),
            Quintuple5(0.62f, 0.9f, true),
            Quintuple5(0.78f, 0.65f, true),
        )
    }

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        for ((i, blade) in blades.withIndex()) {
            val delay = i * 0.3
            val sway = (sin(timeSec * 1.0 + delay) * blade.height * 3.0).toFloat()
            val gust = (sin(timeSec * 0.3 + delay * 0.5) * sin(timeSec * 0.3) * 2.0).toFloat()
            val totalSway = sway + gust
            val bladeH = s * blade.height
            val baseX = s * (blade.xPos - 0.5f)

            // Blade — capsule rotated at base
            val bladeWidth = s * 0.04f
            val bladeBaseY = cy + s * 0.2f
            val bladeTopY = bladeBaseY - bladeH

            rotate(totalSway * 1.5f, pivot = Offset(cx + baseX, bladeBaseY)) {
                drawRoundRect(
                    color = grassColor.copy(alpha = 0.25f),
                    topLeft = Offset(cx + baseX - bladeWidth / 2f, bladeTopY),
                    size = GeomSize(bladeWidth, bladeH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bladeWidth / 2f, bladeWidth / 2f),
                )
            }

            // Seed head (small circle near tip)
            if (blade.hasSeed) {
                val seedR = s * 0.035f
                val seedDx = sin(totalSway * (PI / 180.0)).toFloat() * bladeH * 0.4f
                drawCircle(
                    color = grassColor.copy(alpha = 0.35f),
                    radius = seedR,
                    center = Offset(cx + baseX + seedDx, cy + s * 0.2f - bladeH * 0.75f),
                )
            }

            // Dewdrop — pulsing alpha
            if (hasDew) {
                val dewPulse = ((sin(timeSec * 4.0 + i) + 1.0) / 2.0).toFloat()
                val dewR = s * 0.0175f
                val dewDx = sin(totalSway * (PI / 180.0)).toFloat() * bladeH * 0.35f
                drawCircle(
                    color = Color.White.copy(alpha = 0.2f + dewPulse * 0.4f),
                    radius = dewR,
                    center = Offset(cx + baseX + dewDx, cy + s * 0.2f - bladeH * 0.65f),
                )
            }
        }
    }
}

private data class Quintuple5(val xPos: Float, val height: Float, val hasSeed: Boolean)

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Two staffs leaning together, dōgyō ninin: the ink-scroll mark of a walk
 * that honored a Way to its end. Port of the `SceneryItemView.swift`
 * staffs branch (`SceneryItemView.swift:178-194@7c200bf`). Static like the
 * cairn, with neither its winter cap nor its dawn halo. The shadow is the
 * shape at 1.06× offset 1.5, tint 0.1 (iOS's 1.2 blur dropped, as the
 * cairn's is), under the body at tint 0.35.
 */
@Composable
internal fun StaffsScenery(
    sizeDp: Dp,
    tintColor: Color,
) {
    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()
        val minStroke = 1.dp.toPx()

        val shadow = s * 1.06f
        translate(left = cx - shadow / 2f + 1.5.dp.toPx(), top = cy - shadow / 2f + 1.5.dp.toPx()) {
            drawStaffs(staffsGeometry(Size(shadow, shadow), minStroke), tintColor.copy(alpha = 0.10f))
        }
        translate(left = cx - s / 2f, top = cy - s / 2f) {
            drawStaffs(staffsGeometry(Size(s, s), minStroke), tintColor.copy(alpha = 0.35f))
        }
    }
}

/** The two staffs and their knot in one frame, with the stroke they are drawn at. */
internal data class StaffsGeometry(
    val leftStaff: Pair<Offset, Offset>,
    val rightStaff: Pair<Offset, Offset>,
    val knot: Rect,
    val strokeWidth: Float,
)

/**
 * iOS `StaffsShape` (`StaffsShape.swift:3-15@7c200bf`): staffs from
 * (0.20w, h) to (0.55w, 0.08h) and from (0.80w, h) to (0.45w, 0.08h),
 * crossing just below their tops, and a ring knot at (0.42w, 0) sized
 * 0.16w × 0.10h, all stroked at `max(1, 0.08w)` with round caps.
 * [minStrokePx] is iOS's 1 pt floor in this canvas's pixels.
 */
internal fun staffsGeometry(size: Size, minStrokePx: Float): StaffsGeometry {
    val w = size.width
    val h = size.height
    return StaffsGeometry(
        leftStaff = Offset(w * 0.20f, h) to Offset(w * 0.55f, h * 0.08f),
        rightStaff = Offset(w * 0.80f, h) to Offset(w * 0.45f, h * 0.08f),
        knot = Rect(offset = Offset(w * 0.42f, 0f), size = Size(w * 0.16f, h * 0.10f)),
        strokeWidth = maxOf(minStrokePx, w * 0.08f),
    )
}

/** One path, as iOS strokes one: the default miter join, round caps. */
private fun DrawScope.drawStaffs(geometry: StaffsGeometry, color: Color) {
    val path = Path().apply {
        moveTo(geometry.leftStaff.first.x, geometry.leftStaff.first.y)
        lineTo(geometry.leftStaff.second.x, geometry.leftStaff.second.y)
        moveTo(geometry.rightStaff.first.x, geometry.rightStaff.first.y)
        lineTo(geometry.rightStaff.second.x, geometry.rightStaff.second.y)
        addOval(geometry.knot)
    }
    drawPath(path = path, color = color, style = Stroke(width = geometry.strokeWidth, cap = StrokeCap.Round))
}

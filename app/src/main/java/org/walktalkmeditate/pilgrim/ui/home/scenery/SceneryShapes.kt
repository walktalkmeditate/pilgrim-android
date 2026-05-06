// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure path builders for the seven scenery types — direct ports of the
 * iOS `*Shape` files in `pilgrim-ios/Pilgrim/Views/Scenery/`. Kept as
 * private helpers so each type Composable can re-derive its path on the
 * fly without allocating a `Shape` wrapper. All paths are normalized to
 * a `Size` rect; consumers translate / scale on draw.
 */

/** TreeShape.swift — two stacked triangles + trunk rect. */
internal fun treePath(size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        moveTo(w * 0.5f, 0f)
        lineTo(w * 0.2f, h * 0.55f)
        lineTo(w * 0.8f, h * 0.55f)
        close()

        moveTo(w * 0.5f, h * 0.2f)
        lineTo(w * 0.1f, h * 0.75f)
        lineTo(w * 0.9f, h * 0.75f)
        close()

        addRect(Rect(w * 0.42f, h * 0.75f, w * 0.42f + w * 0.16f, h))
    }
}

/** WinterTreeShape.swift — bare trunk + 6 quad-curve branches. */
internal fun winterTreePath(size: Size): Path {
    val w = size.width
    val h = size.height
    val cx = w * 0.48f

    val path = Path()
    addWinterTrunk(path, cx, w, h)

    // (y, length, angleDeg, side ±1, hasTwig) — verbatim iOS table.
    val branches = listOf(
        Quintuple(0.32f, 0.32f, -50f, -1f, true),
        Quintuple(0.42f, 0.26f, -45f, 1f, true),
        Quintuple(0.50f, 0.18f, -55f, -1f, false),
        Quintuple(0.58f, 0.20f, -40f, 1f, true),
        Quintuple(0.68f, 0.13f, -50f, -1f, false),
        Quintuple(0.75f, 0.10f, -42f, 1f, false),
    )
    for (b in branches) {
        addWinterBranch(path, cx, w, h, b.a, b.b, b.c, b.d, b.e)
    }
    return path
}

private data class Quintuple<A, B, C, D, E>(
    val a: A, val b: B, val c: C, val d: D, val e: E,
)

private fun addWinterTrunk(path: Path, cx: Float, w: Float, h: Float) {
    val baseW = w * 0.07f
    val topW = w * 0.025f
    val top = h * 0.25f

    path.moveTo(cx - baseW, h)
    path.quadraticBezierTo(cx - baseW * 0.6f, h * 0.55f, cx - topW, top)
    path.lineTo(cx + topW, top)
    path.quadraticBezierTo(cx + baseW * 0.4f, h * 0.55f, cx + baseW, h)
    path.close()
}

private fun addWinterBranch(
    path: Path,
    cx: Float,
    w: Float,
    h: Float,
    yFrac: Float,
    lenFrac: Float,
    angleDeg: Float,
    side: Float,
    hasTwig: Boolean,
) {
    val startY = h * yFrac
    val startX = cx + side * w * 0.03f
    val rad = angleDeg.toDouble() * Math.PI / 180.0
    val len = w * lenFrac
    val thickness = w * 0.022f
    val cosRad = cos(rad).toFloat()
    val sinRad = sin(rad).toFloat()

    val midX = startX + side * len * 0.5f
    val curveY = startY + len * sinRad * 0.5f - w * 0.02f
    val endX = startX + side * len * cosRad
    val endY = startY + len * sinRad

    path.moveTo(startX, startY - thickness)
    path.quadraticBezierTo(midX, curveY - thickness, endX, endY)
    path.quadraticBezierTo(midX, curveY + thickness, startX, startY + thickness)
    path.close()

    if (hasTwig) {
        val twigStart = 0.55f
        val twigX = startX + side * len * twigStart * cosRad
        val twigY = startY + len * twigStart * sinRad
        val twigLen = len * 0.35f
        val twigRad = rad + side.toDouble() * -0.6
        val twigEndX = twigX + side * twigLen * cos(twigRad).toFloat()
        val twigEndY = twigY + twigLen * sin(twigRad).toFloat()
        val tw = thickness * 0.5f

        path.moveTo(twigX - tw, twigY)
        path.quadraticBezierTo(
            (twigX + twigEndX) / 2f,
            (twigY + twigEndY) / 2f - w * 0.01f,
            twigEndX,
            twigEndY,
        )
        path.lineTo(twigX + tw, twigY)
        path.close()
    }
}

/** MountainShape.swift — left peak + right peak + small upper triangle. */
internal fun mountainPath(size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        moveTo(0f, h)
        lineTo(w * 0.35f, h * 0.15f)
        lineTo(w * 0.5f, h * 0.3f)
        lineTo(w * 0.7f, 0f)
        lineTo(w, h)
        close()

        moveTo(w * 0.6f, h * 0.15f)
        lineTo(w * 0.7f, 0f)
        lineTo(w * 0.8f, h * 0.15f)
        close()
    }
}

/** ToriiGateShape.swift — pillars + curved kasagi + nuki crossbeam. */
internal fun toriiGatePath(size: Size): Path {
    val w = size.width
    val h = size.height
    val pillarW = w * 0.09f
    val leftX = w * 0.18f
    val rightX = w * 0.82f - pillarW

    return Path().apply {
        // Left pillar
        moveTo(leftX, h * 0.22f)
        lineTo(leftX - w * 0.02f, h)
        lineTo(leftX + pillarW + w * 0.02f, h)
        lineTo(leftX + pillarW, h * 0.22f)
        close()

        // Right pillar
        moveTo(rightX, h * 0.22f)
        lineTo(rightX - w * 0.02f, h)
        lineTo(rightX + pillarW + w * 0.02f, h)
        lineTo(rightX + pillarW, h * 0.22f)
        close()

        // Kasagi (top beam) — curved, extends past pillars.
        val beamH = h * 0.07f
        val overhang = w * 0.08f
        moveTo(-overhang, h * 0.12f + beamH)
        quadraticBezierTo(w * 0.5f, h * 0.04f, w + overhang, h * 0.12f + beamH)
        lineTo(w + overhang, h * 0.12f + beamH * 2f)
        quadraticBezierTo(w * 0.5f, h * 0.12f + beamH, -overhang, h * 0.12f + beamH * 2f)
        close()

        // Nuki (crossbeam)
        val nY = h * 0.3f
        addRect(Rect(leftX, nY, rightX + pillarW, nY + h * 0.04f))
    }
}

/** LanternShape.swift — pedestal + stem + body box + roof + finial. */
internal fun lanternPath(size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        // Base pedestal
        addRect(Rect(w * 0.3f, h * 0.88f, w * 0.7f, h))
        // Stem
        addRect(Rect(w * 0.42f, h * 0.7f, w * 0.58f, h * 0.88f))
        // Body box
        addRect(Rect(w * 0.22f, h * 0.35f, w * 0.78f, h * 0.7f))
        // Roof — peaked pyramid
        moveTo(w * 0.12f, h * 0.35f)
        lineTo(w * 0.5f, h * 0.12f)
        lineTo(w * 0.88f, h * 0.35f)
        close()
        // Finial
        addOval(Rect(w * 0.42f, h * 0.04f, w * 0.58f, h * 0.14f))
    }
}

/** LanternWindowShape.swift — small inset rect for the warm-glow cutout. */
internal fun lanternWindowPath(size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        addRect(Rect(w * 0.32f, h * 0.42f, w * 0.68f, h * 0.64f))
    }
}

/**
 * MoonShape.swift — outer disc minus offset inner disc to form a crescent.
 * Compose's Path supports `op(a, b, Difference)` — used at draw time.
 */
internal fun moonOuterAndInner(size: Size): Pair<Path, Path> {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.45f, h * 0.5f)
    val outerR = minOf(w, h) * 0.45f
    val innerR = outerR * 0.78f
    val innerCenter = Offset(center.x + outerR * 0.5f, center.y - outerR * 0.08f)

    val outer = Path().apply {
        addOval(Rect(center.x - outerR, center.y - outerR, center.x + outerR, center.y + outerR))
    }
    val inner = Path().apply {
        addOval(
            Rect(
                innerCenter.x - innerR,
                innerCenter.y - innerR,
                innerCenter.x + innerR,
                innerCenter.y + innerR,
            ),
        )
    }
    return outer to inner
}

/** Simple downward triangle for snow caps on mountains. */
internal fun trianglePath(size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        moveTo(w * 0.5f, 0f)
        lineTo(0f, h)
        lineTo(w, h)
        close()
    }
}

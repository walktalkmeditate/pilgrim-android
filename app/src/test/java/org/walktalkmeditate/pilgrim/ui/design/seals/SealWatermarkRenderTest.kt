// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSealBitmapRenderer

/**
 * The watermark on a rendered seal (parity spec G §6, correction 19): the
 * walk's line on a wander seal, and on an honor seal the Way's line too,
 * both turned with the seal's own rotation. Each seal is drawn twice, with
 * and without its watermark; every pixel that differs must lie on a line
 * where the fit and the rotation put it, and each line must leave pixels.
 *
 * [GraphicsMode.Mode.NATIVE]: the project's LEGACY Canvas draws nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SealWatermarkRenderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 1024, so the 1/512 stroke is 2 px wide. */
    private val size = 1_024

    private val base = SealSpec(
        uuid = "watermark-seal",
        startMillis = 1_700_000_000_000L,
        distanceMeters = 1_234.0,
        durationSeconds = 3_600.0,
        displayDistance = "",
        unitLabel = "",
        ink = Color.Black,
    )

    private fun p(lat: Double, lon: Double) = SealRoutePoint(latitude = lat, longitude = lon)

    private fun render(spec: SealSpec): IntArray {
        val bitmap = EtegamiSealBitmapRenderer.renderToBitmap(spec, Color.Black, size, context)
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        return pixels
    }

    private data class Segment(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        fun distanceTo(x: Double, y: Double): Double {
            val dx = x1 - x0
            val dy = y1 - y0
            val t = (((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            return hypot(x - (x0 + t * dx), y - (y0 + t * dy))
        }
    }

    /** A unit-square line, scaled to the canvas and turned about its centre as `Canvas.rotate` turns it. */
    private fun rotated(unitLine: FloatArray, degrees: Float): Segment {
        val c = size / 2.0
        val r = Math.toRadians(degrees.toDouble())
        fun turn(ux: Float, uy: Float): Pair<Double, Double> {
            val x = ux * size - c
            val y = uy * size - c
            return (c + x * cos(r) - y * sin(r)) to (c + x * sin(r) + y * cos(r))
        }
        val (x0, y0) = turn(unitLine[0], unitLine[1])
        val (x1, y1) = turn(unitLine[2], unitLine[3])
        return Segment(x0, y0, x1, y1)
    }

    private fun changedPixels(with: IntArray, without: IntArray): List<Pair<Int, Int>> =
        with.indices.filter { with[it] != without[it] }.map { (it % size) to (it / size) }

    private fun assertOnLines(changed: List<Pair<Int, Int>>, lines: List<Segment>) {
        val stray = changed.filter { (x, y) -> lines.minOf { it.distanceTo(x + 0.5, y + 0.5) } > 2.5 }
        assertEquals("every changed pixel lies on a watermark line: ${stray.take(5)}", 0, stray.size)
        lines.forEachIndexed { index, line ->
            val onIt = changed.count { (x, y) -> line.distanceTo(x + 0.5, y + 0.5) <= 1.5 }
            assertTrue("line $index left pixels ($onIt)", onIt > 100)
        }
    }

    @Test
    fun `a wander seal draws the walk's line, turned with the seal`() {
        val watermark = SealWatermark.of(walk = listOf(p(0.0, 0.0), p(0.0, 0.01)), way = null)!!
        val spec = base.copy(watermark = watermark)

        val changed = changedPixels(render(spec), render(base))

        val rotation = sealGeometry(spec).rotationDeg
        assertTrue("the fixture's seal turns, so north is not up", rotation % 90f != 0f)
        assertOnLines(changed, listOf(rotated(watermark.walkLine, rotation)))
    }

    @Test
    fun `an honor seal draws the Way's line beneath the walk's, in one fit`() {
        val watermark = SealWatermark.of(
            walk = listOf(p(0.002, 0.0), p(0.002, 0.005)),
            way = listOf(p(0.0, 0.0), p(0.0, 0.01)),
        )!!
        val spec = base.copy(watermark = watermark)

        val changed = changedPixels(render(spec), render(base))

        val rotation = sealGeometry(spec).rotationDeg
        assertOnLines(
            changed,
            listOf(rotated(watermark.walkLine, rotation), rotated(watermark.wayLine!!, rotation)),
        )
    }

    @Test
    fun `the lines are the seal's ink, faint`() {
        val watermark = SealWatermark.of(walk = listOf(p(0.0, 0.0), p(0.0, 0.01)), way = null)!!
        val spec = base.copy(watermark = watermark)
        val with = render(spec)
        val without = render(base)
        val line = rotated(watermark.walkLine, sealGeometry(spec).rotationDeg)
        // Off the seal's own marks, a watermark pixel is the bare ink at
        // the walk line's 5.5%, never anything stronger.
        val bare = with.indices.filter { without[it] == 0 && with[it] != 0 }
        assertTrue(bare.isNotEmpty())
        bare.forEach { i ->
            val pixel = with[i]
            assertEquals("ink colour", 0, pixel and 0x00FFFFFF)
            val alpha = pixel ushr 24
            assertTrue("alpha $alpha at most 5.5%", alpha <= (0.055 * 255).toInt() + 1)
            assertTrue(line.distanceTo(i % size + 0.5, i / size + 0.5) <= 2.5)
        }
    }

    @Test
    fun `a seal with no watermark draws exactly as before`() {
        val plain = render(base)
        val again = render(base.copy(watermark = null))
        assertTrue(plain.contentEquals(again))
        assertEquals(Bitmap.Config.ARGB_8888, EtegamiSealBitmapRenderer.renderToBitmap(base, Color.Black, 8, context).config)
    }
}

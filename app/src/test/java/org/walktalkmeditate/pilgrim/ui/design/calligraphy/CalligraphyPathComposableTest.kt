// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two kinds of coverage:
 *
 * 1. **Composition smoke tests** — confirm [CalligraphyPath] composes
 *    without crashing for empty, single, two, eight, zero-pace, and
 *    zero-distance inputs. These do NOT guarantee the Canvas draw
 *    lambda fired (Robolectric's render backend is a stub); they
 *    prove composition is reachable and [Modifier.height] resolves
 *    cleanly for each case.
 * 2. **Direct [buildRibbonPath] tests** — actually exercise the
 *    `Path` + `cubicTo` + `lineTo` + `close` code path that the
 *    Composable would invoke during a real draw pass. Assertions
 *    use `Path.getBounds()` which returns `Rect.Zero` for an unused
 *    Path — a non-zero bounds means the calls landed on a live
 *    `android.graphics.Path`.
 *
 * The split matches the CLAUDE.md platform-object-builder convention:
 * the risky runtime path (Skia Path construction) gets a direct test
 * that hits the real builder; composition correctness is covered by
 * the smoke tests and the pure-JVM math tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalligraphyPathComposableTest {

    @get:Rule val composeRule = createComposeRule()

    private fun spec(i: Int): CalligraphyStrokeSpec = CalligraphyStrokeSpec(
        uuid = "uuid-$i",
        startMillis = 1_700_000_000_000L + i * 86_400_000L,
        distanceMeters = 2_000.0 + i * 500.0,
        averagePaceSecPerKm = 500.0 + i * 20.0,
        ink = Color.Black,
    )

    private fun renderInsideSizedBox(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            Box(modifier = Modifier.size(width = 400.dp, height = 1000.dp)) {
                content()
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    // --- composition smoke tests --------------------------------------

    @Test fun `composition smoke — empty list`() {
        renderInsideSizedBox { CalligraphyPath(strokes = emptyList()) }
    }

    @Test fun `composition smoke — single stroke`() {
        renderInsideSizedBox { CalligraphyPath(strokes = listOf(spec(0))) }
    }

    @Test fun `composition smoke — two strokes`() {
        renderInsideSizedBox { CalligraphyPath(strokes = listOf(spec(0), spec(1))) }
    }

    @Test fun `composition smoke — eight strokes`() {
        renderInsideSizedBox { CalligraphyPath(strokes = (0 until 8).map(::spec)) }
    }

    @Test fun `composition smoke — zero pace falls back cleanly`() {
        val zeroPace = spec(0).copy(averagePaceSecPerKm = 0.0)
        renderInsideSizedBox { CalligraphyPath(strokes = listOf(zeroPace, spec(1))) }
    }

    @Test fun `composition smoke — zero distance stroke`() {
        val zeroDist = spec(0).copy(distanceMeters = 0.0, averagePaceSecPerKm = 0.0)
        renderInsideSizedBox { CalligraphyPath(strokes = listOf(zeroDist, spec(1))) }
    }

    // --- direct Path builder tests ------------------------------------

    @Test fun `buildRibbonPath produces a non-empty path with meaningful bounds`() {
        val path = buildRibbonPath(
            startX = 100f, startY = 50f,
            endX = 150f, endY = 140f,
            halfWidth = 2f, cpOffsetX = 15f,
            verticalSpacingPx = 90f,
        )
        val bounds = path.getBounds()
        assertTrue("bounds width should be > 0, got ${bounds.width}", bounds.width > 0f)
        assertTrue("bounds height should be > 0, got ${bounds.height}", bounds.height > 0f)
        // X span covers start±halfWidth to end±halfWidth — at minimum 2 * halfWidth + |start-end|
        // Since our control points can push outside the endpoints, we assert >= that lower bound.
        assertTrue(
            "x span should include stroke width; bounds=$bounds",
            bounds.width >= 2f * 2f,
        )
    }

    @Test fun `buildRibbonPath with zero offset produces a straight ribbon`() {
        val path = buildRibbonPath(
            startX = 100f, startY = 50f,
            endX = 100f, endY = 140f,
            halfWidth = 3f, cpOffsetX = 0f,
            verticalSpacingPx = 90f,
        )
        val bounds = path.getBounds()
        // Straight vertical ribbon — X span exactly 2 * halfWidth.
        assertEquals(6f, bounds.width, 0.01f)
        // Y span from 50 to 140 = 90.
        assertEquals(90f, bounds.height, 0.01f)
    }

    @Test fun `buildRibbonPath with degenerate zero-width geometry still closes`() {
        // halfWidth = 0 + cpOffset = 0: path is a degenerate zero-area
        // curve. Must still construct without throwing; bounds collapse
        // to a line's bounding box.
        val path = buildRibbonPath(
            startX = 100f, startY = 50f,
            endX = 100f, endY = 140f,
            halfWidth = 0f, cpOffsetX = 0f,
            verticalSpacingPx = 90f,
        )
        val bounds = path.getBounds()
        assertEquals(0f, bounds.width, 0.01f)
        assertEquals(90f, bounds.height, 0.01f)
    }
}

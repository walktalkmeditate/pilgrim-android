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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rasterizes [CalligraphyPath] through Compose's draw pipeline to
 * confirm the Path + DrawScope calls actually execute. Pixel values
 * aren't asserted — this is a builder-path smoke test, mirroring the
 * project convention for platform-object constructors (see CLAUDE.md).
 *
 * Each test wraps the composable in a sized [Box] so the Canvas gets
 * real dimensions; without that, `fillMaxWidth()` would resolve to 0
 * and the DrawScope lambda would short-circuit before hitting the
 * Path + cubicTo calls we actually want to exercise.
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

    @Test fun `empty list renders without crashing`() {
        renderInsideSizedBox { CalligraphyPath(strokes = emptyList()) }
    }

    @Test fun `single stroke renders without crashing`() {
        renderInsideSizedBox { CalligraphyPath(strokes = listOf(spec(0))) }
    }

    @Test fun `two strokes render without crashing`() {
        renderInsideSizedBox { CalligraphyPath(strokes = listOf(spec(0), spec(1))) }
    }

    @Test fun `eight strokes render without crashing`() {
        renderInsideSizedBox { CalligraphyPath(strokes = (0 until 8).map(::spec)) }
    }

    @Test fun `zero pace stroke renders without crashing`() {
        val zeroPace = spec(0).copy(averagePaceSecPerKm = 0.0)
        renderInsideSizedBox { CalligraphyPath(strokes = listOf(zeroPace, spec(1))) }
    }

    @Test fun `zero distance stroke renders without crashing`() {
        val zeroDist = spec(0).copy(distanceMeters = 0.0, averagePaceSecPerKm = 0.0)
        renderInsideSizedBox { CalligraphyPath(strokes = listOf(zeroDist, spec(1))) }
    }
}

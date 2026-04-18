// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

    @Test fun `empty list renders without crashing`() {
        composeRule.setContent { CalligraphyPath(strokes = emptyList()) }
        composeRule.onRoot().assertExists()
    }

    @Test fun `single stroke renders without crashing`() {
        composeRule.setContent { CalligraphyPath(strokes = listOf(spec(0))) }
        composeRule.onRoot().assertExists()
    }

    @Test fun `two strokes render without crashing`() {
        composeRule.setContent { CalligraphyPath(strokes = listOf(spec(0), spec(1))) }
        composeRule.onRoot().assertExists()
    }

    @Test fun `eight strokes render without crashing`() {
        composeRule.setContent {
            CalligraphyPath(strokes = (0 until 8).map(::spec))
        }
        composeRule.onRoot().assertExists()
    }
}

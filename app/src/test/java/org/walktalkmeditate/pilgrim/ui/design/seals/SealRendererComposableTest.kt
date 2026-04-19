// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * Composition smoke tests for [SealRenderer]. The actual DrawScope
 * execution goes through Android's graphics stack; Robolectric provides
 * the shadow implementations.
 *
 * [SealGeometryTest] + [SealHashTest] cover the deterministic geometry
 * builder with plain JVM tests (fast, no Android runtime).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SealRendererComposableTest {

    @get:Rule val composeRule = createComposeRule()

    private fun spec(i: Int) = SealSpec(
        uuid = "seal-test-$i",
        startMillis = 1_700_000_000_000L + i * 86_400_000L,
        distanceMeters = 2_000.0 + i * 500.0,
        durationSeconds = 1_800.0 + i * 60.0,
        displayDistance = "%.1f".format(2.0 + i * 0.5),
        unitLabel = "km",
        ink = Color(0xFFA0634B),       // PilgrimColors.rust baseline
    )

    private fun renderInsideSizedBox(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            Box(modifier = Modifier.size(200.dp)) {
                content()
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `single seal renders without crashing`() {
        renderInsideSizedBox { SealRenderer(spec = spec(0)) }
    }

    @Test fun `seal with zero distance renders without crashing`() {
        val zeroDistance = spec(0).copy(distanceMeters = 0.0, displayDistance = "0")
        renderInsideSizedBox { SealRenderer(spec = zeroDistance) }
    }

    @Test fun `seal with long distance label renders without crashing`() {
        val longLabel = spec(0).copy(displayDistance = "123.45", unitLabel = "km")
        renderInsideSizedBox { SealRenderer(spec = longLabel) }
    }

    @Test fun `seal with empty strings renders without crashing`() {
        val emptyLabel = spec(0).copy(displayDistance = "", unitLabel = "")
        renderInsideSizedBox { SealRenderer(spec = emptyLabel) }
    }

    @Test fun `multiple seals with distinct uuids render without crashing`() {
        composeRule.setContent {
            Column {
                (0 until 8).forEach { i ->
                    Box(modifier = Modifier.size(120.dp)) {
                        SealRenderer(spec = spec(i))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `seal with tiny canvas does not crash`() {
        // 10dp Canvas — everything collapses to sub-pixel sizes.
        composeRule.setContent {
            Box(modifier = Modifier.size(10.dp)) {
                SealRenderer(spec = spec(0))
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }
}

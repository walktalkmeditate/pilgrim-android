// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed Compose tests for [WaveformBar].
 *
 * Robolectric's Compose Canvas backend is a stub — these tests do NOT
 * verify rasterized pixels. They cover:
 *
 * 1. Composition succeeds (no crash) for typical and edge inputs.
 * 2. Tap fires `onSeek` with the expected fraction (`x / width`).
 * 3. Continuous drag fires `onSeek` multiple times with progressing
 *    fractions, matching iOS `WaveformBarView`'s `.onChanged` semantics
 *    (see plan note: NOT `.onEnded`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaveformBarTest {

    @get:Rule val composeRule = createComposeRule()

    private val sampleSamples = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.5f, 0.3f, 0.1f, 0.4f)

    @Test
    fun `composes without crash for typical samples`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(width = 200.dp, height = 32.dp)) {
                WaveformBar(
                    samples = sampleSamples,
                    progress = 0.5f,
                    inactiveColor = Color.Gray,
                    activeColor = Color.Black,
                    onSeek = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test
    fun `composes without crash for empty samples`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(width = 200.dp, height = 32.dp)) {
                WaveformBar(
                    samples = floatArrayOf(),
                    progress = 0f,
                    inactiveColor = Color.Gray,
                    activeColor = Color.Black,
                    onSeek = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test
    fun `tap fires onSeek with fraction matching tap-x divided by width`() {
        val seeks = mutableListOf<Float>()
        composeRule.setContent {
            Box(modifier = Modifier.size(width = 200.dp, height = 32.dp)) {
                WaveformBar(
                    samples = sampleSamples,
                    progress = 0f,
                    inactiveColor = Color.Gray,
                    activeColor = Color.Black,
                    onSeek = { seeks += it },
                    modifier = Modifier
                        .testTag("waveform")
                        .size(width = 200.dp, height = 32.dp),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("waveform").performTouchInput {
            click(Offset(centerX, centerY))
        }
        composeRule.waitForIdle()

        assertTrue("expected at least one seek; got $seeks", seeks.isNotEmpty())
        // centerX / width should be ~0.5
        assertEquals(0.5f, seeks.last(), 0.05f)
    }

    @Test
    fun `drag fires onSeek continuously with progressing fractions`() {
        val seeks = mutableListOf<Float>()
        composeRule.setContent {
            Box(modifier = Modifier.size(width = 200.dp, height = 32.dp)) {
                WaveformBar(
                    samples = sampleSamples,
                    progress = 0f,
                    inactiveColor = Color.Gray,
                    activeColor = Color.Black,
                    onSeek = { seeks += it },
                    modifier = Modifier
                        .testTag("waveform")
                        .size(width = 200.dp, height = 32.dp),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("waveform").performTouchInput {
            down(Offset(0f, centerY))
            moveTo(Offset(centerX * 0.5f, centerY))
            moveTo(Offset(centerX, centerY))
            moveTo(Offset(centerX * 1.5f, centerY))
            up()
        }
        composeRule.waitForIdle()

        // Continuous seek: at least 2 callbacks during the drag.
        assertTrue(
            "expected continuous-drag seeks; got ${seeks.size} callbacks: $seeks",
            seeks.size >= 2,
        )
        // Fractions should monotonically progress (allowing equal values for
        // any duplicate move events). At minimum: max > min.
        assertTrue(
            "expected progressing fractions; got $seeks",
            seeks.max() > seeks.min(),
        )
        // All fractions stay in [0, 1].
        seeks.forEach { f ->
            assertTrue("fraction $f outside [0,1]", f in 0f..1f)
        }
    }
}

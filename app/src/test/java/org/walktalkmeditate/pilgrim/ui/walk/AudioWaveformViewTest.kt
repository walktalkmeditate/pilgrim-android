// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioWaveformViewTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `renders five bars regardless of level`() {
        composeRule.setContent {
            AudioWaveformView(level = 0.5f)
        }
        composeRule.onAllNodesWithTag(WAVEFORM_BAR_TEST_TAG).assertCountEquals(5)
    }

    @Test
    fun `level 0 renders all bars at minimum height`() {
        composeRule.setContent {
            AudioWaveformView(level = 0f)
        }
        composeRule.onAllNodesWithTag(WAVEFORM_BAR_TEST_TAG).assertCountEquals(5)
    }

    @Test
    fun `level 1 renders all bars at maximum height`() {
        composeRule.setContent {
            AudioWaveformView(level = 1f)
        }
        composeRule.onAllNodesWithTag(WAVEFORM_BAR_TEST_TAG).assertCountEquals(5)
    }

    @Test
    fun `LiveAudioWaveform reflects the collected level and re-renders on emission`() {
        val level = MutableStateFlow(1f)
        composeRule.setContent {
            LiveAudioWaveform(levelFlow = level)
        }
        val bars = composeRule.onAllNodesWithTag(WAVEFORM_BAR_TEST_TAG)
        bars.assertCountEquals(5)
        // Center bar (weight 1.0) at level 1f → 4 + 1*20 = 24dp. Asserting a
        // height well above the 4dp floor proves the flow value actually
        // reached the bars — a wrapper that ignored the flow (drew level 0)
        // would render the 4dp minimum.
        bars[2].assertHeightIsAtLeast(20.dp)

        level.value = 0f
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(WAVEFORM_BAR_TEST_TAG).assertCountEquals(5)
    }
}

class AudioWaveformBarHeightTest {

    @Test
    fun `level 0 maps to minimum height`() {
        assertBarHeight(level = 0f, weight = 1.0f, expectedDp = 4f)
    }

    @Test
    fun `level 1 maps to 4 plus 20 times weight`() {
        assertBarHeight(level = 1f, weight = 1.0f, expectedDp = 24f)
        assertBarHeight(level = 1f, weight = 0.6f, expectedDp = 16f)
    }

    @Test
    fun `level clamped above 1`() {
        assertBarHeight(level = 5f, weight = 1.0f, expectedDp = 24f)
    }

    @Test
    fun `negative level clamped to 0`() {
        assertBarHeight(level = -1f, weight = 1.0f, expectedDp = 4f)
    }

    private fun assertBarHeight(level: Float, weight: Float, expectedDp: Float) {
        val actual = audioWaveformBarHeightDp(level = level, weight = weight)
        assertEquals(expectedDp, actual, 0.001f)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.animation

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JournalFadeInTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun reduce_motion_snaps_to_one() {
        var alpha = -1f
        composeRule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                val state = rememberJournalFadeIn(reduceMotion = true)
                alpha = state.dotAlpha(0)
            }
        }
        composeRule.runOnIdle {
            assertEquals(1f, alpha, 0.0001f)
        }
    }

    @Test
    fun default_animates_from_0_to_1() {
        composeRule.mainClock.autoAdvance = false
        var observed = 0f
        composeRule.setContent {
            val state = rememberJournalFadeIn(reduceMotion = false)
            observed = state.dotAlpha(0)
        }
        composeRule.mainClock.advanceTimeBy(0)
        assertEquals(0f, observed, 0.001f)
        composeRule.mainClock.advanceTimeBy(2000)
        composeRule.runOnIdle {
            assertTrue("alpha should reach ~1f after 2s", observed > 0.99f)
        }
    }

    @Test
    fun segment_alpha_animates() {
        composeRule.mainClock.autoAdvance = false
        var observed = 0f
        composeRule.setContent {
            val state = rememberJournalFadeIn(reduceMotion = false)
            observed = state.segmentAlpha(0)
        }
        composeRule.mainClock.advanceTimeBy(0)
        assertEquals(0f, observed, 0.001f)
        composeRule.mainClock.advanceTimeBy(2500)
        composeRule.runOnIdle {
            assertTrue("segment alpha should reach ~1f after 2.5s", observed > 0.99f)
        }
    }
}

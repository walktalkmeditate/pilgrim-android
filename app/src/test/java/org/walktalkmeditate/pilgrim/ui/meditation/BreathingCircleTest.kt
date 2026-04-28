// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

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
import org.walktalkmeditate.pilgrim.data.sounds.BreathRhythm

/**
 * Composition smoke tests for [BreathingCircle]. Animation timing + the
 * gradient draw aren't asserted — Robolectric's Canvas is a stub
 * (Stage 3-C lesson); these tests confirm the composable reaches the
 * draw pipeline without throwing for each [BreathRhythm] preset shape:
 *  - default (Calm — symmetric inhale/exhale, no holds)
 *  - 4-phase Box (4-4-4-4 — exercises both holds)
 *  - 4-7-8 Relaxing (asymmetric inhale + hold-in)
 *  - None (static, no animation driver)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BreathingCircleTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `circle composes without crashing for default rhythm`() {
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                BreathingCircle(
                    moss = Color(0xFF7A8B6F),
                    breathRhythm = BreathRhythm.byId(0),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `circle composes without crashing for 4-phase Box rhythm`() {
        // Box (id 3) uses all four phases — inhale + hold-in + exhale +
        // hold-out — so this exercises the keyframe spec's hold paths.
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                BreathingCircle(
                    moss = Color(0xFF7A8B6F),
                    breathRhythm = BreathRhythm.byId(3),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `circle composes without crashing for asymmetric Relaxing rhythm`() {
        // Relaxing (4-7-8) — inhale + hold-in + exhale, no hold-out.
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                BreathingCircle(
                    moss = Color(0xFF7A8B6F),
                    breathRhythm = BreathRhythm.byId(2),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `circle composes without crashing for None (static)`() {
        // None (id 6) — no animation driver runs at all; circle is a
        // still focal point at the inhaled scale.
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                BreathingCircle(
                    moss = Color(0xFF7A8B6F),
                    breathRhythm = BreathRhythm.byId(6),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }
}

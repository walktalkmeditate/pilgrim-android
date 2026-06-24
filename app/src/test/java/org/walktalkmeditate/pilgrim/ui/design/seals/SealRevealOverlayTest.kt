// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composition smoke tests for [SealRevealOverlay]. Animation timing
 * and haptic firing aren't asserted — Compose animations + LaunchedEffect
 * timing is hard to pin down in unit tests; manual on-device QA
 * verifies the 3-phase choreography matches the iOS reference.
 *
 * These tests confirm the composable reaches Compose's draw pipeline
 * without crashing for the inputs the production code passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SealRevealOverlayTest {

    @get:Rule val composeRule = createComposeRule()

    private fun testSpec() = SealSpec(
        uuid = "reveal-test-0",
        startMillis = 1_700_000_000_000L,
        distanceMeters = 5_000.0,
        durationSeconds = 1_800.0,
        displayDistance = "5.00",
        unitLabel = "km",
        ink = Color(0xFFA0634B),
    )

    @Test fun `overlay renders without crashing`() {
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SealRevealOverlay(spec = testSpec(), onDismiss = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `overlay renders with small sealSize`() {
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SealRevealOverlay(
                    spec = testSpec(),
                    onDismiss = {},
                    sealSizeDp = 80,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `overlay renders with zero-distance spec`() {
        val zeroDistance = testSpec().copy(
            distanceMeters = 0.0,
            displayDistance = "0",
            unitLabel = "m",
        )
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SealRevealOverlay(spec = zeroDistance, onDismiss = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `overlay renders with transparent ink placeholder`() {
        val transparent = testSpec().copy(ink = Color.Transparent)
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SealRevealOverlay(spec = transparent, onDismiss = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    // AF62: the Canvas-drawn seal must be labeled so TalkBack doesn't focus a
    // blank node. (The tap-to-dismiss onClickLabel is verified by reading +
    // device pass; the seal label is the unit-assertable pin.)
    @Test fun `seal is labeled for TalkBack`() {
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SealRevealOverlay(spec = testSpec(), onDismiss = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Walk seal").assertExists()
    }

    // AF62: the tap-anywhere-to-dismiss action must carry a label so TalkBack
    // announces "double-tap to dismiss". autoAdvance=false pins the phase
    // before the auto-dismiss timer (which disables the clickable), keeping the
    // OnClick action present so its label is assertable.
    @Test fun `tap-to-dismiss overlay exposes the dismiss click label`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SealRevealOverlay(spec = testSpec(), onDismiss = {})
            }
        }
        val label = composeRule.onNode(hasClickAction())
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick].label
        assertEquals("Dismiss", label)
    }

    @Test fun `overlay renders with isMilestone flag set`() {
        // Stage 4-D: the milestone celebration adds a 2nd haptic pulse
        // and 0.5s extra hold. Animation timing + haptic firing aren't
        // asserted (Robolectric stubs both); this test confirms the
        // composition path with `isMilestone = true` doesn't throw.
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SealRevealOverlay(
                    spec = testSpec(),
                    onDismiss = {},
                    isMilestone = true,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }
}

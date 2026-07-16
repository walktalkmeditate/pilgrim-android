// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WalkDotComposableTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tap fires onTap callback`() {
        var tapped = false
        composeRule.setContent {
            WalkDot(
                snapshot = WalkSnapshot(
                    id = 1L, uuid = "u", startMs = 0L, distanceM = 1000.0,
                    durationSec = 600.0, averagePaceSecPerKm = 360.0,
                    cumulativeDistanceM = 1000.0, talkDurationSec = 0L,
                    meditateDurationSec = 0L, favicon = null, isShared = false,
                    weatherCondition = null,
                ),
                sizeDp = 12f,
                color = Color.Black,
                talkColor = Color.Red,
                meditateColor = Color.Blue,
                opacity = 1f,
                isNewest = true,
                contentDescription = "test-dot",
                onTap = { tapped = true },
            )
        }
        composeRule.onNodeWithContentDescription("test-dot").performClick()
        assertTrue(tapped)
    }

    // AF57 (iOS PR #45): the dot's tap is handled by detectTapGestures via
    // pointerInput, which is INVISIBLE to TalkBack. The dot must also carry a
    // Button role + a semantics click action so it's announced and operable.
    // performSemanticsAction(OnClick) exercises the a11y action specifically
    // (not the touch gesture), so it fails if only the pointerInput exists.
    @Test
    fun `dot exposes button role and a TalkBack-operable click action`() {
        var tapped = false
        composeRule.setContent {
            WalkDot(
                snapshot = WalkSnapshot(
                    id = 1L, uuid = "u", startMs = 0L, distanceM = 1000.0,
                    durationSec = 600.0, averagePaceSecPerKm = 360.0,
                    cumulativeDistanceM = 1000.0, talkDurationSec = 0L,
                    meditateDurationSec = 0L, favicon = null, isShared = false,
                    weatherCondition = null,
                ),
                sizeDp = 12f,
                color = Color.Black,
                talkColor = Color.Red,
                meditateColor = Color.Blue,
                opacity = 1f,
                isNewest = false,
                contentDescription = "test-dot",
                onTap = { tapped = true },
            )
        }
        val dot = composeRule.onNodeWithContentDescription("test-dot")
        dot.assert(hasClickAction())
        dot.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        dot.performSemanticsAction(SemanticsActions.OnClick)
        assertTrue("the TalkBack click action must invoke onTap", tapped)
    }

    // The archived dot is a SEPARATE code path (early-return Box) carrying its
    // own copy of the same semantics block. Cover it so a future divergence
    // (e.g. extracting one branch and forgetting the semantics) is caught.
    @Test
    fun `archived dot also exposes button role and a TalkBack-operable click action`() {
        var tapped = false
        composeRule.setContent {
            WalkDot(
                snapshot = WalkSnapshot(
                    id = 1L, uuid = "u", startMs = 0L, distanceM = 1000.0,
                    durationSec = 600.0, averagePaceSecPerKm = 360.0,
                    cumulativeDistanceM = 1000.0, talkDurationSec = 0L,
                    meditateDurationSec = 0L, favicon = null, isShared = false,
                    weatherCondition = null,
                ),
                sizeDp = 12f,
                color = Color.Black,
                talkColor = Color.Red,
                meditateColor = Color.Blue,
                opacity = 1f,
                isNewest = false,
                isArchived = true,
                contentDescription = "archived-dot",
                onTap = { tapped = true },
            )
        }
        val dot = composeRule.onNodeWithContentDescription("archived-dot")
        dot.assert(hasClickAction())
        dot.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        dot.performSemanticsAction(SemanticsActions.OnClick)
        assertTrue("the archived dot's TalkBack click action must invoke onTap", tapped)
    }

    // iOS a11y frame parity (WalkDotView @ c1745e8): live dots use
    // `.frame(width: max(44, size * 3.5))`, archived rings a fixed 44×44.

    @Test
    fun `smallest live dot still spans the 44 dp minimum tap target`() {
        composeRule.setContent {
            WalkDot(
                snapshot = snapshot(),
                sizeDp = 8f,
                color = Color.Black,
                talkColor = Color.Red,
                meditateColor = Color.Blue,
                opacity = 1f,
                isNewest = false,
                contentDescription = "small-dot",
                onTap = {},
            )
        }
        composeRule.onNodeWithContentDescription("small-dot")
            .assertWidthIsEqualTo(44.dp)
            .assertHeightIsEqualTo(44.dp)
    }

    @Test
    fun `largest live dot box scales to 3_5x its core size`() {
        composeRule.setContent {
            WalkDot(
                snapshot = snapshot(),
                sizeDp = 22f,
                color = Color.Black,
                talkColor = Color.Red,
                meditateColor = Color.Blue,
                opacity = 1f,
                isNewest = false,
                contentDescription = "large-dot",
                onTap = {},
            )
        }
        composeRule.onNodeWithContentDescription("large-dot")
            .assertWidthIsEqualTo(77.dp)
            .assertHeightIsEqualTo(77.dp)
    }

    @Test
    fun `archived ring keeps a fixed 44 dp tap target`() {
        composeRule.setContent {
            WalkDot(
                snapshot = snapshot(),
                sizeDp = 8f,
                color = Color.Black,
                talkColor = Color.Red,
                meditateColor = Color.Blue,
                opacity = 0.5f,
                isNewest = false,
                isArchived = true,
                contentDescription = "archived-small",
                onTap = {},
            )
        }
        composeRule.onNodeWithContentDescription("archived-small")
            .assertWidthIsEqualTo(44.dp)
            .assertHeightIsEqualTo(44.dp)
    }

    private fun snapshot() = WalkSnapshot(
        id = 1L, uuid = "u", startMs = 0L, distanceM = 1000.0,
        durationSec = 600.0, averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = 1000.0, talkDurationSec = 0L,
        meditateDurationSec = 0L, favicon = null, isShared = false,
        weatherCondition = null,
    )
}

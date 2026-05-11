// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSharingButtonsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersThreeShareActions_whenRouteHasPoints() {
        composeRule.setContent {
            WalkSharingButtons(
                hasRoute = true,
                isGoshuinGenerating = false,
                isEtegamiGenerating = false,
                onGoshuinShare = {},
                onEtegamiShare = {},
                onWalkJourneyShare = {},
            )
        }
        composeRule.onNodeWithTag("sharing-card").assertExists()
        composeRule.onNodeWithTag("share-button-goshuin").assertExists()
        composeRule.onNodeWithTag("share-button-etegami").assertExists()
        composeRule.onNodeWithTag("share-button-walk-journey").assertExists()
    }

    @Test
    fun doesNotRender_whenHasRouteIsFalse() {
        composeRule.setContent {
            WalkSharingButtons(
                hasRoute = false,
                isGoshuinGenerating = false,
                isEtegamiGenerating = false,
                onGoshuinShare = {},
                onEtegamiShare = {},
                onWalkJourneyShare = {},
            )
        }
        composeRule.onNodeWithTag("sharing-card").assertDoesNotExist()
    }

    @Test
    fun goshuinButton_disabledWhenGenerating() {
        composeRule.setContent {
            WalkSharingButtons(
                hasRoute = true,
                isGoshuinGenerating = true,
                isEtegamiGenerating = false,
                onGoshuinShare = {},
                onEtegamiShare = {},
                onWalkJourneyShare = {},
            )
        }
        composeRule.onNodeWithTag("share-button-goshuin").assertIsNotEnabled()
        composeRule.onNodeWithTag("share-button-etegami").assertIsEnabled()
    }

    @Test
    fun etegamiButton_disabledWhenGenerating() {
        composeRule.setContent {
            WalkSharingButtons(
                hasRoute = true,
                isGoshuinGenerating = false,
                isEtegamiGenerating = true,
                onGoshuinShare = {},
                onEtegamiShare = {},
                onWalkJourneyShare = {},
            )
        }
        composeRule.onNodeWithTag("share-button-etegami").assertIsNotEnabled()
        composeRule.onNodeWithTag("share-button-goshuin").assertIsEnabled()
    }

    @Test
    fun goshuinButton_clickInvokesCallback() {
        var fired = 0
        composeRule.setContent {
            WalkSharingButtons(
                hasRoute = true,
                isGoshuinGenerating = false,
                isEtegamiGenerating = false,
                onGoshuinShare = { fired += 1 },
                onEtegamiShare = {},
                onWalkJourneyShare = {},
            )
        }
        composeRule.onNodeWithTag("share-button-goshuin").performClick()
        composeRule.waitForIdle()
        assert(fired == 1) { "expected goshuin callback, got fired=$fired" }
    }
}

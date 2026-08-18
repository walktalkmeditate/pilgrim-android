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
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.data.share.ExpiryOption

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSharingButtonsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun activeShare(expiryOption: ExpiryOption? = ExpiryOption.Cycle) = CachedShare(
        url = "https://walk.pilgrimapp.org/s/abc123",
        id = "abc123",
        expiryEpochMs = System.currentTimeMillis() + 600_000L,
        shareDateEpochMs = System.currentTimeMillis() - 600_000L,
        expiryOption = expiryOption,
    )

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
                activeCachedShare = null,
                onCachedShareEngaged = {},
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
                activeCachedShare = null,
                onCachedShareEngaged = {},
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
                activeCachedShare = null,
                onCachedShareEngaged = {},
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
                activeCachedShare = null,
                onCachedShareEngaged = {},
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
                activeCachedShare = null,
                onCachedShareEngaged = {},
            )
        }
        composeRule.onNodeWithTag("share-button-goshuin").performClick()
        composeRule.waitForIdle()
        assert(fired == 1) { "expected goshuin callback, got fired=$fired" }
    }

    // -- issue #222: cached-share branch in the journey footer --

    @Test
    fun noCachedShare_showsPlainButton_notTheBlock() {
        composeRule.setContent {
            WalkSharingButtons(
                hasRoute = true,
                isGoshuinGenerating = false,
                isEtegamiGenerating = false,
                onGoshuinShare = {},
                onEtegamiShare = {},
                onWalkJourneyShare = {},
                activeCachedShare = null,
                onCachedShareEngaged = {},
            )
        }
        composeRule.onNodeWithTag("share-button-walk-journey").assertExists()
        composeRule.onNodeWithTag("share-active-block").assertDoesNotExist()
    }

    @Test
    fun nonExpiredCachedShare_showsBlock_withUrlCopyShareAndReturnsCaption() {
        composeRule.setContent {
            WalkSharingButtons(
                hasRoute = true,
                isGoshuinGenerating = false,
                isEtegamiGenerating = false,
                onGoshuinShare = {},
                onEtegamiShare = {},
                onWalkJourneyShare = {},
                activeCachedShare = activeShare(),
                onCachedShareEngaged = {},
            )
        }
        composeRule.onNodeWithTag("share-button-walk-journey").assertDoesNotExist()
        composeRule.onNodeWithTag("share-active-block").assertExists()
        composeRule.onNodeWithTag("share-active-url").assertExists()
        composeRule.onNodeWithTag("share-active-copy").assertExists()
        composeRule.onNodeWithTag("share-active-share").assertExists()
        composeRule.onNodeWithTag("share-active-returns").assertExists()
    }

    @Test
    fun expiredCachedShare_fallsBackToPlainButton() {
        // issue #222 scope: expired is treated the same as never-shared,
        // NOT iOS's separate `returnedSection` layout. `WalkSharingButtons`
        // itself is expiry-agnostic — it only ever sees `activeCachedShare`
        // after the caller applies the same filter WalkSummaryScreen uses
        // in production (`cachedShare?.takeIf { !it.isExpiredAt() }`).
        val expired = CachedShare(
            url = "https://walk.pilgrimapp.org/s/expired",
            id = "expired",
            expiryEpochMs = System.currentTimeMillis() - 60_000L,
            shareDateEpochMs = System.currentTimeMillis() - 600_000L,
            expiryOption = ExpiryOption.Moon,
        )
        assert(expired.isExpiredAt()) { "fixture must actually be expired for this test to mean anything" }

        composeRule.setContent {
            WalkSharingButtons(
                hasRoute = true,
                isGoshuinGenerating = false,
                isEtegamiGenerating = false,
                onGoshuinShare = {},
                onEtegamiShare = {},
                onWalkJourneyShare = {},
                activeCachedShare = expired.takeIf { !it.isExpiredAt() },
                onCachedShareEngaged = {},
            )
        }
        composeRule.onNodeWithTag("share-button-walk-journey").assertExists()
        composeRule.onNodeWithTag("share-active-block").assertDoesNotExist()
    }
}

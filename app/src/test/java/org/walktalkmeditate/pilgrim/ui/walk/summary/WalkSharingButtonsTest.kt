// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
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
                cachedShare = null,
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
                cachedShare = null,
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
                cachedShare = null,
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
                cachedShare = null,
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
                cachedShare = null,
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
                cachedShare = null,
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
                cachedShare = activeShare(),
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

    // -- issue #225: an expired share has returned to the trail
    //    (`WalkSharingButtons.swift:310-344@7c200bf`) --

    private fun expiredShare(
        expiryOption: ExpiryOption? = ExpiryOption.Moon,
        expiryEpochMs: Long = NOW - 60_000L,
    ) = CachedShare(
        url = "https://walk.pilgrimapp.org/s/expired",
        id = "expired",
        expiryEpochMs = expiryEpochMs,
        shareDateEpochMs = NOW - 600_000L,
        expiryOption = expiryOption,
    )

    private fun setButtons(cachedShare: CachedShare?, onWalkJourneyShare: () -> Unit = {}) {
        composeRule.setContent {
            WalkSharingButtons(
                hasRoute = true,
                isGoshuinGenerating = false,
                isEtegamiGenerating = false,
                onGoshuinShare = {},
                onEtegamiShare = {},
                onWalkJourneyShare = onWalkJourneyShare,
                cachedShare = cachedShare,
                onCachedShareEngaged = {},
                nowEpochMs = NOW,
            )
        }
    }

    @Test
    fun expiredCachedShare_showsReturnedBlock_notTheActiveBlockOrPlainButton() {
        setButtons(expiredShare(expiryOption = ExpiryOption.Moon))

        composeRule.onNodeWithTag("share-returned-block").assertExists()
        composeRule.onNodeWithText("This walk has returned to the trail").assertExists()
        composeRule.onNodeWithText("Shared for 1 moon").assertExists()
        composeRule.onNodeWithText("Share again").assertExists()
        composeRule.onNodeWithTag("share-active-block").assertDoesNotExist()
        composeRule.onNodeWithTag("share-button-walk-journey").assertDoesNotExist()
    }

    @Test
    fun expiredCachedShare_withNoOption_saysTheWalkWasShared() {
        setButtons(expiredShare(expiryOption = null))

        composeRule.onNodeWithText("This walk was shared").assertExists()
        composeRule.onNodeWithText("Shared for", substring = true).assertDoesNotExist()
    }

    @Test
    fun cachedShare_expiringExactlyNow_rendersReturned() {
        // iOS `isExpired` is `expiry <= Date()`.
        setButtons(expiredShare(expiryEpochMs = NOW))

        composeRule.onNodeWithTag("share-returned-block").assertExists()
        composeRule.onNodeWithTag("share-active-block").assertDoesNotExist()
    }

    @Test
    fun shareAgain_opensTheJourneyShare() {
        var fired = 0
        setButtons(expiredShare(), onWalkJourneyShare = { fired += 1 })

        composeRule.onNodeWithTag("share-returned-share-again").performClick()
        composeRule.waitForIdle()

        assertEquals(1, fired)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

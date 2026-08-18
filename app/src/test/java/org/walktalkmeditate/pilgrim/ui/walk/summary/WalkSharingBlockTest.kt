// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.data.share.ExpiryOption

/**
 * Issue #222: [WalkSharingBlock] rendering + the Copy button's
 * clipboard write and Copy/Copied swap. The 2s auto-reset + rapid
 * double-tap generation guard is covered separately in
 * [WalkSharingBlockLogicTest] via [CopyToastState] with virtual time
 * — exercising an exact 2-second `delay()` through a real Compose
 * recomposition clock here would be slow and non-deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSharingBlockTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val share = CachedShare(
        url = "https://walk.pilgrimapp.org/s/abc123def456",
        id = "abc123def456",
        expiryEpochMs = System.currentTimeMillis() + 600_000L,
        shareDateEpochMs = System.currentTimeMillis() - 600_000L,
        expiryOption = ExpiryOption.Cycle,
    )

    @Test
    fun rendersKanjiAndLabel_whenExpiryOptionPresent() {
        composeRule.setContent {
            WalkSharingBlock(cachedShare = share, onOpenJourney = {}, onEngaged = {})
        }
        composeRule.onNodeWithTag("share-active-kanji").assertExists()
        composeRule.onNodeWithTag("share-active-label").assertExists()
        composeRule.onNodeWithText("1 CYCLE").assertExists()
    }

    @Test
    fun omitsKanjiAndLabel_whenExpiryOptionIsNull_butStillRendersCoreContent() {
        composeRule.setContent {
            WalkSharingBlock(
                cachedShare = share.copy(expiryOption = null),
                onOpenJourney = {},
                onEngaged = {},
            )
        }
        composeRule.onNodeWithTag("share-active-kanji").assertDoesNotExist()
        composeRule.onNodeWithTag("share-active-label").assertDoesNotExist()
        composeRule.onNodeWithTag("share-active-url").assertExists()
        composeRule.onNodeWithTag("share-active-returns").assertExists()
    }

    @Test
    fun showsUrlText() {
        composeRule.setContent {
            WalkSharingBlock(cachedShare = share, onOpenJourney = {}, onEngaged = {})
        }
        composeRule.onNodeWithText(share.url).assertExists()
    }

    @Test
    fun tappingUrlRow_invokesOnOpenJourney() {
        var fired = 0
        composeRule.setContent {
            WalkSharingBlock(cachedShare = share, onOpenJourney = { fired += 1 }, onEngaged = {})
        }
        composeRule.onNodeWithTag("share-active-url").performClick()
        composeRule.waitForIdle()
        assert(fired == 1) { "expected onOpenJourney callback, got fired=$fired" }
    }

    @Test
    fun copyButton_startsAsUnpressedCopyLabel() {
        composeRule.setContent {
            WalkSharingBlock(cachedShare = share, onOpenJourney = {}, onEngaged = {})
        }
        composeRule.onNodeWithText("Copy").assertExists()
        composeRule.onNodeWithText("Copied").assertDoesNotExist()
    }

    @Test
    fun copyButton_writesUrlToClipboard_andFlipsLabelToCopied() {
        composeRule.setContent {
            WalkSharingBlock(cachedShare = share, onOpenJourney = {}, onEngaged = {})
        }
        composeRule.onNodeWithTag("share-active-copy").performClick()
        composeRule.waitForIdle()

        val clipboard = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipped = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assert(clipped == share.url) { "expected clipboard to hold '${share.url}', got '$clipped'" }

        composeRule.onNodeWithText("Copied").assertExists()
        composeRule.onNodeWithText("Copy").assertDoesNotExist()
    }

    @Test
    fun copyButton_invokesOnEngaged() {
        var fired = 0
        composeRule.setContent {
            WalkSharingBlock(cachedShare = share, onOpenJourney = {}, onEngaged = { fired += 1 })
        }
        composeRule.onNodeWithTag("share-active-copy").performClick()
        composeRule.waitForIdle()
        assert(fired == 1) { "expected onEngaged callback from Copy, got fired=$fired" }
    }

    @Test
    fun shareButton_invokesOnEngaged() {
        var fired = 0
        composeRule.setContent {
            WalkSharingBlock(cachedShare = share, onOpenJourney = {}, onEngaged = { fired += 1 })
        }
        composeRule.onNodeWithTag("share-active-share").performClick()
        composeRule.waitForIdle()
        assert(fired == 1) { "expected onEngaged callback from Share, got fired=$fired" }
    }
}

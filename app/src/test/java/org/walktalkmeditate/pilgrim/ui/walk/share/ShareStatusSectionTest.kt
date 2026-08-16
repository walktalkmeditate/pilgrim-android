// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
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
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareStatusSectionTest {

    @get:Rule val composeRule = createComposeRule()

    private fun setSection(
        state: ShareCardState,
        canShare: Boolean = true,
        repairUnavailable: Boolean = false,
        expiryText: String = "May 21, 2026",
        onShare: () -> Unit = {},
        onOpenPreview: (String) -> Unit = {},
        onRetryMissingFiles: () -> Unit = {},
        onShareWithoutDroppedPhotos: () -> Unit = {},
        onCancelDroppedPhotoShare: () -> Unit = {},
    ) {
        composeRule.setContent {
            PilgrimTheme {
                ShareStatusSection(
                    state = state,
                    canShare = canShare,
                    repairUnavailable = repairUnavailable,
                    expiryText = expiryText,
                    routePoints = emptyList(),
                    onShare = onShare,
                    onOpenPreview = onOpenPreview,
                    onRetryMissingFiles = onRetryMissingFiles,
                    onShareWithoutDroppedPhotos = onShareWithoutDroppedPhotos,
                    onCancelDroppedPhotoShare = onCancelDroppedPhotoShare,
                )
            }
        }
    }

    // ---- UI-33/UI-66: the 8-case switch ------------------------------

    @Test
    fun `idle renders the Share Walk button enabled when canShare`() {
        setSection(ShareCardState.Idle, canShare = true)
        composeRule.onNodeWithTag("share-status-idle-button").assertIsDisplayed()
        composeRule.onNodeWithText("Share Walk").assertIsDisplayed()
        composeRule.onNodeWithTag("share-status-idle-button").assertIsEnabled()
    }

    @Test
    fun `idle disables the Share Walk button when canShare is false`() {
        setSection(ShareCardState.Idle, canShare = false)
        composeRule.onNodeWithTag("share-status-idle-button").assertIsNotEnabled()
    }

    @Test
    fun `tapping idle Share Walk fires onShare`() {
        var fired = false
        setSection(ShareCardState.Idle, canShare = true, onShare = { fired = true })
        composeRule.onNodeWithTag("share-status-idle-button").performClick()
        assertEquals(true, fired)
    }

    @Test
    fun `uploading renders the Sharing progress row`() {
        setSection(ShareCardState.Uploading)
        composeRule.onNodeWithText("Sharing…").assertIsDisplayed()
    }

    @Test
    fun `preparingPhotos renders N of M progress copy`() {
        setSection(ShareCardState.PreparingPhotos(completed = 2, total = 5))
        composeRule.onNodeWithText("Preparing photos… 2/5").assertIsDisplayed()
    }

    @Test
    fun `uploadingMedia renders Carrying your walk N of M plus the keep-open subtitle`() {
        setSection(ShareCardState.UploadingMedia(completed = 3, total = 8))
        composeRule.onNodeWithText("Carrying your walk… 3/8").assertIsDisplayed()
        composeRule.onNodeWithText("keep Pilgrim open while your walk uploads").assertIsDisplayed()
    }

    @Test
    fun `success renders the shared card with Shared badge and expiry text`() {
        setSection(ShareCardState.Success(url = "https://walk.pilgrimapp.org/s/abc"), expiryText = "May 21, 2026")
        composeRule.onNodeWithText("Shared").assertIsDisplayed()
        composeRule.onNodeWithText("Returns to the trail on May 21, 2026").assertIsDisplayed()
        composeRule.onNodeWithTag("share-status-partial-failure-block").assertDoesNotExist()
    }

    @Test
    fun `success open-preview tap fires onOpenPreview with the url`() {
        var opened: String? = null
        setSection(
            ShareCardState.Success(url = "https://walk.pilgrimapp.org/s/xyz"),
            onOpenPreview = { opened = it },
        )
        composeRule.onNodeWithTag("share-status-open-preview").performClick()
        assertEquals("https://walk.pilgrimapp.org/s/xyz", opened)
    }

    @Test
    fun `success View scroll tap fires onOpenPreview with the url`() {
        var opened: String? = null
        setSection(
            ShareCardState.Success(url = "https://walk.pilgrimapp.org/s/xyz"),
            onOpenPreview = { opened = it },
        )
        composeRule.onNodeWithTag("share-status-view-scroll-button").performClick()
        assertEquals("https://walk.pilgrimapp.org/s/xyz", opened)
    }

    @Test
    fun `error renders the message in place and a Try Again button`() {
        setSection(ShareCardState.Error(message = "The Internet connection appears to be offline."))
        composeRule.onNodeWithText("The Internet connection appears to be offline.").assertIsDisplayed()
        composeRule.onNodeWithText("Try Again").assertIsDisplayed()
    }

    @Test
    fun `tapping Try Again fires onShare`() {
        var fired = false
        setSection(ShareCardState.Error(message = "boom"), onShare = { fired = true })
        composeRule.onNodeWithText("Try Again").performClick()
        assertEquals(true, fired)
    }

    // ---- UI-38: partial card, failed count + repair vs repair-unavailable ----

    @Test
    fun `partial with one failed file uses singular copy and shows the retry button`() {
        setSection(ShareCardState.Partial(url = "https://walk.pilgrimapp.org/s/p", failedCount = 1), repairUnavailable = false)
        composeRule.onNodeWithText("1 file didn't make it — they'll show as unavailable on the page.").assertIsDisplayed()
        composeRule.onNodeWithTag("share-status-retry-button").assertIsDisplayed()
        composeRule.onNodeWithText("Carry the missing files").assertIsDisplayed()
        composeRule.onNodeWithTag("share-status-repair-unavailable-text").assertDoesNotExist()
    }

    @Test
    fun `partial with multiple failed files uses plural copy`() {
        setSection(ShareCardState.Partial(url = "https://walk.pilgrimapp.org/s/p", failedCount = 3), repairUnavailable = false)
        composeRule.onNodeWithText("3 files didn't make it — they'll show as unavailable on the page.").assertIsDisplayed()
    }

    @Test
    fun `partial with repairUnavailable shows the explanation and hides the retry button`() {
        setSection(ShareCardState.Partial(url = "https://walk.pilgrimapp.org/s/p", failedCount = 2), repairUnavailable = true)
        composeRule.onNodeWithTag("share-status-repair-unavailable-text").assertIsDisplayed()
        composeRule.onNodeWithText(
            "These files can no longer be carried — the walk's recordings have changed.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("share-status-retry-button").assertDoesNotExist()
    }

    @Test
    fun `partial still renders the shared card chrome around the failure block`() {
        setSection(ShareCardState.Partial(url = "https://walk.pilgrimapp.org/s/p", failedCount = 1))
        composeRule.onNodeWithText("Shared").assertIsDisplayed()
        composeRule.onNodeWithTag("share-status-view-scroll-button").assertIsDisplayed()
    }

    @Test
    fun `tapping Carry the missing files fires onRetryMissingFiles`() {
        var fired = false
        setSection(
            ShareCardState.Partial(url = "https://walk.pilgrimapp.org/s/p", failedCount = 1),
            repairUnavailable = false,
            onRetryMissingFiles = { fired = true },
        )
        composeRule.onNodeWithTag("share-status-retry-button").performClick()
        assertEquals(true, fired)
    }

    // ---- UI-43/UI-44/UI-45: photosDropped pre-POST consent pause ------

    @Test
    fun `photosDropped renders as an inline card, not asserting any dialog-only semantics`() {
        setSection(ShareCardState.PhotosDropped(prepared = 3, dropped = 1))
        composeRule.onNodeWithTag("share-status-photos-dropped").assertIsDisplayed()
    }

    @Test
    fun `photosDropped singular pluralizes the photo noun on the denominator too`() {
        setSection(ShareCardState.PhotosDropped(prepared = 0, dropped = 1))
        composeRule.onNodeWithText("1 of 1 photo couldn't be prepared — it may not be on this device yet.").assertIsDisplayed()
    }

    @Test
    fun `photosDropped plural denominator when more than one dropped`() {
        setSection(ShareCardState.PhotosDropped(prepared = 2, dropped = 2))
        composeRule.onNodeWithText("2 of 4 photos couldn't be prepared — they may not be on this device yet.").assertIsDisplayed()
    }

    @Test
    fun `photosDropped shows both action buttons`() {
        setSection(ShareCardState.PhotosDropped(prepared = 3, dropped = 1))
        composeRule.onNodeWithText("Share without them").assertIsDisplayed()
        composeRule.onNodeWithText("Don't share yet").assertIsDisplayed()
    }

    @Test
    fun `tapping Share without them fires onShareWithoutDroppedPhotos`() {
        var fired = false
        setSection(
            ShareCardState.PhotosDropped(prepared = 3, dropped = 1),
            onShareWithoutDroppedPhotos = { fired = true },
        )
        composeRule.onNodeWithTag("share-status-without-them-button").performClick()
        assertEquals(true, fired)
    }

    @Test
    fun `tapping Don't share yet fires onCancelDroppedPhotoShare`() {
        var fired = false
        setSection(
            ShareCardState.PhotosDropped(prepared = 3, dropped = 1),
            onCancelDroppedPhotoShare = { fired = true },
        )
        composeRule.onNodeWithTag("share-status-dont-share-yet-button").performClick()
        assertEquals(true, fired)
    }
}

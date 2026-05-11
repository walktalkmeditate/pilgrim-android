// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhotoPreviewSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val photo = WalkPhoto(
        id = 1L,
        walkId = 100L,
        photoUri = "content://media/1",
        pinnedAt = 1_000L,
    )

    @Test
    fun sheetRenders_withPhotoImageAndPinButton() {
        composeRule.setContent {
            PhotoPreviewSheet(
                photo = photo,
                isPinned = false,
                isPinningInFlight = false,
                onPin = {},
                onOpenInGallery = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag("preview-sheet-image").assertExists()
        composeRule.onNodeWithTag("preview-sheet-pin-button").assertExists()
    }

    @Test
    fun pinButtonDisabled_whenAlreadyPinned() {
        var pinFired = 0
        composeRule.setContent {
            PhotoPreviewSheet(
                photo = photo,
                isPinned = true,
                isPinningInFlight = false,
                onPin = { pinFired += 1 },
                onOpenInGallery = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag("preview-sheet-pin-button").performClick()
        composeRule.waitForIdle()
        assert(pinFired == 0) { "expected disabled pin button to swallow click, got $pinFired" }
    }

    @Test
    fun pinButtonDisabled_whenPinningInFlight() {
        var pinFired = 0
        composeRule.setContent {
            PhotoPreviewSheet(
                photo = photo,
                isPinned = false,
                isPinningInFlight = true,
                onPin = { pinFired += 1 },
                onOpenInGallery = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag("preview-sheet-pin-button").performClick()
        composeRule.waitForIdle()
        assert(pinFired == 0) { "expected in-flight pin button to swallow click, got $pinFired" }
    }

    @Test
    fun openInGalleryButton_existsAndFiresCallback() {
        var galleryFired = 0
        composeRule.setContent {
            PhotoPreviewSheet(
                photo = photo,
                isPinned = false,
                isPinningInFlight = false,
                onPin = {},
                onOpenInGallery = { galleryFired += 1 },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag("preview-sheet-open-in-gallery").assertExists()
        composeRule.onNodeWithTag("preview-sheet-open-in-gallery").performClick()
        composeRule.waitForIdle()
        assert(galleryFired == 1) { "expected gallery callback to fire once, got $galleryFired" }
    }

    @Test
    fun buildOpenInGalleryIntent_hasExpectedShape() {
        val intent = buildOpenInGalleryIntent("content://media/external/images/42")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data?.scheme)
        assertEquals("image/*", intent.type)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
    }

    @Test
    fun dragJustBelowThreshold_snapsBackNotDismisses() {
        var dismissed = false
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f),
            ) {
                PhotoPreviewSheet(
                    photo = photo,
                    isPinned = false,
                    isPinningInFlight = false,
                    onPin = {},
                    onOpenInGallery = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        // Drag down 119px at density=1f, just below 120dp threshold
        composeRule.onNodeWithTag("preview-sheet-image").performTouchInput {
            swipeDown(
                startY = 100f,
                endY = 219f, // 119px delta
                durationMillis = 300,
            )
        }
        composeRule.waitForIdle()
        assert(!dismissed) { "expected snap-back at 119dp, but sheet dismissed" }
    }

    @Test
    fun dragJustAboveThreshold_dismisses() {
        var dismissed = false
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f),
            ) {
                PhotoPreviewSheet(
                    photo = photo,
                    isPinned = false,
                    isPinningInFlight = false,
                    onPin = {},
                    onOpenInGallery = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        // Drag down 250px at density=1f, well above 120dp threshold
        composeRule.onNodeWithTag("preview-sheet-image").performTouchInput {
            swipeDown(
                startY = 100f,
                endY = 350f, // 250px delta
                durationMillis = 300,
            )
        }
        composeRule.waitForIdle()
        assert(dismissed) { "expected dismiss with large drag, but sheet snap-back" }
    }
}

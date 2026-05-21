// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.app.Application
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhotoCarouselTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pinnedCandidate = PhotoCandidate(
        uri = "content://media/1",
        takenAtMs = 1_000L,
        capturedLat = null,
        capturedLng = null,
        isPinned = true,
        pinnedPhotoId = 1L,
    )
    private val secondPinned = PhotoCandidate(
        uri = "content://media/2",
        takenAtMs = 2_000L,
        capturedLat = null,
        capturedLng = null,
        isPinned = true,
        pinnedPhotoId = 2L,
    )

    private fun thumbnailTag(c: PhotoCandidate): String {
        val key = c.pinnedPhotoId ?: c.uri.hashCode().toLong()
        return "photo-thumbnail-$key"
    }

    @Test
    fun thumbnail_rendersAt88dpSquare() {
        composeRule.setContent {
            PhotoCarousel(
                candidates = listOf(pinnedCandidate),
                onTogglePin = {},
                onPreview = {},
            )
        }
        composeRule.onNodeWithTag(thumbnailTag(pinnedCandidate)).assertWidthIsEqualTo(88.dp)
    }

    @Test
    fun longPress_activatesThumbnail() {
        composeRule.setContent {
            PhotoCarousel(
                candidates = listOf(pinnedCandidate),
                onTogglePin = {},
                onPreview = {},
            )
        }
        composeRule.onNodeWithTag(thumbnailTag(pinnedCandidate)).performTouchInput {
            longClick(durationMillis = 500)
        }
        composeRule.onNodeWithTag(
            "${thumbnailTag(pinnedCandidate)}-activated",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun touchDrag_clearsActivation() {
        composeRule.setContent {
            PhotoCarousel(
                candidates = listOf(pinnedCandidate, secondPinned),
                onTogglePin = {},
                onPreview = {},
            )
        }
        composeRule.onNodeWithTag(thumbnailTag(pinnedCandidate)).performTouchInput {
            longClick(durationMillis = 500)
        }
        composeRule.onNodeWithTag(
            "${thumbnailTag(pinnedCandidate)}-activated",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithTag("photo-carousel").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag(
            "${thumbnailTag(pinnedCandidate)}-activated",
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun pinnedBadge_visibleOnPinnedPhotos() {
        composeRule.setContent {
            PhotoCarousel(
                candidates = listOf(pinnedCandidate),
                onTogglePin = {},
                onPreview = {},
            )
        }
        composeRule.onNodeWithTag(
            "${thumbnailTag(pinnedCandidate)}-pinned-badge",
            useUnmergedTree = true,
        ).assertExists()
    }
}

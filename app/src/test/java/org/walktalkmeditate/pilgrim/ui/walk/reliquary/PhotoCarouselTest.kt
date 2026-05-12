// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhotoCarouselTest {

    @get:Rule
    val composeRule = createComposeRule()

    // WalkPhoto constructor — confirmed via grep against task 2.1:
    // (id: Long, uuid: String, walkId: Long, photoUri: String, pinnedAt: Long, ...)
    private val photoOne = WalkPhoto(
        id = 1L,
        walkId = 1L,
        photoUri = "content://media/1",
        pinnedAt = 1_000L,
    )
    private val photoTwo = WalkPhoto(
        id = 2L,
        walkId = 1L,
        photoUri = "content://media/2",
        pinnedAt = 2_000L,
    )

    @Test
    fun thumbnail_rendersAt88dpSquare() {
        composeRule.setContent {
            PhotoCarousel(
                photos = listOf(photoOne),
                pinnedIds = setOf(photoOne.id),
                onThumbnailCommit = {},
            )
        }
        composeRule.onNodeWithTag("photo-thumbnail-${photoOne.id}").assertWidthIsEqualTo(88.dp)
    }

    @Test
    fun longPress_activatesThumbnail() {
        composeRule.setContent {
            PhotoCarousel(
                photos = listOf(photoOne),
                pinnedIds = setOf(photoOne.id),
                onThumbnailCommit = {},
            )
        }
        composeRule.onNodeWithTag("photo-thumbnail-${photoOne.id}").performTouchInput {
            longClick(durationMillis = 500)
        }
        composeRule.onNodeWithTag("photo-thumbnail-${photoOne.id}-activated", useUnmergedTree = true).assertExists()
    }

    @Test
    fun touchDrag_clearsActivation() {
        composeRule.setContent {
            PhotoCarousel(
                photos = listOf(photoOne, photoTwo),
                pinnedIds = setOf(photoOne.id, photoTwo.id),
                onThumbnailCommit = {},
            )
        }
        composeRule.onNodeWithTag("photo-thumbnail-${photoOne.id}").performTouchInput {
            longClick(durationMillis = 500)
        }
        composeRule.onNodeWithTag("photo-thumbnail-${photoOne.id}-activated", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("photo-carousel").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("photo-thumbnail-${photoOne.id}-activated", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun pinnedBadge_visibleOnPinnedPhotos() {
        composeRule.setContent {
            PhotoCarousel(
                photos = listOf(photoOne),
                pinnedIds = setOf(photoOne.id),
                onThumbnailCommit = {},
            )
        }
        composeRule.onNodeWithTag("photo-thumbnail-${photoOne.id}-pinned-badge", useUnmergedTree = true).assertExists()
    }
}

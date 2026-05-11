// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhotoReliquarySectionTest {

    @get:Rule val composeRule = createComposeRule()

    private fun photo(id: Long, uri: String = "content://media/picker/0/$id") = WalkPhoto(
        id = id,
        walkId = 1L,
        photoUri = uri,
        pinnedAt = 1_000L + id,
    )

    @Test
    fun `populated empty state shows no header`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        state = ReliquaryState.Populated(emptyList()),
                        onPinPhotos = {},
                        onUnpinPhoto = {},
                        onForegrounded = {},
                        onSettingsClick = {},
                    )
                }
            }
        }
        // Empty Populated renders height-zero leaf — no header shown
        composeRule.onNodeWithText("Reliquary").assertDoesNotExist()
    }

    @Test
    fun `populated with photos shows header and enabled Add button`() {
        val photos = (1L..3L).map { photo(it) }
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        state = ReliquaryState.Populated(photos),
                        onPinPhotos = {},
                        onUnpinPhoto = {},
                        onForegrounded = {},
                        onSettingsClick = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Reliquary").assertIsDisplayed()
        composeRule.onNodeWithText("Add").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `full state (MAX pins) disables Add and shows Full label`() {
        val photos = (1L..MAX_PINS_PER_WALK.toLong()).map { photo(it) }
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        state = ReliquaryState.Populated(photos),
                        onPinPhotos = {},
                        onUnpinPhoto = {},
                        onForegrounded = {},
                        onSettingsClick = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Full").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Add").assertDoesNotExist()
    }

    @Test
    fun `populated with photos renders carousel`() {
        val photos = (1L..3L).map { photo(it) }
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        state = ReliquaryState.Populated(photos),
                        onPinPhotos = {},
                        onUnpinPhoto = {},
                        onForegrounded = {},
                        onSettingsClick = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("photo-thumbnail-1").assertExists()
        composeRule.onNodeWithTag("photo-thumbnail-2").assertExists()
        composeRule.onNodeWithTag("photo-thumbnail-3").assertExists()
    }

    // Picker-launch paths are not driven from a unit test: ActivityResult
    // launchers open system UI that Robolectric can't simulate. The
    // contract construction itself is proven to work at runtime by
    // assembleDebug compiling the ActivityResult contracts against Coil
    // + Photo Picker, plus the VM tests that exercise pinPhotos end-to-end
    // when the launcher callback fires with a hand-constructed Uri list.
    //
    // Carousel interaction (long-press activation, drag-clear, tap-commit)
    // is covered by PhotoCarouselTest. The unpin flow (onThumbnailCommit →
    // PhotoPreviewSheet) is wired in Stage 4.
}

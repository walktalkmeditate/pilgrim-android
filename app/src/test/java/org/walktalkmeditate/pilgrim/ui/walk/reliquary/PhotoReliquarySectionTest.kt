// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `empty state shows header and enabled Add button`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        photos = emptyList(),
                        onPinPhotos = {},
                        onUnpinPhoto = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Reliquary").assertIsDisplayed()
        composeRule.onNodeWithText("Add").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Full").assertDoesNotExist()
    }

    @Test
    fun `full state (MAX pins) disables Add and shows Full label`() {
        val photos = (1L..MAX_PINS_PER_WALK.toLong()).map { photo(it) }
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        photos = photos,
                        onPinPhotos = {},
                        onUnpinPhoto = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Full").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Add").assertDoesNotExist()
    }

    @Test
    fun `grid renders one cell per photo`() {
        val photos = (1L..5L).map { photo(it) }
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        photos = photos,
                        onPinPhotos = {},
                        onUnpinPhoto = {},
                    )
                }
            }
        }
        // Each tile carries a fixed content description; count them.
        composeRule.onAllNodesWithContentDescription("Photo from this walk")
            .assertCountEquals(5)
    }

    @Test
    fun `long-press surfaces confirmation dialog`() {
        val photos = listOf(photo(1L))
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        photos = photos,
                        onPinPhotos = {},
                        onUnpinPhoto = {},
                    )
                }
            }
        }
        composeRule.onAllNodesWithContentDescription("Photo from this walk")[0]
            .performTouchInput { longClick() }

        composeRule.onNodeWithText("Remove from walk?").assertIsDisplayed()
        composeRule.onNodeWithText("Remove").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("Keep").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `confirm Remove invokes onUnpinPhoto with the pressed photo`() {
        val photos = listOf(photo(42L))
        var removed: WalkPhoto? = null
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        photos = photos,
                        onPinPhotos = {},
                        onUnpinPhoto = { removed = it },
                    )
                }
            }
        }

        composeRule.onAllNodesWithContentDescription("Photo from this walk")[0]
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("Remove").performClick()

        assertEquals(42L, removed?.id)
        // Dialog dismissed.
        composeRule.onNodeWithText("Remove from walk?").assertDoesNotExist()
    }

    @Test
    fun `dismiss Keep does not invoke onUnpinPhoto`() {
        val photos = listOf(photo(1L))
        var removed: WalkPhoto? = null
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        photos = photos,
                        onPinPhotos = {},
                        onUnpinPhoto = { removed = it },
                    )
                }
            }
        }

        composeRule.onAllNodesWithContentDescription("Photo from this walk")[0]
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("Keep").performClick()

        assertNull(removed)
        composeRule.onNodeWithText("Remove from walk?").assertDoesNotExist()
    }

    // Picker-launch paths are not driven from a unit test: ActivityResult
    // launchers open system UI that Robolectric can't simulate. The
    // contract construction itself is proven to work at runtime by
    // assembleDebug compiling the ActivityResult contracts against Coil
    // + Photo Picker, plus the VM tests that exercise pinPhotos end-to-end
    // when the launcher callback fires with a hand-constructed Uri list.
}

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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
        composeRule.onAllNodes(tileMatcher())
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
        composeRule.onAllNodes(tileMatcher())[0]
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

        composeRule.onAllNodes(tileMatcher())[0]
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

        composeRule.onAllNodes(tileMatcher())[0]
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("Keep").performClick()

        assertNull(removed)
        composeRule.onNodeWithText("Remove from walk?").assertDoesNotExist()
    }

    @Test
    fun `tombstone caption is announced when the image fails to load`() {
        // Under Robolectric, Coil can't resolve content:// URIs — every
        // SubcomposeAsyncImage fires onError. The outer Box's
        // contentDescription flips to the tombstone hint and the error
        // slot renders the "Photo unavailable" caption. In production
        // this only fires for genuinely broken pins; device QA covers
        // the happy-path contentDescription.
        //
        // **Flake guard:** Coil's onError resolves asynchronously under
        // Robolectric — the call returns before the error propagates
        // through SubcomposeAsyncImage. Local runs on fast machines
        // catch the tombstone in time; CI runners (slower) have failed
        // intermittently against this same assertion. `waitUntil` polls
        // until the node appears or the timeout fires, removing the
        // race entirely.
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
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("Photo unavailable")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Photo unavailable").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(
            "Photo unavailable — long press to remove",
        ).assertCountEquals(1)
    }

    // Picker-launch paths are not driven from a unit test: ActivityResult
    // launchers open system UI that Robolectric can't simulate. The
    // contract construction itself is proven to work at runtime by
    // assembleDebug compiling the ActivityResult contracts against Coil
    // + Photo Picker, plus the VM tests that exercise pinPhotos end-to-end
    // when the launcher callback fires with a hand-constructed Uri list.

    /**
     * Coil's ImageLoader is a process-wide singleton; state can leak
     * across test invocations under Robolectric (first test fails to
     * load → cache records failure → subsequent tests see fail state;
     * but ordering isn't guaranteed). Match EITHER content description
     * so the layout assertions are robust to either load outcome. The
     * specific tombstone caption ("Photo unavailable") is still
     * asserted by the dedicated tombstone test.
     */
    private fun tileMatcher() =
        hasContentDescription("Photo from this walk")
            .or(hasContentDescription("Photo unavailable — long press to remove"))
}

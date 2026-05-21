// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhotoReliquarySectionStateTest {

    @get:Rule val composeRule = createComposeRule()

    private fun photo(id: Long) = PhotoCandidate(uri = "content://media/picker/0/$id", takenAtMs = 1000L, capturedLat = null, capturedLng = null, isPinned = true, pinnedPhotoId = id)

    private fun render(state: ReliquaryState) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoReliquarySection(
                        state = state,
                        onTogglePin = {},
                        onForegrounded = {},
                        onSettingsClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun toggleOff_rendersEmptyTree() {
        render(ReliquaryState.ToggleOff)
        // Empty Box has zero size — verify node is in tree (not "displayed").
        assertEquals(
            1,
            composeRule.onAllNodesWithTag(TAG_RELIQUARY_TOGGLE_OFF).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun permissionDenied_rendersPrompt_grantButton_andSettingsFallback() {
        render(ReliquaryState.PermissionDenied)
        composeRule.onNodeWithTag(TAG_RELIQUARY_PERMISSION_PROMPT).assertIsDisplayed()
        // Primary action requests the permission in-app (iOS parity);
        // Open settings remains only as the permanently-denied fallback.
        composeRule.onNodeWithTag(TAG_RELIQUARY_GRANT_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RELIQUARY_SETTINGS_BUTTON).assertIsDisplayed()
    }

    @Test
    fun loading_rendersDeferredSkeleton() {
        // Disable auto-advance so we control exactly when the clock moves.
        composeRule.mainClock.autoAdvance = false
        render(ReliquaryState.Loading)
        // Let composition run first frame — LaunchedEffect is launched but
        // delay(300ms) has not yet elapsed.
        composeRule.mainClock.advanceTimeBy(16L)
        // Confirm the node is absent before the threshold.
        assertEquals(0, composeRule.onAllNodesWithTag(TAG_RELIQUARY_SKELETON).fetchSemanticsNodes().size)
        // Advance past the 300ms delay threshold.
        composeRule.mainClock.advanceTimeBy(290L)
        composeRule.mainClock.advanceTimeBy(16L)
        // Now show=true has fired and the skeleton node must be in the tree.
        assertEquals(
            1,
            composeRule.onAllNodesWithTag(TAG_RELIQUARY_SKELETON).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun populatedWithPhotos_rendersCarousel() {
        render(ReliquaryState.Populated(listOf(photo(1L))))
        composeRule.onNodeWithTag(TAG_RELIQUARY_CAROUSEL).assertIsDisplayed()
    }

    @Test
    fun populatedEmpty_rendersHeightZeroLeaf() {
        render(ReliquaryState.Populated(emptyList()))
        assertEquals(0, composeRule.onAllNodesWithTag(TAG_RELIQUARY_SKELETON).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag(TAG_RELIQUARY_CAROUSEL).fetchSemanticsNodes().size)
    }
}

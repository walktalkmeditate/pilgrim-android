// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.honor.HonorImportState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * The Ways sheet's shared sections (shared-walk spec S4 §6–§7, S2 §8):
 * the "Shared with you" rows, and the paste field, its "Open", and the
 * line under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorWaysSheetTest {

    @get:Rule val composeRule = createComposeRule()

    private val importState = mutableStateOf<HonorImportState>(HonorImportState.Idle)
    private val chosen = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private fun show(shared: SharedWaysUiState = SharedWaysUiState.Loaded(emptyList())) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 1600.dp)) {
                    HonorWaysSheetContent(
                        shared = shared,
                        importState = importState.value,
                        onClose = {},
                        onChooseShared = { chosen += it },
                        onWalkOneOfYours = {},
                        onOpenPasted = { opened += it },
                    )
                }
            }
        }
    }

    private val field get() = composeRule.onNode(hasSetTextAction())
    private val open get() = composeRule.onNodeWithText("Open")

    @Test
    fun `each shared Way is one button - its title, its date, a dot, its counts - and hands over its id`() {
        show(
            SharedWaysUiState.Loaded(
                listOf(
                    SharedWayRow("share:Qoi4YmPHLN", "Rúa do Franco → Obradoiro", "Aug 1, 2026", voiceCount = 2, photoCount = 1),
                    SharedWayRow("share:Second1234", "Sep 3, 2026", "Sep 3, 2026", voiceCount = 0, photoCount = 0),
                ),
            ),
        )

        composeRule.onNodeWithText("2 voices · 1 photo").assertIsDisplayed()
        composeRule.onNodeWithText("Aug 1, 2026").assertIsDisplayed()
        composeRule.onNodeWithText("a quiet way").assertIsDisplayed()
        composeRule.onAllNodesWithText("no ways yet. Accept a shared walk, or walk one of yours again.").assertCountEquals(0)
        composeRule.onNodeWithText("Rúa do Franco → Obradoiro").assert(isButton()).performClick()
        assertEquals(listOf("share:Qoi4YmPHLN"), chosen)
    }

    @Test
    fun `a row whose voices were returned to the trail says so in place of its counts`() {
        show(SharedWaysUiState.Loaded(listOf(SharedWayRow("share:Qoi4YmPHLN", "t", "Aug 1, 2026", 9, 4, voicesReturned = true))))

        composeRule.onNodeWithText("voices returned to the trail").assertIsDisplayed()
        composeRule.onAllNodesWithText("9 voices · 4 photos").assertCountEquals(0)
    }

    @Test
    fun `nothing shows while the list is read, and an empty one says iOS's copy`() {
        show(SharedWaysUiState.Loading)
        composeRule.onAllNodesWithText("no ways yet. Accept a shared walk, or walk one of yours again.").assertCountEquals(0)
    }

    @Test
    fun `the paste section is iOS's - its header, its placeholder, and its footer naming the walk host`() {
        show()

        composeRule.onNodeWithText("From a shared walk").assertIsDisplayed()
        composeRule.onNodeWithText("paste a walk link").assertIsDisplayed()
        composeRule.onNodeWithText("A walk someone shared with you, from walk.pilgrimapp.org.").assertIsDisplayed()
    }

    @Test
    fun `Open is enabled only while the text parses and nothing is fetching`() {
        show()
        open.assertIsNotEnabled()

        field.performTextInput("Qoi4YmPHL")
        open.assertIsNotEnabled()
        field.performTextClearance()
        field.performTextInput("walk.pilgrimapp.org/Qoi4YmPHLN")
        open.assertIsEnabled()

        importState.value = HonorImportState.Fetching
        open.assertIsNotEnabled()
        importState.value = HonorImportState.Failed(WayError.NOT_FOUND)
        open.assertIsEnabled()
    }

    @Test
    fun `Open hands over the text, and Return opens nothing`() {
        show()
        field.performTextInput("https://honor.pilgrimapp.org/Qoi4YmPHLN")

        field.performImeAction()
        assertTrue(opened.isEmpty())
        open.performClick()

        assertEquals(listOf("https://honor.pilgrimapp.org/Qoi4YmPHLN"), opened)
    }

    @Test
    fun `the line under Open speaks the fetch and each failure, and the field keeps its text`() {
        show()
        field.performTextInput("Qoi4YmPHLN")
        composeRule.onAllNodesWithText("reaching for the walk…").assertCountEquals(0)

        importState.value = HonorImportState.Fetching
        composeRule.onNodeWithText("reaching for the walk…").assertIsDisplayed()

        mapOf(
            WayError.NOT_FOUND to "couldn't find that walk. Check the link, or it may have returned to the trail.",
            WayError.RETURNED_TO_TRAIL to "This walk has returned to the trail",
            WayError.UNAVAILABLE to "couldn't reach the walk",
        ).forEach { (error, line) ->
            importState.value = HonorImportState.Failed(error)
            composeRule.onNodeWithText(line).assertIsDisplayed()
        }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Qoi4YmPHLN")))
    }

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
}

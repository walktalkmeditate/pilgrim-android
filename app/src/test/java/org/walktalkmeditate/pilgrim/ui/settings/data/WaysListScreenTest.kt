// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Settings → Ways and the Data card row on screen (shared-walk spec S4 §2–§5,
 * §13.1–§13.2), and the list's package footer (pilgrimage-stage spec P2 §11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaysListScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private val rows = listOf(
        WayListRow("share:Qoi4YmPHLN", "Rúa do Franco → Obradoiro", "Jul 31, 2026", bytes = 23_500_000, voicesReturned = false),
        WayListRow("share:Older12345", "Ponte Maceira", "Jul 2, 2026", bytes = 0, voicesReturned = true),
    )

    private val deleted = mutableListOf<String>()
    private var deletedAll = 0

    private fun show(state: WaysListUiState) {
        composeRule.setContent {
            PilgrimTheme {
                WaysListContent(state = state, onDelete = { deleted += it }, onDeleteAll = { deletedAll++ })
            }
        }
    }

    @Test
    fun `an empty list reads no ways yet, with no Delete all`() {
        show(WaysListUiState.Loaded(emptyList()))

        composeRule.onNodeWithText("no ways yet").assertIsDisplayed()
        composeRule.onAllNodesWithText("Delete all Ways").assertCountEquals(0)
    }

    @Test
    fun `each row reads its title over its date and its size or its returned voices`() {
        show(WaysListUiState.Loaded(rows))

        composeRule.onNodeWithText("Rúa do Franco → Obradoiro").assertIsDisplayed()
        composeRule.onNodeWithText("Jul 31, 2026 · 23.5 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Jul 2, 2026 · voices returned to the trail").assertIsDisplayed()
    }

    // iOS's `.onDelete`, which VoiceOver offers as the row's "Delete": unconfirmed.
    @Test
    fun `a row's Delete action deletes it at once`() {
        show(WaysListUiState.Loaded(rows))

        // A list property, not an action: fetched and invoked, the house idiom (MeditationScreenTest).
        val delete = composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasAnyDescendant(hasText("Ponte Maceira")))
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .single { it.label == "Delete" }
        composeRule.runOnIdle { delete.action() }

        assertEquals(listOf("share:Older12345"), deleted)
    }

    @Test
    fun `Delete all asks with iOS's title, message, and buttons, and Cancel deletes nothing`() {
        show(WaysListUiState.Loaded(rows))

        composeRule.onNodeWithText("Delete all Ways").performClick()
        composeRule.onNodeWithText("Delete all Ways?").assertIsDisplayed()
        composeRule.onNodeWithText("Their voices and photos leave this phone. Your own walks are untouched.").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, deletedAll)

        composeRule.onNodeWithText("Delete all Ways").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(1, deletedAll)
    }

    // The package footer (pilgrimage-stage spec P2 §11): the list's last row.

    private val footer = "the Camino de Santiago (Francés) keeps its 2 stages on its route page"

    @Test
    fun `the package footer is the last row, after Delete all`() {
        show(WaysListUiState.Loaded(rows, packageRouteName = "Camino de Santiago (Francés)", packageStageCount = 2))

        val deleteAll = composeRule.onNodeWithText("Delete all Ways").fetchSemanticsNode().boundsInRoot.top
        val line = composeRule.onNodeWithText(footer).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue("the footer comes last", line > deleteAll)
    }

    @Test
    fun `with no Ways the package footer follows no ways yet`() {
        show(WaysListUiState.Loaded(emptyList(), packageRouteName = "Camino de Santiago (Francés)", packageStageCount = 2))

        val empty = composeRule.onNodeWithText("no ways yet").fetchSemanticsNode().boundsInRoot.top
        val line = composeRule.onNodeWithText(footer).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue(line > empty)
    }

    @Test
    fun `no route to name the stages by, no footer`() {
        show(WaysListUiState.Loaded(rows, packageRouteName = null, packageStageCount = 2))

        composeRule.onAllNodesWithText("keeps its", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the Data card shows Ways with its count and size, and opens the list`() {
        val actions = mutableListOf<SettingsAction>()
        composeRule.setContent {
            PilgrimTheme {
                DataCard(onAction = { actions += it }, showsWays = true, waysTotals = WaysTotals(2, 3_400_000))
            }
        }

        composeRule.onNodeWithText("2 ways · 3.4 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Ways").performClick()

        assertEquals(listOf<SettingsAction>(SettingsAction.OpenWays), actions)
    }

    @Test
    fun `with Ways unavailable the Data card keeps only Export and Import`() {
        composeRule.setContent {
            PilgrimTheme { DataCard(onAction = {}, showsWays = false) }
        }

        composeRule.onNodeWithText("Export & Import").assertIsDisplayed()
        composeRule.onAllNodesWithText("Ways").assertCountEquals(0)
    }
}
